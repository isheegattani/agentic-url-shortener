"""End-to-end model and governance evidence without external accounts or secrets."""
import json
from pathlib import Path

import pytest

from orchestrator.engine import WorkflowEngine
from orchestrator.model import ModelError
from orchestrator.patching import ProposalRejected, check_python, inspect_proposal
from orchestrator.store import RunStore


@pytest.fixture
def engine(tmp_path, monkeypatch):
    import orchestrator.agents as agents
    import orchestrator.engine as engine_module
    artifacts = tmp_path / "artifacts"
    monkeypatch.setattr(agents, "ARTIFACTS", artifacts)
    monkeypatch.setattr(engine_module, "ARTIFACTS", artifacts)
    return WorkflowEngine(RunStore(str(tmp_path / "runs.sqlite")))


def scripted_model(stage, instructions, context):
    if stage == "intake":
        return {"acceptance_criteria": ["Prevent the help alias from being shortened"],
                "assumptions": ["Existing API responses must not change"], "ambiguities": []}
    if stage == "plan":
        return {"tasks": [
            {"id": "inspect", "action": "Inspect alias validation", "depends_on": []},
            {"id": "patch", "action": "Reserve the alias", "depends_on": ["inspect"]},
            {"id": "verify", "action": "Test the change", "depends_on": ["patch"]},
        ]}
    if stage == "design":
        return {"impacted_modules": ["url_shortener/validation.py"],
                "tradeoffs": ["Reserved aliases can no longer be used as custom codes"]}
    if stage == "proposal":
        return {"summary": "Reserve a help alias in short-link validation",
                "edits": [{"path": "url_shortener/validation.py", "find": '"admin"}',
                           "replace": '"admin", "help"}'}],
                "test_source": "import pytest\nfrom url_shortener.validation import validate_alias\n\n"
                               "def test_generated_alias_rejected():\n"
                               "    with pytest.raises(ValueError, match='reserved'):\n"
                               "        validate_alias('help')\n"}
    raise AssertionError("unexpected model stage " + stage)


def test_model_proposal_requires_review_before_execution(engine, monkeypatch):
    import orchestrator.agents as agents
    import orchestrator.patching as patching
    monkeypatch.setattr(agents, "ask_model", scripted_model)
    monkeypatch.setattr(patching, "ask_model", scripted_model)
    original = (agents.ROOT / "url_shortener" / "validation.py").read_text()
    run = engine.create("greenfield", "Reserve the help alias", use_llm=True)
    run = engine.advance(run["id"])
    assert run["status"] == "waiting_approval"
    assert run["tasks"]["change_approval"]["status"] == "blocked"
    assert run["tasks"]["implement"]["status"] == "pending"
    assert run["tasks"]["proposal"]["status"] == "completed"
    diff = Path(run["tasks"]["proposal"]["result"]["review_diff"]).read_text()
    assert '+RESERVED_CODES = {"api", "docs"' in diff
    assert '"help"}' in diff
    assert not (engine.store.path.parent / "artifacts").exists() or \
           not (engine.store.path.parent / "artifacts" / run["id"] / "candidate").exists()
    run = engine.approve(run["id"], "change_approval", "Ishee")
    run = engine.advance(run["id"])
    assert run["status"] == "waiting_approval"
    assert run["tasks"]["security"]["status"] == "completed"
    assert run["tasks"]["test"]["status"] == "completed"
    assert (agents.ROOT / "url_shortener" / "validation.py").read_text() == original
    summary = Path(run["tasks"]["docs"]["result"]["engineering_summary"])
    assert "Risks and validation" in summary.read_text()
    engine.approve(run["id"], "release_approval", "Ishee")
    assert engine.advance(run["id"])["status"] == "completed"
    assert engine.store.verify_audit(run["id"])


