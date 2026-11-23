import json
from pathlib import Path

import pytest

from orchestrator.engine import WorkflowEngine
from orchestrator.store import RunStore


@pytest.fixture
def engine(tmp_path, monkeypatch):
    import orchestrator.agents as agents
    import orchestrator.engine as engine_module
    artifacts = tmp_path / "artifacts"
    monkeypatch.setattr(agents, "ARTIFACTS", artifacts)
    monkeypatch.setattr(engine_module, "ARTIFACTS", artifacts)
    return WorkflowEngine(RunStore(str(tmp_path / "runs.sqlite")))


def test_greenfield_gate_then_complete(engine):
    run = engine.create("greenfield", "Build a URL shortener with click analytics")
    run = engine.advance(run["id"])
    assert run["status"] == "waiting_approval"
    assert run["tasks"]["review"]["status"] == "completed"
    assert run["tasks"]["release_approval"]["status"] == "blocked"
    run = engine.approve(run["id"], "release_approval", "tech-lead")
    run = engine.advance(run["id"])
    assert run["status"] == "completed"
    assert engine.store.verify_audit(run["id"])
    assert json.loads(Path(run["tasks"]["release"]["result"]["release_manifest"]).read_text())["mode"] == "dry_run_only"


def test_brownfield_requires_change_and_release_review(engine):
    run = engine.create("brownfield", "Reserve the support alias")
    run = engine.advance(run["id"])
    assert run["tasks"]["change_approval"]["status"] == "blocked"
    assert run["tasks"]["implement"]["status"] == "pending"
    engine.approve(run["id"], "change_approval", "senior-engineer")
    run = engine.advance(run["id"])
    assert run["tasks"]["test"]["status"] == "completed"
    assert run["tasks"]["release_approval"]["status"] == "blocked"
    engine.approve(run["id"], "release_approval", "senior-engineer")
    assert engine.advance(run["id"])["status"] == "completed"


def test_ambiguous_pauses_before_planning(engine):
    run = engine.create("ambiguous", "Make analytics private and efficient")
    run = engine.advance(run["id"])
    assert run["tasks"]["requirements_approval"]["status"] == "blocked"
    assert run["tasks"]["plan"]["status"] == "pending"
    engine.approve(run["id"], "requirements_approval", "product-owner")
    run = engine.advance(run["id"])
    assert run["tasks"]["test"]["status"] == "completed"


def test_replan_revokes_stale_approvals(engine):
    run = engine.create("ambiguous", "Improve security")
    engine.advance(run["id"])
    engine.approve(run["id"], "requirements_approval", "product-owner")
    revised = engine.revise(run["id"], "Improve security and privacy without storing IP")
    assert revised["version"] == 2
    assert revised["approvals"] == {}
    assert revised["tasks"]["requirements_approval"]["status"] == "pending"
    assert any(e["kind"] == "replan" for e in engine.store.events(run["id"]))


def test_rejection_rolls_back(engine, tmp_path):
    run = engine.create("greenfield", "Build URL shortener")
    run = engine.advance(run["id"])
    candidate = tmp_path / "artifacts" / run["id"] / "candidate"
    assert candidate.exists()
    run = engine.approve(run["id"], "release_approval", "reviewer", "rejected")
    assert run["status"] == "stopped"
    assert not candidate.exists()
    assert any(e["kind"] == "rollback" for e in engine.store.events(run["id"]))


def test_retry_is_bounded_and_observable(engine):
    run = engine.create("greenfield", "Build URL shortener")
    run = engine.advance(run["id"], fault_once="test")
    assert run["tasks"]["test"]["attempts"] == 2
    assert any(e["kind"] == "retry" for e in engine.store.events(run["id"]))
    assert engine.store.metrics()["retry_frequency"] == 1


def test_policy_denies_bypassing_audit(engine):
    run = engine.create("greenfield", "Build service and disable audit logs")
    run = engine.advance(run["id"])
    assert run["status"] == "failed"
    assert run["tasks"]["intake"]["status"] == "failed"


def test_agent_cannot_self_approve(engine):
    run = engine.create("ambiguous", "Make it better")
    engine.advance(run["id"])
    with pytest.raises(ValueError):
        engine.approve(run["id"], "requirements_approval", "agent")


def test_metrics_count_success(engine):
    run = engine.create("greenfield", "Build shortener")
    engine.advance(run["id"])
    engine.approve(run["id"], "release_approval", "reviewer")
    engine.advance(run["id"])
    metrics = engine.store.metrics()
    assert metrics["success_rate"] == 1.0
    assert metrics["average_end_to_end_latency_ms"] is not None
