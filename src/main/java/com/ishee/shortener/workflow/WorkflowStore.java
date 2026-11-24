package com.ishee.shortener.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkflowStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(WorkflowRun run) {
        String data = stringify(run);
        jdbc.update("MERGE INTO workflow_runs(id,state) KEY(id) VALUES (?, ?)", run.id, data);
    }

    public WorkflowRun get(String id) {
        List<String> rows = jdbc.query("SELECT state FROM workflow_runs WHERE id=?", (result, row) -> result.getString(1), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown workflow: " + id);
        try {
            return json.readValue(rows.getFirst(), WorkflowRun.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored workflow could not be read", error);
        }
    }

    public List<WorkflowRun> all() {
        List<String> rows = jdbc.query("SELECT state FROM workflow_runs", (result, row) -> result.getString(1));
        return rows.stream().map(value -> {
            try {
                return json.readValue(value, WorkflowRun.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Stored workflow could not be read", error);
            }
        }).toList();
    }

    public void event(String runId, String type, Map<String, ?> detail) {
        String at = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String body = stringify(detail);
        List<String> hashes = jdbc.query("SELECT event_hash FROM workflow_events WHERE run_id=? ORDER BY id DESC LIMIT 1",
                (result, row) -> result.getString(1), runId);
        String previous = hashes.isEmpty() ? "GENESIS" : hashes.getFirst();
        String digest = hash(runId + "|" + at + "|" + type + "|" + body + "|" + previous);
        jdbc.update("INSERT INTO workflow_events(run_id,occurred_at,event_type,detail,prev_hash,event_hash) VALUES (?,?,?,?,?,?)",
                runId, at, type, body, previous, digest);
    }

    public List<Map<String, Object>> events(String id) {
        return jdbc.query("SELECT occurred_at,event_type,detail,prev_hash,event_hash FROM workflow_events WHERE run_id=? ORDER BY id",
                (result, row) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("at", result.getString(1));
                    event.put("kind", result.getString(2));
                    try {
                        event.put("detail", json.readValue(result.getString(3), Map.class));
                    } catch (JsonProcessingException error) {
                        throw new IllegalStateException("Invalid audit event", error);
                    }
                    event.put("prev_hash", result.getString(4));
                    event.put("event_hash", result.getString(5));
                    return event;
                }, id);
    }

    public boolean verify(String id) {
        String previous = "GENESIS";
        for (Map<String, Object> event : events(id)) {
            String body = stringify(event.get("detail"));
            String digest = hash(id + "|" + event.get("at") + "|" + event.get("kind") + "|" + body + "|" + previous);
            if (!previous.equals(event.get("prev_hash")) || !digest.equals(event.get("event_hash"))) return false;
            previous = digest;
        }
        return true;
    }

    public Map<String, Object> metrics() {
        List<WorkflowRun> runs = all();
        List<WorkflowRun> terminal = runs.stream().filter(r -> List.of("completed", "failed", "stopped").contains(r.status)).toList();
        List<String> types = jdbc.query("SELECT event_type FROM workflow_events", (result, row) -> result.getString(1));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("runs", runs.size());
        metrics.put("terminal_runs", terminal.size());
        metrics.put("success_rate", terminal.isEmpty() ? null : (double) terminal.stream().filter(r -> r.status.equals("completed")).count() / terminal.size());
        for (String event : List.of("retry", "rollback", "replan")) {
            metrics.put(event + "_frequency", types.stream().filter(event::equals).count());
        }
        metrics.put("average_end_to_end_latency_ms", terminal.stream().filter(r -> r.elapsedMs != null)
                .mapToLong(r -> r.elapsedMs).average().orElse(Double.NaN));
        metrics.put("mttr_proxy_ms", runs.stream().filter(r -> r.recoveryMs != null)
                .mapToLong(r -> r.recoveryMs).average().orElse(Double.NaN));
        return metrics;
    }

    public String stringify(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize workflow data", error);
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