def test_rejected_model_proposal_cannot_change_source(engine, monkeypatch):
    import orchestrator.agents as agents
    import orchestrator.patching as patching
    monkeypatch.setattr(agents, "ask_model", scripted_model)
    monkeypatch.setattr(patching, "ask_model", scripted_model)
    run = engine.create("greenfield", "Reserve the help alias", use_llm=True)
    run = engine.advance(run["id"])
    run = engine.approve(run["id"], "change_approval", "Ishee", "rejected")
    assert run["status"] == "stopped"
    assert run["tasks"]["implement"]["status"] == "pending"
    assert any(event["kind"] == "rollback" for event in engine.store.events(run["id"]))


def test_untrusted_proposal_path_traversal_rejected():
    from orchestrator.agents import ROOT
    with pytest.raises(ProposalRejected):
        inspect_proposal(ROOT, {"edits": [{"path": "../orchestrator/engine.py",
                                           "find": "policy", "replace": "disabled"}]})


def test_dangerous_generated_code_is_rejected():
    with pytest.raises(ProposalRejected):
        check_python("import subprocess\nsubprocess.run(['whoami'])\n")
    with pytest.raises(ProposalRejected):
        check_python("eval('1+1')\n")
    with pytest.raises(ProposalRejected):
        check_python("from pathlib import Path\nPath('/tmp/a').write_text('bad')\n")


def test_changed_upstream_artifact_invalidates_stale_release_review(engine):
    run = engine.create("greenfield", "Build URL shortener")
    run = engine.advance(run["id"])
    assert run["tasks"]["release_approval"]["status"] == "blocked"
    artifact = Path(run["tasks"]["plan"]["result"]["plan"])
    artifact.write_text(artifact.read_text() + "\nchanged upstream\n")
    run = engine.advance(run["id"])
    assert run["replans"] == 1
    assert run["tasks"]["release_approval"]["status"] == "blocked"
    assert any(event["kind"] == "selective_replan" and
               event["detail"]["changed_stage"] == "plan" for event in engine.store.events(run["id"]))
    assert engine.store.verify_audit(run["id"])


def test_selective_replan_preserves_independent_docs(engine):
    run = engine.create("greenfield", "Build URL shortener")
    run = engine.advance(run["id"])
    previous_doc = run["tasks"]["docs"]["result"]["change_notes"]
    run = engine.replan(run["id"], "security")
    assert run["tasks"]["docs"]["status"] == "completed"
    assert run["tasks"]["docs"]["result"]["change_notes"] == previous_doc
    assert run["tasks"]["release_approval"]["status"] == "pending"
    with pytest.raises(ValueError):
        engine.approve(run["id"], "release_approval", "Ishee")
    run = engine.advance(run["id"])
    assert run["tasks"]["release_approval"]["status"] == "blocked"
    assert run["tasks"]["docs"]["status"] == "completed"


def test_invalid_model_output_fail_closed(engine, monkeypatch):
    import orchestrator.agents as agents
    monkeypatch.setattr(agents, "ask_model", lambda *_: {"acceptance_criteria": "bad"})
    run = engine.create("greenfield", "Build URL shortener", use_llm=True)
    run = engine.advance(run["id"])
    assert run["status"] == "failed"
    assert run["tasks"]["intake"]["status"] == "failed"
    assert run["tasks"]["implement"]["status"] == "pending"


def test_staged_tests_can_import_user_installed_dependencies(engine, monkeypatch):
    import os
    import site
    import orchestrator.agents as agents

    recorded_env = []
    real_run = agents.subprocess.run

    def check_environment(command, **kwargs):
        if command[1:3] == ["-m", "pytest"]:
            recorded_env.append(kwargs["env"])
        return real_run(command, **kwargs)

    monkeypatch.setattr(agents.subprocess, "run", check_environment)
    run = engine.create("greenfield", "Build URL shortener")
    run = engine.advance(run["id"])

    assert run["status"] == "waiting_approval"
    assert recorded_env
    assert site.getusersitepackages() in recorded_env[0]["PYTHONPATH"].split(os.pathsep)
    assert recorded_env[0]["HOME"] == run["tasks"]["implement"]["result"]["candidate"]
