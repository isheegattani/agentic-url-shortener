package com.ishee.shortener.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorkflowRun {
    public String id;
    public String scenario;
    public String requirement;
    public int version = 1;
    public boolean useModel;
    public String status = "pending";
    public String createdAt;
    public Long elapsedMs;
    public Long recoveryMs;
    public int replans;
    public Map<String, Step> steps = new LinkedHashMap<>();
    public Map<String, Approval> approvals = new LinkedHashMap<>();

    public static class Step {
        public String id;
        public String kind;
        public String action;
        public List<String> deps = new ArrayList<>();
        public String status = "pending";
        public int attempts;
        public long durationMs;
        public Map<String, Object> result = new LinkedHashMap<>();
        public Map<String, String> fingerprints = new LinkedHashMap<>();
        public String error;

        public Step() {}

        public Step(String id, String kind, String action, String... dependencies) {
            this.id = id;
            this.kind = kind;
            this.action = action;
            this.deps = List.of(dependencies);
        }
    }

    public static class Approval {
        public String actor;
        public String decision;
        public String at;
        public int version;

        public Approval() {}

        public Approval(String actor, String decision, String at, int version) {
            this.actor = actor;
            this.decision = decision;
            this.at = at;
            this.version = version;
        }
    }
}
