package com.ishee.shortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ishee.shortener.workflow.ModelGateway;
import com.ishee.shortener.workflow.WorkflowEngine;
import com.ishee.shortener.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:model_test;DB_CLOSE_DELAY=-1")
class ModelWorkflowTest {
    @Autowired WorkflowEngine engine;
    @Autowired ObjectMapper json;
    @MockitoBean ModelGateway model;

    @Test void modelGeneratedPlanCreatesRealExecutionNodes() throws Exception {
        String regression = "package com.ishee.shortener;\nimport org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import com.ishee.shortener.api.AliasPolicy;\n"
                + "class GeneratedChangeTest { @Test void rejectsHelp() { "
                + "assertThrows(IllegalArgumentException.class, () -> AliasPolicy.validate(\"help\")); } }\n";
        when(model.ask(anyString(), any())).thenReturn(
                json.readTree("{\"acceptanceCriteria\":[\"Reserve help alias\"],\"assumptions\":[\"Existing API behavior unchanged\"],\"ambiguities\":[]}"),
                json.readTree("{\"tasks\":[{\"id\":\"patch_validation\",\"action\":\"SOURCE_PATCH\",\"dependsOn\":[]},{\"id\":\"add_test\",\"action\":\"ADD_REGRESSION_TEST\",\"dependsOn\":[\"patch_validation\"]}]}"),
                json.readTree("{\"impactedFiles\":[\"src/main/java/com/ishee/shortener/api/AliasPolicy.java\"],\"tradeoffs\":[\"Alias can no longer be assigned\"]}"),
                json.readTree(json.writeValueAsString(java.util.Map.of(
                        "path", "src/main/java/com/ishee/shortener/api/AliasPolicy.java",
                        "find", "\"admin\"", "replace", "\"admin\", \"help\"",
                        "summary", "Reserve help alias and validate it",
                        "testSource", regression))));

        WorkflowRun run = engine.create("greenfield", "Reserve the help alias and add a regression test", true);
        run = engine.advance(run.id, false);
        assertEquals("waiting_approval", run.status);
        assertTrue(run.steps.containsKey("task_patch_validation"));
        assertTrue(run.steps.containsKey("task_add_test"));
        assertTrue(run.steps.get("task_add_test").deps.contains("task_patch_validation"));
        assertEquals("blocked", run.steps.get("change_approval").status);
        assertEquals("pending", run.steps.get("task_patch_validation").status);
        run = engine.approve(run.id, "change_approval", "Ishee", "approved");
        run = engine.advance(run.id, false);
        assertEquals("waiting_approval", run.status);
        assertEquals("completed", run.steps.get("test").status);
        assertEquals("blocked", run.steps.get("release_approval").status);
        verify(model, times(4)).ask(anyString(), any());
    }
}
