package com.ishee.shortener.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

@Component
public class EngineeringActions {
    private static final String POLICY_FILE = "src/main/java/com/ishee/shortener/api/AliasPolicy.java";
    private static final String REGRESSION_FILE = "src/test/java/com/ishee/shortener/GeneratedChangeTest.java";
    private static final String PRIVACY_FILE = "src/test/java/com/ishee/shortener/PrivacyContractTest.java";
    private static final Set<String> EDITABLE = Set.of(POLICY_FILE,
            "src/main/java/com/ishee/shortener/api/UrlPolicy.java",
            "src/main/java/com/ishee/shortener/api/LinkService.java",
            "src/main/java/com/ishee/shortener/api/LinkController.java");
    private static final Set<String> ACTIONS = Set.of("BASELINE", "SOURCE_PATCH", "ADD_REGRESSION_TEST", "PRIVACY_CONTRACT_TEST");
    private static final List<String> DANGEROUS = List.of("Runtime.getRuntime", "ProcessBuilder", "System.exit", "java.lang.reflect",
            "Class.forName", "Files.write", "Files.delete", "Files.readString", "java.net.http", "Socket(", "new Thread(", "javax.script");

    private final ModelGateway model;
    private final ObjectMapper json;
    private final WorkflowStore store;
    private final Path checkout = Path.of("").toAbsolutePath().normalize();
    private final Path artifacts = Path.of("artifacts").toAbsolutePath().normalize();

    public EngineeringActions(ModelGateway model, ObjectMapper json, WorkflowStore store) {
        this.model = model;
        this.json = json;
        this.store = store;
    }

    public static boolean validAction(String action) {
        return ACTIONS.contains(action);
    }

    public Path artifactRoot(WorkflowRun run) throws IOException {
        Path directory = artifacts.resolve(run.id);
        Files.createDirectories(directory);
        return directory;
    }

