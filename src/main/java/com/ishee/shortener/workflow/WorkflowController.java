package com.ishee.shortener.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
    private final WorkflowEngine engine;

    public WorkflowController(WorkflowEngine engine) {
        this.engine = engine;
    }

    public record CreateRequest(String scenario, String requirement, boolean useModel) {}
    public record ApprovalRequest(String gate, String actor, String decision) {}
    public record RevisionRequest(String requirement) {}
    public record ReplanRequest(String changedStage) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRun create(@RequestBody CreateRequest request) {
        return engine.create(request.scenario(), request.requirement(), request.useModel());
    }

    @GetMapping("/{id}")
    public WorkflowRun get(@PathVariable String id) {
        return engine.get(id);
    }

    @PostMapping("/{id}/advance")
    public WorkflowRun advance(@PathVariable String id, @RequestParam(defaultValue = "false") boolean faultOnce) {
        return engine.advance(id, faultOnce);
    }

    @PostMapping("/{id}/approvals")
    public WorkflowRun approve(@PathVariable String id, @RequestBody ApprovalRequest request) {
        return engine.approve(id, request.gate(), request.actor(), request.decision() == null ? "approved" : request.decision());
    }

    @PutMapping("/{id}/requirement")
    public WorkflowRun revise(@PathVariable String id, @RequestBody RevisionRequest request) {
        return engine.revise(id, request.requirement());
    }

    @PostMapping("/{id}/replan")
    public WorkflowRun replan(@PathVariable String id, @RequestBody ReplanRequest request) {
        return engine.replan(id, request.changedStage());
    }

    @PostMapping("/{id}/stop")
    public WorkflowRun stop(@PathVariable String id) {
        return engine.stop(id);
    }

    @GetMapping("/{id}/events")
    public Map<String, Object> events(@PathVariable String id) {
        return Map.of("auditValid", engine.auditValid(id), "events", engine.events(id));
    }

    @GetMapping("/{id}/diff")
    public String diff(@PathVariable String id) throws IOException {
        engine.get(id);
        Path path = Path.of("artifacts", id, "proposed_change.diff");
        if (!Files.exists(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not available");
        return Files.readString(path);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return engine.metrics();
    }
}
