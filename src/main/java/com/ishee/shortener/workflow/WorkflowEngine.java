package com.ishee.shortener.workflow;

import com.ishee.shortener.workflow.WorkflowRun.Step;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class WorkflowEngine {
    private final WorkflowStore store;
    private final EngineeringActions actions;

    public WorkflowEngine(WorkflowStore store, EngineeringActions actions) {
        this.store = store;
        this.actions = actions;
    }

    public synchronized WorkflowRun create(String scenario, String requirement, boolean useModel) {
        if (!Set.of("greenfield", "brownfield", "ambiguous").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("Requirement is required");
        WorkflowRun run = new WorkflowRun();
        run.id = UUID.randomUUID().toString().substring(0, 12);
        run.createdAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        run.scenario = scenario;
        run.requirement = requirement;
        run.useModel = useModel;
        graph(run);
        store.save(run);
        store.event(run.id, "created", Map.of("scenario", scenario, "version", 1, "useModel", useModel));
        return run;
    }

    private void add(WorkflowRun run, String id, String kind, String action, String... deps) {
        run.steps.put(id, new Step(id, kind, action, deps));
    }

    private void graph(WorkflowRun run) {
        run.steps.clear();
        add(run, "intake", "agent", "intake");
        String planDep = "intake";
        if (run.scenario.equals("ambiguous")) {
            add(run, "requirements_approval", "approval", "approval", "intake");
            planDep = "requirements_approval";
        }
        add(run, "plan", "agent", "plan", planDep);
        add(run, "design", "agent", "design", "plan");
        add(run, "proposal", "agent", "proposal", "design");
        String stageDep = "proposal";
        if (run.useModel || run.scenario.equals("brownfield")) {
            add(run, "change_approval", "approval", "approval", "proposal");
            stageDep = "change_approval";
        }
        add(run, "stage", "agent", "stage", stageDep);
        add(run, "security", "agent", "security", "stage");
        add(run, "test", "agent", "test", "security");
        add(run, "docs", "agent", "docs", "stage");
        add(run, "review", "agent", "review", "security", "test", "docs");
        add(run, "release_approval", "approval", "approval", "review");
        add(run, "release", "agent", "release", "release_approval");
    }

    @SuppressWarnings("unchecked")
    private void insertPlannedTasks(WorkflowRun run, Map<String, Object> plan) {
        Object tasksValue = plan.get("tasks");
        if (!(tasksValue instanceof List<?> tasks) || tasks.isEmpty() || tasks.size() > 6) {
            throw new IllegalArgumentException("Invalid task decomposition");
        }
        List<String> created = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> operations = new HashSet<>();
        for (Object item : tasks) {
            if (!(item instanceof Map<?, ?> task)) throw new IllegalArgumentException("Invalid task");
            String id = String.valueOf(task.get("id"));
            String action = String.valueOf(task.get("action"));
            if (!id.matches("[a-z][a-z0-9_]{2,35}") || !ids.add(id) || !EngineeringActions.validAction(action)) {
                throw new IllegalArgumentException("Invalid task identity or action");
            }
            Object refs = task.get("dependsOn");
            if (!(refs instanceof List<?> deps)) throw new IllegalArgumentException("Invalid task dependencies");
            List<String> dependencies = new ArrayList<>(List.of("stage"));
            for (Object dependency : deps) {
                if (!(dependency instanceof String value) || !ids.contains(value) || value.equals(id)) {
                    throw new IllegalArgumentException("Task dependency must refer to an earlier task");
                }
                dependencies.add("task_" + value);
            }
            add(run, "task_" + id, "agent", action, dependencies.toArray(String[]::new));
            created.add("task_" + id);
            operations.add(action);
        }
        if (run.useModel && (!operations.contains("SOURCE_PATCH") || !operations.contains("ADD_REGRESSION_TEST"))) {
            throw new IllegalArgumentException("Model plan must include source change and regression test");
        }
        run.steps.get("security").deps = List.copyOf(created);
        run.steps.get("docs").deps = List.copyOf(created);
    }

    public synchronized WorkflowRun get(String id) {
        return store.get(id);
    }

    public synchronized WorkflowRun approve(String id, String gate, String actor, String decision) {
        WorkflowRun run = store.get(id);
        if (actor == null || actor.isBlank() || Set.of("agent", "system", "automation").contains(actor.toLowerCase())) {
            throw new IllegalArgumentException("Named human reviewer required");
        }
        if (!Set.of("approved", "rejected").contains(decision)) throw new IllegalArgumentException("Invalid decision");
        Step step = run.steps.get(gate);
        if (step == null || !step.kind.equals("approval") || !step.status.equals("blocked")) {
            throw new IllegalArgumentException("Gate is not awaiting approval");
        }
        run.approvals.put(gate, new WorkflowRun.Approval(actor, decision, OffsetDateTime.now(ZoneOffset.UTC).toString(), run.version));
        store.event(id, "human_decision", Map.of("actor", actor, "gate", gate, "decision", decision, "version", run.version));
        if (decision.equals("rejected")) {
            step.status = "rejected";
            run.status = "stopped";
            actions.rollback(run);
            store.event(id, "rollback", Map.of("scope", "staged_candidate", "reason", "approval_rejected"));
            run.elapsedMs = elapsed(run);
        } else {
            step.status = "completed";
            run.status = "pending";
        }
        store.save(run);
        return run;
    }

    public synchronized WorkflowRun revise(String id, String requirement) {
        WorkflowRun run = store.get(id);
        requireRunning(run);
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("Requirement is required");
        run.version++;
        run.requirement = requirement;
        run.approvals.clear();
        run.status = "pending";
        graph(run);
        actions.rollback(run);
        store.event(id, "rollback", Map.of("scope", "staged_candidate", "reason", "requirement_changed"));
        store.event(id, "replan", Map.of("reason", "requirement_changed", "version", run.version));
        run.replans++;
        store.save(run);
        return run;
    }

    public synchronized WorkflowRun replan(String id, String changedStage) {
        WorkflowRun run = store.get(id);
        requireRunning(run);
        if (!run.steps.containsKey(changedStage)) throw new IllegalArgumentException("Unknown workflow stage");
        return invalidate(run, changedStage, "upstream_output_changed");
    }

    private WorkflowRun invalidate(WorkflowRun run, String changed, String reason) {
        Set<String> affected = new LinkedHashSet<>(List.of(changed));
        int previousSize;
        do {
            previousSize = affected.size();
            for (Step step : run.steps.values()) {
                if (step.deps.stream().anyMatch(affected::contains)) affected.add(step.id);
            }
        } while (affected.size() != previousSize);
        if (affected.contains("stage")) {
            actions.rollback(run);
            store.event(run.id, "rollback", Map.of("scope", "staged_candidate", "reason", reason));
        }
        for (String id : affected) {
            Step original = run.steps.get(id);
            Step reset = new Step(original.id, original.kind, original.action, original.deps.toArray(String[]::new));
            run.steps.put(id, reset);
            run.approvals.remove(id);
        }
        run.replans++;
        run.status = "pending";
        store.event(run.id, "replan", Map.of("changed", changed, "invalidated", affected, "reason", reason,
                "version", run.version));
        store.save(run);
        return run;
    }

    private WorkflowRun checkDrift(WorkflowRun run) {
        for (Step step : run.steps.values()) {
            if (!step.status.equals("completed")) continue;
            for (var item : step.fingerprints.entrySet()) {
                try {
                    Path path = Path.of(item.getKey());
                    if (!Files.isRegularFile(path) || !WorkflowStore.hash(Files.readString(path)).equals(item.getValue())) {
                        return invalidate(run, step.id, "artifact_hash_changed");
                    }
                } catch (Exception error) {
                    return invalidate(run, step.id, "artifact_missing");
                }
            }
        }
        return run;
    }

    public synchronized WorkflowRun stop(String id) {
        WorkflowRun run = store.get(id);
        requireRunning(run);
        actions.rollback(run);
        run.status = "stopped";
        run.elapsedMs = elapsed(run);
        store.save(run);
        store.event(id, "safe_stop", Map.of("reason", "human_requested"));
        return run;
    }

    public synchronized WorkflowRun advance(String id, boolean faultOnce) {
        WorkflowRun run = store.get(id);
        if (Set.of("completed", "stopped", "failed").contains(run.status)) return run;
        checkDrift(run);
        run.status = "running";
        store.save(run);
        while (true) {
            List<Step> ready = run.steps.values().stream()
                    .filter(step -> step.status.equals("pending") && step.deps.stream()
                            .allMatch(dep -> run.steps.containsKey(dep) && run.steps.get(dep).status.equals("completed"))).toList();
            if (ready.isEmpty()) break;
            List<Step> executable = new ArrayList<>();
            for (Step step : ready) {
                if (step.kind.equals("approval")) {
                    step.status = "blocked";
                    store.event(id, "approval_requested", Map.of("gate", step.id, "version", run.version));
                } else executable.add(step);
            }
            if (!executable.isEmpty()) {
                store.event(id, "parallel_batch", Map.of("tasks", executable.stream().map(step -> step.id).toList()));
                WorkflowRun current = run;
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Outcome>> pending = executable.stream().map(step ->
                            CompletableFuture.supplyAsync(() -> attempt(current, step, faultOnce), pool)).toList();
                    for (int index = 0; index < executable.size(); index++) {
                        Step step = executable.get(index);
                        Outcome result = pending.get(index).join();
                        step.attempts = result.attempts;
                        step.durationMs = result.durationMs;
                        if (result.error != null) {
                            step.status = "failed";
                            step.error = result.error;
                            store.event(id, "task_failed", Map.of("task", step.id, "error", result.error));
                            actions.rollback(run);
                            store.event(id, "rollback", Map.of("scope", "staged_candidate", "reason", "task_failed"));
                            run.status = "failed";
                            run.elapsedMs = elapsed(run);
                            store.save(run);
                            return run;
                        }
                        if (step.id.equals("plan")) {
                            try {
                                insertPlannedTasks(run, result.result);
                            } catch (RuntimeException invalidPlan) {
                                step.status = "failed";
                                step.error = invalidPlan.getMessage();
                                store.event(id, "task_failed", Map.of("task", step.id, "error", step.error));
                                actions.rollback(run);
                                store.event(id, "rollback", Map.of("scope", "staged_candidate", "reason", "invalid_plan"));
                                run.status = "failed";
                                run.elapsedMs = elapsed(run);
                                store.save(run);
                                return run;
                            }
                        }
                        step.status = "completed";
                        step.result = result.result;
                        step.fingerprints = fingerprints(result.result);
                        store.event(id, "task_completed", Map.of("task", step.id, "attempts", result.attempts,
                                "durationMs", result.durationMs, "version", run.version));
                        if (result.attempts > 1) {
                            run.recoveryMs = result.durationMs;
                            store.event(id, "retry", Map.of("task", step.id, "attempts", result.attempts));
                        }
                    }
                }
            }
            store.save(run);
        }
        if (run.steps.values().stream().allMatch(step -> step.status.equals("completed"))) {
            run.status = "completed";
            run.elapsedMs = elapsed(run);
            store.event(id, "completed", Map.of("version", run.version, "elapsedMs", run.elapsedMs));
        } else if (run.steps.values().stream().anyMatch(step -> step.status.equals("blocked"))) {
            run.status = "waiting_approval";
        } else {
            run.status = "failed";
            actions.rollback(run);
            store.event(id, "safe_stop", Map.of("reason", "dependency_deadlock"));
        }
        store.save(run);
        return run;
    }

    private record Outcome(int attempts, long durationMs, Map<String, Object> result, String error) {}

    private Outcome attempt(WorkflowRun run, Step step, boolean faultOnce) {
        long start = System.nanoTime();
        int retries = step.action.equals("test") ? 1 : 0;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                if (faultOnce && step.action.equals("test") && attempt == 1) {
                    throw new IllegalStateException("Injected test runner fault");
                }
                Map<String, Object> output = actions.execute(run, step);
                return new Outcome(attempt, Duration.ofNanos(System.nanoTime() - start).toMillis(), output, null);
            } catch (Exception error) {
                if (attempt > retries) return new Outcome(attempt, Duration.ofNanos(System.nanoTime() - start).toMillis(),
                        Map.of(), error.getClass().getSimpleName() + ": " + error.getMessage());
                try { Thread.sleep(100L * attempt); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Outcome(attempt, Duration.ofNanos(System.nanoTime() - start).toMillis(), Map.of(), "Interrupted");
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private Map<String, String> fingerprints(Map<String, Object> result) {
        Map<String, String> checks = new LinkedHashMap<>();
        for (String key : List.of("file", "proposal", "diff")) {
            Object value = result.get(key);
            if (value instanceof String path) {
                try {
                    if (Files.isRegularFile(Path.of(path))) checks.put(path, WorkflowStore.hash(Files.readString(Path.of(path))));
                } catch (Exception error) {
                    throw new IllegalStateException("Could not fingerprint workflow output", error);
                }
            }
        }
        return checks;
    }

    private long elapsed(WorkflowRun run) {
        return Duration.between(OffsetDateTime.parse(run.createdAt), OffsetDateTime.now(ZoneOffset.UTC)).toMillis();
    }

    private void requireRunning(WorkflowRun run) {
        if (Set.of("completed", "stopped", "failed").contains(run.status)) {
            throw new IllegalArgumentException("Cannot modify terminal workflow");
        }
    }

    public List<Map<String, Object>> events(String id) {
        store.get(id);
        return store.events(id);
    }

    public Map<String, Object> metrics() {
        return store.metrics();
    }

    public boolean auditValid(String id) {
        return store.verify(id);
    }
}