    private Map<String, Object> saved(Path path, Object data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(data) + "\n");
        return Map.of("file", path.toString());
    }

    private Map<String, Object> report(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        return Map.of("file", path.toString());
    }

    private JsonNode read(Path path) throws IOException {
        return json.readTree(Files.readString(path));
    }

    public Map<String, Object> execute(WorkflowRun run, WorkflowRun.Step step) throws Exception {
        Path root = artifactRoot(run);
        return switch (step.action) {
            case "intake" -> intake(run, root);
            case "plan" -> plan(run, root);
            case "design" -> design(run, root);
            case "proposal" -> proposal(run, root);
            case "stage" -> stage(root);
            case "BASELINE" -> baseline(root);
            case "SOURCE_PATCH" -> patch(root);
            case "ADD_REGRESSION_TEST" -> regression(root);
            case "PRIVACY_CONTRACT_TEST" -> privacy(root);
            case "security" -> security(root);
            case "test" -> test(root);
            case "docs" -> docs(run, root);
            case "review" -> review(run, root);
            case "release" -> release(run, root);
            default -> throw new IllegalArgumentException("Unknown engineering action " + step.action);
        };
    }

    private Map<String, Object> intake(WorkflowRun run, Path root) throws Exception {
        if (run.requirement.isBlank()) throw new IllegalArgumentException("Requirement cannot be empty");
        String lower = run.requirement.toLowerCase();
        if (List.of("skip approval", "disable audit", "ignore security", "bypass review").stream().anyMatch(lower::contains)) {
            throw new SecurityException("Requirement requests bypassing a governance control");
        }
        List<String> criteria = new ArrayList<>();
        List<String> assumptions = new ArrayList<>(List.of("Local prototype", "Review is required before release"));
        List<String> ambiguities = new ArrayList<>();
        if (run.scenario.equals("ambiguous")) ambiguities.add("Define privacy and retention expectations before making changes");
        else criteria.add(run.requirement);
        if (run.useModel) {
            JsonNode answer = model.ask("Interpret the requirement. Return JSON arrays acceptanceCriteria, assumptions, ambiguities. Keep each concise.",
                    Map.of("requirement", run.requirement, "scenario", run.scenario));
            criteria = strings(answer.path("acceptanceCriteria"));
            assumptions = strings(answer.path("assumptions"));
            ambiguities = strings(answer.path("ambiguities"));
        }
        return saved(root.resolve("requirement.json"), Map.of("original", run.requirement, "version", run.version,
                "acceptanceCriteria", criteria, "assumptions", assumptions, "ambiguities", ambiguities));
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray() || node.size() > 12) throw new IllegalArgumentException("Invalid model list");
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().length() > 250 || value.asText().isBlank()) {
                throw new IllegalArgumentException("Invalid model list item");
            }
            result.add(value.asText());
        });
        return result;
    }

    private Map<String, Object> plan(WorkflowRun run, Path root) throws Exception {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (run.useModel) {
            JsonNode answer = model.ask("Decompose the requested source change into executable tasks. Return JSON: tasks array of "
                            + "{id,action,dependsOn}. Actions must be SOURCE_PATCH and ADD_REGRESSION_TEST, at least one each. "
                            + "Use unique simple IDs and refer only to earlier IDs in dependsOn. Do not add other actions or approvals.",
                    Map.of("requirement", run.requirement, "allowedActions", List.of("SOURCE_PATCH", "ADD_REGRESSION_TEST")));
            JsonNode items = answer.path("tasks");
            if (!items.isArray() || items.size() < 2 || items.size() > 5) throw new IllegalArgumentException("Invalid model task plan");
            for (JsonNode item : items) {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("id", item.path("id").asText());
                task.put("action", item.path("action").asText());
                task.put("dependsOn", strings(item.path("dependsOn")));
                tasks.add(task);
            }
        } else if (run.scenario.equals("brownfield")) {
            tasks.add(task("update_alias_policy", "SOURCE_PATCH"));
            tasks.add(task("add_alias_test", "ADD_REGRESSION_TEST", "update_alias_policy"));
        } else if (run.scenario.equals("ambiguous")) {
            tasks.add(task("verify_privacy", "PRIVACY_CONTRACT_TEST"));
        } else {
            tasks.add(task("verify_baseline", "BASELINE"));
        }
        Path path = root.resolve("plan.json");
        saved(path, Map.of("tasks", tasks, "version", run.version));
        return Map.of("file", path.toString(), "tasks", tasks);
    }

    private Map<String, Object> task(String id, String action, String... dependencies) {
        return Map.of("id", id, "action", action, "dependsOn", List.of(dependencies));
    }

    private Map<String, Object> design(WorkflowRun run, Path root) throws Exception {
        List<String> impacted = run.scenario.equals("brownfield") || run.useModel
                ? List.of(POLICY_FILE, REGRESSION_FILE)
                : List.of("src/main/java/com/ishee/shortener/api/LinkService.java");
        List<String> tradeoffs = List.of("Small, reviewable edits instead of unrestricted repository changes");
        if (run.useModel) {
            JsonNode response = model.ask("Return JSON with impactedFiles and tradeoffs arrays. Impacted files must come from the source inventory.",
                    Map.of("requirement", run.requirement, "available", List.copyOf(EDITABLE)));
            impacted = strings(response.path("impactedFiles"));
            for (String file : impacted) if (!EDITABLE.contains(file)) throw new SecurityException("Model proposed unknown file");
            tradeoffs = strings(response.path("tradeoffs"));
        }
        return saved(root.resolve("design.json"), Map.of("impactedFiles", impacted, "tradeoffs", tradeoffs,
                "flow", "HTTP API -> validation -> H2 transactions; redirect -> best-effort click tracking"));
    }

    private Map<String, Object> proposal(WorkflowRun run, Path root) throws Exception {
        JsonNode plan = read(root.resolve("plan.json"));
        boolean needsPatch = StreamSupport.stream(plan.path("tasks").spliterator(), false)
                .anyMatch(task -> task.path("action").asText().equals("SOURCE_PATCH"));
        String path = POLICY_FILE;
        String find = "\"admin\"";
        String replace = "\"admin\", \"support\"";
        String test = aliasTest("support");
        if (run.useModel) {
            Map<String, String> source = new LinkedHashMap<>();
            for (String candidate : EDITABLE) source.put(candidate, Files.readString(checkout.resolve(candidate)));
            JsonNode response = model.ask("Produce a small real Java source edit and complete JUnit 5 regression test. "
                            + "Return JSON string fields path, find, replace, testSource, summary. find must be a unique exact substring "
                            + "of one allowed source file. Do not edit workflow policy or build files. Test source must have package "
                            + "com.ishee.shortener and class GeneratedChangeTest. No shell, network, reflection or filesystem access.",
                    Map.of("requirement", run.requirement, "allowedSource", source));
            path = response.path("path").asText();
            find = response.path("find").asText();
            replace = response.path("replace").asText();
            test = response.path("testSource").asText();
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        String diff = "";
        if (needsPatch) {
            if (!EDITABLE.contains(path)) throw new SecurityException("Source path is not allowlisted");
            if (find.isBlank() || find.length() > 3_000 || replace.length() > 5_000) throw new IllegalArgumentException("Invalid patch bounds");
            String original = Files.readString(checkout.resolve(path));
            if (original.split(java.util.regex.Pattern.quote(find), -1).length != 2) {
                throw new IllegalArgumentException("Patch anchor must occur exactly once");
            }
            checkGenerated(replace);
            checkGenerated(test);
            if (test.isBlank() || !test.contains("class GeneratedChangeTest")) throw new IllegalArgumentException("Missing JUnit regression test");
            patch.put("path", path);
            patch.put("find", find);
            patch.put("replace", replace);
            patch.put("originalSha", WorkflowStore.hash(original));
            patch.put("testSource", test);
            diff = diff(path, original, original.replace(find, replace)) + "\n--- /dev/null\n+++ b/" + REGRESSION_FILE + "\n"
                    + test.lines().map(line -> "+" + line).reduce("", (a, b) -> a + b + "\n");
        }
        saved(root.resolve("proposal.json"), patch);
        Files.writeString(root.resolve("proposed_change.diff"), diff);
        return Map.of("proposal", root.resolve("proposal.json").toString(), "diff", root.resolve("proposed_change.diff").toString(),
                "mode", run.useModel ? "model_proposed" : (needsPatch ? "bounded_patch" : "baseline"));
    }

    private String diff(String path, String before, String after) {
        List<String> oldLines = before.lines().toList();
        List<String> newLines = after.lines().toList();
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - suffix - 1).equals(newLines.get(newLines.size() - suffix - 1))) suffix++;
        int contextBefore = Math.min(prefix, 3);
        int contextAfter = Math.min(suffix, 3);
        int oldStart = prefix - contextBefore;
        int newStart = prefix - contextBefore;
        int oldEnd = oldLines.size() - suffix + contextAfter;
        int newEnd = newLines.size() - suffix + contextAfter;
        StringBuilder result = new StringBuilder("--- a/" + path + "\n+++ b/" + path + "\n");
        result.append("@@ -").append(oldStart + 1).append(',').append(oldEnd - oldStart)
                .append(" +").append(newStart + 1).append(',').append(newEnd - newStart).append(" @@\n");
        for (int i = oldStart; i < prefix; i++) result.append(' ').append(oldLines.get(i)).append('\n');
        for (int i = prefix; i < oldLines.size() - suffix; i++) result.append('-').append(oldLines.get(i)).append('\n');
        for (int i = prefix; i < newLines.size() - suffix; i++) result.append('+').append(newLines.get(i)).append('\n');
        for (int i = oldLines.size() - suffix; i < oldEnd; i++) result.append(' ').append(oldLines.get(i)).append('\n');
        return result.toString();
    }

    private void checkGenerated(String source) {
        if (source.length() > 15_000 || DANGEROUS.stream().anyMatch(source::contains)) {
            throw new SecurityException("Generated source failed policy validation");
        }
    }

    private String aliasTest(String alias) {
        return "package com.ishee.shortener;\n\nimport org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import com.ishee.shortener.api.AliasPolicy;\n\n"
                + "class GeneratedChangeTest {\n"
                + "    @Test void rejectsReservedAlias() {\n"
                + "        assertThrows(IllegalArgumentException.class, () -> AliasPolicy.validate(\"" + alias + "\"));\n"
                + "    }\n}\n";
    }

    private Map<String, Object> stage(Path root) throws IOException {
        Path candidate = root.resolve("candidate");
        if (Files.exists(candidate)) deleteTree(candidate);
        for (String path : List.of("pom.xml", "src/main/java", "src/main/resources")) {
            copy(checkout.resolve(path), candidate.resolve(path));
        }
        String test = "src/test/java/com/ishee/shortener/LinkApiTest.java";
        copy(checkout.resolve(test), candidate.resolve(test));
        return saved(root.resolve("stage.json"), Map.of("candidate", candidate.toString(), "mode", "isolated"));
    }

    private void copy(Path source, Path destination) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path child : paths.toList()) {
                    Path relative = source.relativize(child);
                    Path output = destination.resolve(relative);
                    if (Files.isDirectory(child)) Files.createDirectories(output);
                    else {
                        Files.createDirectories(output.getParent());
                        Files.copy(child, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } else {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, Object> baseline(Path root) throws IOException {
        if (!Files.exists(root.resolve("candidate/src/test/java/com/ishee/shortener/LinkApiTest.java"))) {
            throw new IllegalStateException("Baseline contract test missing");
        }
        return saved(root.resolve("baseline.json"), Map.of("status", "staged", "source", "URL shortener"));
    }

    private Map<String, Object> patch(Path root) throws IOException {
        JsonNode proposal = read(root.resolve("proposal.json"));
        String path = proposal.path("path").asText();
        if (!EDITABLE.contains(path)) throw new SecurityException("Patch path is not allowlisted");
        Path file = root.resolve("candidate").resolve(path).normalize();
        String original = Files.readString(file);
        String find = proposal.path("find").asText();
        String replace = proposal.path("replace").asText();
        if (!WorkflowStore.hash(original).equals(proposal.path("originalSha").asText())
                || original.split(java.util.regex.Pattern.quote(find), -1).length != 2) {
            throw new IllegalStateException("Source changed after the proposal was reviewed");
        }
        checkGenerated(replace);
        Files.writeString(file, original.replace(find, replace));
        return Map.of("file", file.toString());
    }

    private Map<String, Object> regression(Path root) throws IOException {
        String source = read(root.resolve("proposal.json")).path("testSource").asText();
        checkGenerated(source);
        if (!source.contains("class GeneratedChangeTest")) throw new IllegalArgumentException("Missing named regression test class");
        Path file = root.resolve("candidate").resolve(REGRESSION_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return Map.of("file", file.toString());
    }

    private Map<String, Object> privacy(Path root) throws IOException {
        String source = """
                package com.ishee.shortener;
                import java.util.Set;
                import java.util.stream.Collectors;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.jdbc.core.JdbcTemplate;
                import static org.junit.jupiter.api.Assertions.*;
                @SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:privacy;DB_CLOSE_DELAY=-1")
                class PrivacyContractTest {
                    @Autowired JdbcTemplate jdbc;
                    @Test void analyticsDoesNotKeepPersonalIdentifiers() {
                        Set<String> columns = jdbc.queryForList(
                            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='CLICKS'", String.class)
                            .stream().map(String::toLowerCase).collect(Collectors.toSet());
                        assertFalse(columns.contains("ip_address"));
                        assertFalse(columns.contains("user_agent"));
                    }
                }
                """;
        Path file = root.resolve("candidate").resolve(PRIVACY_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return Map.of("file", file.toString());
    }

    private Map<String, Object> security(Path root) throws IOException {
        Path candidate = root.resolve("candidate");
        for (String path : EDITABLE) {
            String content = Files.readString(candidate.resolve(path));
            if (DANGEROUS.stream().anyMatch(content::contains)) throw new SecurityException("Candidate source failed safety policy");
        }
        for (String test : List.of(REGRESSION_FILE, PRIVACY_FILE)) {
            Path file = candidate.resolve(test);
            if (Files.exists(file)) checkGenerated(Files.readString(file));
        }
        return saved(root.resolve("security.json"), Map.of("status", "passed", "scope", "allowed Java sources and generated tests",
                "limitations", List.of("Static checks are not a container sandbox", "No authenticated reviewer identities")));
    }

    private Map<String, Object> test(Path root) throws Exception {
        Path candidate = root.resolve("candidate");
        List<String> command = List.of("mvn", "-q", "-Dtest=LinkApiTest,GeneratedChangeTest,PrivacyContractTest",
                "-Dsurefire.failIfNoSpecifiedTests=false", "test");
        Path captured = root.resolve("maven_output.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(candidate.toFile()).redirectErrorStream(true)
                .redirectOutput(captured.toFile());
        builder.environment().remove("MODEL_API_KEY");
        builder.environment().put("DB_URL", "jdbc:h2:mem:staged;DB_CLOSE_DELAY=-1");
        Process process = builder.start();
        boolean finished = process.waitFor(150, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        String output = Files.exists(captured) ? Files.readString(captured) : "No Maven output";
        int exit = finished ? process.exitValue() : 124;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", String.join(" ", command));
        result.put("exitCode", exit);
        result.put("passed", exit == 0);
        result.put("output", output.substring(Math.max(0, output.length() - 8_000)));
        Path path = root.resolve("test_report.json");
        saved(path, result);
        if (exit != 0) {
            Map<String, Object> diagnostic = Map.of("note", "Tests failed; compile diagnostic only", "testReport", path.toString());
            saved(root.resolve("fallback.json"), diagnostic);
            throw new IllegalStateException("Candidate tests failed; see " + path);
        }
        return Map.of("file", path.toString(), "passed", true);
    }

    private Map<String, Object> docs(WorkflowRun run, Path root) throws Exception {
        JsonNode plan = read(root.resolve("plan.json"));
        JsonNode requirements = read(root.resolve("requirement.json"));
        JsonNode design = read(root.resolve("design.json"));
        String text = "# Engineering summary\n\nRequirement (v" + run.version + "): " + run.requirement
                + "\n\n## Plan and rationale\n" + plan.path("tasks").toPrettyString()
                + "\n\n## Impact and trade-offs\n" + design.toPrettyString()
                + "\n\n## Validation\nCandidate source review, safety checks and isolated Maven tests are required before release approval."
                + "\n\n## Assumptions\n" + requirements.path("assumptions").toPrettyString()
                + "\n\n## Risks and limitations\nStaged rollback only; local database, static checks, unauthenticated CLI-level approvals;"
                + " no real deployment and no hardened execution sandbox.\n";
        return report(root.resolve("engineering_summary.md"), text);
    }

    private Map<String, Object> review(WorkflowRun run, Path root) throws Exception {
        JsonNode tests = read(root.resolve("test_report.json"));
        if (!tests.path("passed").asBoolean(false) || !read(root.resolve("security.json")).path("status").asText().equals("passed")) {
            throw new IllegalStateException("Security and tests must pass before review");
        }
        for (String file : List.of("plan.json", "design.json", "proposal.json", "proposed_change.diff", "engineering_summary.md")) {
            if (!Files.exists(root.resolve(file))) throw new IllegalStateException("Missing review evidence: " + file);
        }
        return saved(root.resolve("review.json"), Map.of("status", "ready_for_human_review",
                "requirementVersion", run.version, "tests", "passed", "security", "passed", "approvalRequired", true));
    }

    private Map<String, Object> release(WorkflowRun run, Path root) throws Exception {
        WorkflowRun.Approval approval = run.approvals.get("release_approval");
        if (approval == null || !approval.decision.equals("approved") || approval.version != run.version) {
            throw new SecurityException("Current human release approval required");
        }
        return saved(root.resolve("release_manifest.json"), Map.of("ready", true, "mode", "dry_run_only",
                "version", run.version, "approvedBy", approval.actor, "candidate", root.resolve("candidate").toString(),
                "note", "Code was staged and tested, not merged or deployed"));
    }

    public void rollback(WorkflowRun run) {
        Path candidate = artifacts.resolve(run.id).resolve("candidate");
        try {
            if (Files.exists(candidate)) deleteTree(candidate);
        } catch (IOException error) {
            throw new IllegalStateException("Could not remove staged source", error);
        }
    }

    private void deleteTree(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted((a, b) -> b.compareTo(a)).toList()) Files.delete(path);
        }
    }
}
