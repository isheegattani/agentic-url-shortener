package com.ishee.shortener;

import com.ishee.shortener.workflow.WorkflowEngine;
import com.ishee.shortener.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:workflow_test;DB_CLOSE_DELAY=-1")
class WorkflowTest {
    @Autowired WorkflowEngine engine;

    @Test void greenfieldWaitsForHumanReleaseApproval() throws Exception {
        WorkflowRun run = engine.create("greenfield", "Build URL shortener with click analytics", false);
        run = engine.advance(run.id, false);
        assertEquals("waiting_approval", run.status);
        assertEquals("completed", run.steps.get("test").status);
        assertEquals("blocked", run.steps.get("release_approval").status);
        assertTrue(engine.auditValid(run.id));
        run = engine.approve(run.id, "release_approval", "Ishee", "approved");
        run = engine.advance(run.id, false);
        assertEquals("completed", run.status);
        assertTrue(Files.exists(Path.of("artifacts", run.id, "release_manifest.json")));
    }

    @Test void brownfieldRequiresReviewBeforeChangingCode() throws Exception {
        WorkflowRun run = engine.create("brownfield", "Reserve the support alias", false);
        run = engine.advance(run.id, false);
        assertEquals("blocked", run.steps.get("change_approval").status);
        assertEquals("pending", run.steps.get("task_update_alias_policy").status);
        assertFalse(Files.exists(Path.of("artifacts", run.id, "candidate")));
        run = engine.approve(run.id, "change_approval", "Ishee", "approved");
        run = engine.advance(run.id, false);
        assertEquals("waiting_approval", run.status);
        assertEquals("completed", run.steps.get("test").status);
        assertTrue(Files.readString(Path.of("artifacts", run.id, "candidate",
                "src/main/java/com/ishee/shortener/api/AliasPolicy.java")).contains("\"support\""));
        run = engine.approve(run.id, "release_approval", "Ishee", "approved");
        assertEquals("completed", engine.advance(run.id, false).status);
    }

    @Test void ambiguousInputBlocksPlanningAndRevisesVersion() throws Exception {
        WorkflowRun run = engine.create("ambiguous", "Make analytics private", false);
        run = engine.advance(run.id, false);
        assertEquals("blocked", run.steps.get("requirements_approval").status);
        assertEquals("pending", run.steps.get("plan").status);
        run = engine.revise(run.id, "Keep aggregate counts without storing IP addresses or user agents");
        assertEquals(2, run.version);
        assertTrue(run.approvals.isEmpty());
        run = engine.advance(run.id, false);
        run = engine.approve(run.id, "requirements_approval", "Ishee", "approved");
        run = engine.advance(run.id, false);
        assertEquals("completed", run.steps.get("test").status);
        assertEquals("blocked", run.steps.get("release_approval").status);
        assertTrue(engine.auditValid(run.id));
    }

    @Test void rejectedApprovalStopsAndRollsBack() {
        WorkflowRun run = engine.create("brownfield", "Reserve the support alias", false);
        run = engine.advance(run.id, false);
        run = engine.approve(run.id, "change_approval", "Ishee", "rejected");
        assertEquals("stopped", run.status);
        assertFalse(Files.exists(Path.of("artifacts", run.id, "candidate")));
    }

    @Test void retryAndSelectiveReplanAreRecorded() {
        WorkflowRun run = engine.create("greenfield", "Test URL shortening", false);
        run = engine.advance(run.id, true);
        assertEquals(2, run.steps.get("test").attempts);
        assertNotNull(run.recoveryMs);
        assertEquals("completed", run.steps.get("docs").status);
        run = engine.replan(run.id, "security");
        assertEquals("completed", run.steps.get("docs").status);
        assertEquals("pending", run.steps.get("test").status);
        assertEquals("pending", run.steps.get("release_approval").status);
        run = engine.advance(run.id, false);
        assertEquals("blocked", run.steps.get("release_approval").status);
    }

    @Test void cannotApproveBeforeGateIsReady() {
        WorkflowRun run = engine.create("ambiguous", "Improve privacy", false);
        assertThrows(IllegalArgumentException.class, () -> engine.approve(run.id, "release_approval", "Ishee", "approved"));
    }
}
