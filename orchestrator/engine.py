"""Run workflow tasks in dependency order and record their outcomes."""
from __future__ import annotations

import shutil
import hashlib
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from .agents import ARTIFACTS, execute
from .graph import create_graph
from .store import RunStore, utc_now


def _elapsed_ms(start):
    return round((datetime.now(timezone.utc) - datetime.fromisoformat(start)).total_seconds() * 1000, 1)


class WorkflowEngine:
    def __init__(self, store=None):
        self.store = store or RunStore()

    def create(self, scenario: str, requirement: str, use_llm: bool = False):
        if not requirement.strip():
            raise ValueError("Requirement must not be blank")
        graph = create_graph(scenario, use_llm)
        run = {
            "id": uuid.uuid4().hex[:12], "scenario": scenario, "requirement": requirement,
            "version": 1, "graph": graph, "use_llm": use_llm,
            "tasks": {name: {"status": "pending", "attempts": 0} for name in graph},
            "approvals": {}, "status": "pending", "replans": 0, "created_at": utc_now(),
            "elapsed_ms": None, "recovery_ms": None}
        self.store.put(run)
        self.store.event(run["id"], "created", {"scenario": scenario, "version": 1, "llm_advisory": use_llm})
        return run

    def _rollback(self, run, reason):
        # Only remove the staged copy; leave the working source unchanged.
        candidate = ARTIFACTS / run["id"] / "candidate"
        if candidate.exists():
            shutil.rmtree(candidate)
        self.store.event(run["id"], "rollback", {"reason": reason, "scope": "candidate-only"})

    def approve(self, run_id: str, gate: str, actor: str, decision="approved"):
        run = self.store.get(run_id)
        if not actor.strip() or actor.lower() in {"agent", "automation", "system"}:
            raise ValueError("A named human reviewer is required")
        if decision not in {"approved", "rejected"}:
            raise ValueError("decision must be approved or rejected")
        node = run["graph"].get(gate)
        if not node or node["kind"] != "approval" or run["tasks"][gate]["status"] != "blocked":
            raise ValueError(f"Gate {gate} is not awaiting approval")
        run["approvals"][gate] = {"actor": actor, "decision": decision, "at": utc_now(), "version": run["version"]}
        self.store.event(run_id, "human_decision", {"gate": gate, "actor": actor, "decision": decision, "version": run["version"]})
        if decision == "rejected":
            run["tasks"][gate]["status"] = "rejected"
            self._rollback(run, f"human_rejected_{gate}")
            run["status"] = "stopped"
            run["elapsed_ms"] = _elapsed_ms(run["created_at"])
        else:
            run["tasks"][gate]["status"] = "completed"
            run["status"] = "pending"
        self.store.put(run)
        return run

    def revise(self, run_id, requirement):
        run = self.store.get(run_id)
        if not requirement.strip():
            raise ValueError("Requirement must not be blank")
        if run["status"] in {"completed", "stopped", "failed"}:
            raise ValueError("Terminal workflow cannot be revised; create a new run")
        old = run["version"]
        run["version"] += 1
        run["requirement"] = requirement
        run["tasks"] = {name: {"status": "pending", "attempts": 0} for name in run["graph"]}
        run["approvals"] = {}  # Approvals never carry forward to changed requirements.
        run["status"] = "pending"
        self._rollback(run, "upstream_requirement_changed")
        self.store.event(run_id, "replan", {"old_version": old, "new_version": run["version"],
                                            "effect": "invalidated all tasks and approvals"})
        self.store.put(run)
        return run

    @staticmethod
    def _digest(path: Path) -> str:
        """Hash saved outputs so changed files can trigger another review."""
        hasher = hashlib.sha256()
        paths = sorted(p for p in path.rglob("*") if p.is_file()) if path.is_dir() else [path]
        for file in paths:
            if any(part in {"__pycache__", ".pytest_cache", ".git"} for part in file.parts) or \
               file.suffix in {".pyc", ".sqlite", ".db"}:
                continue
            hasher.update(str(file.relative_to(path) if path.is_dir() else file.name).encode())
            hasher.update(file.read_bytes())
        return hasher.hexdigest()

    def _fingerprints(self, result, run_id):
        allowed_root = (ARTIFACTS / run_id).resolve()
        found = {}
        for value in result.values():
            if not isinstance(value, str):
                continue
            path = Path(value)
            if path.is_absolute() and path.exists() and path.resolve().is_relative_to(allowed_root):
                found[value] = self._digest(path)
        return found

    def _invalidate(self, run, changed, *, reason):
        """Reset dependent steps and any approvals that are no longer valid."""
        if changed not in run["graph"]:
            raise ValueError(f"Unknown graph stage {changed}")
        affected = {changed}
        changed_count = -1
        while changed_count != len(affected):
            changed_count = len(affected)
            affected.update(name for name, node in run["graph"].items()
                            if any(dep in affected for dep in node["deps"]))
        if "implement" in affected:
            self._rollback(run, f"replan_{changed}")
        for task in affected:
            run["tasks"][task] = {"status": "pending", "attempts": 0}
            run["approvals"].pop(task, None)
        run["status"] = "pending"
        run["replans"] = run.get("replans", 0) + 1
        self.store.event(run["id"], "selective_replan", {
            "changed_stage": changed, "affected_stages": sorted(affected),
            "preserved_stages": sorted(set(run["graph"]) - affected), "reason": reason,
            "requirement_version": run["version"]})
        self.store.put(run)
        return run

    def replan(self, run_id, changed_stage):
        run = self.store.get(run_id)
        if run["status"] in {"completed", "stopped", "failed"}:
            raise ValueError("Terminal workflow cannot be re-planned")
        return self._invalidate(run, changed_stage, reason="reviewer_marked_output_changed")

    def _check_output_drift(self, run):
        for name, state in run["tasks"].items():
            if state["status"] != "completed" or not state.get("fingerprints"):
                continue
            for path, expected in state["fingerprints"].items():
                candidate = Path(path)
                if not candidate.exists() or self._digest(candidate) != expected:
                    return self._invalidate(run, name, reason="upstream_artifact_changed")
        return run

    def stop(self, run_id, reason="operator safe-stop"):
        run = self.store.get(run_id)
        if run["status"] in {"completed", "stopped", "failed"}:
            raise ValueError("Run already terminal")
        self._rollback(run, reason)
        run["status"] = "stopped"
        run["elapsed_ms"] = _elapsed_ms(run["created_at"])
        self.store.put(run)
        self.store.event(run_id, "safe_stop", {"reason": reason})
        return run

    def advance(self, run_id, fault_once: str | None = None):
        run = self.store.get(run_id)
        if run["status"] in {"completed", "failed", "stopped"}:
            return run
        run = self._check_output_drift(run)
        run["status"] = "running"
        # Record each batch before running its ready tasks.
        while True:
            ready = [name for name, spec in run["graph"].items()
                     if run["tasks"][name]["status"] == "pending"
                     and all(run["tasks"][d]["status"] == "completed" for d in spec["deps"])]
            if not ready:
                break
            executable = []
            for name in ready:
                if run["graph"][name]["kind"] == "approval":
                    run["tasks"][name]["status"] = "blocked"
                    self.store.event(run_id, "approval_requested", {"gate": name, "version": run["version"]})
                else:
                    executable.append(name)
            if executable:
                # Run independent ready tasks together.
                self.store.event(run_id, "parallel_batch", {"ready": executable})
                with ThreadPoolExecutor(max_workers=3) as pool:
                    results = list(pool.map(lambda task: self._execute_with_retry(task, run, fault_once), executable))
                for name, outcome in zip(executable, results):
                    state = run["tasks"][name]
                    state["attempts"] = outcome["attempts"]
                    state["duration_ms"] = outcome["duration_ms"]
                    if outcome["error"]:
                        state["status"] = "failed"
                        state["error"] = outcome["error"]
                        self.store.event(run_id, "task_failed", {"task": name, "error": outcome["error"]})
                        self._rollback(run, f"task_failure_{name}")
                        run["status"] = "failed"
                        run["elapsed_ms"] = _elapsed_ms(run["created_at"])
                        self.store.put(run)
                        return run
                    state["status"] = "completed"
                    state["result"] = outcome["result"]
                    state["fingerprints"] = self._fingerprints(outcome["result"], run_id)
                    self.store.event(run_id, "task_completed", {"task": name, "attempts": outcome["attempts"],
                                                                 "duration_ms": outcome["duration_ms"],
                                                                 "version": run["version"]})
                    if outcome["attempts"] > 1:
                        self.store.event(run_id, "retry", {"task": name, "count": outcome["attempts"] - 1})
                        run["recovery_ms"] = outcome["duration_ms"]
            self.store.put(run)
        if all(t["status"] == "completed" for t in run["tasks"].values()):
            run["status"] = "completed"
            run["elapsed_ms"] = _elapsed_ms(run["created_at"])
            self.store.event(run_id, "run_completed", {"version": run["version"], "elapsed_ms": run["elapsed_ms"]})
        elif any(t["status"] == "blocked" for t in run["tasks"].values()):
            run["status"] = "waiting_approval"
        else:
            run["status"] = "failed"  # Deadlock or unmet graph condition: safe stop.
            run["elapsed_ms"] = _elapsed_ms(run["created_at"])
            self._rollback(run, "workflow_deadlock")
        self.store.put(run)
        return run

    def _execute_with_retry(self, name, run, fault_once):
        retry_budget = run["graph"][name]["retries"]
        start = time.monotonic()
        for attempt in range(1, retry_budget + 2):
            try:
                if name == fault_once and attempt == 1:
                    raise RuntimeError("Injected transient test runner failure (demo)")
                result = execute(name, run)
                return {"attempts": attempt, "result": result, "error": None,
                        "duration_ms": round((time.monotonic() - start) * 1000, 1)}
            except Exception as exc:
                if attempt > retry_budget:
                    return {"attempts": attempt, "result": {}, "error": f"{type(exc).__name__}: {exc}",
                            "duration_ms": round((time.monotonic() - start) * 1000, 1)}
                time.sleep(0.05 * attempt)  # Bounded backoff.
        raise AssertionError("Unreachable")
