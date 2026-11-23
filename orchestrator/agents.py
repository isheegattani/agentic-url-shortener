"""Purpose-limited engineering agents with optional reviewed model proposals.

Model output is never a command, workflow policy, or authority to approve.
Code changes are staged, checked, and require review before execution.
"""
from __future__ import annotations

import json
import os
import shutil
import site
import subprocess
import sys
from pathlib import Path

from .model import ModelError, ask_model, string_list
from .patching import make_proposal, apply_proposal, check_python

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = Path(os.getenv("ORCHESTRATOR_ARTIFACTS", str(ROOT / "artifacts"))).resolve()
BANNED_REQUESTS = ("disable audit", "skip approval", "remove security checks", "log passwords", "disable logging")


def artifact_dir(run):
    path = ARTIFACTS / run["id"]
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    return str(path)


def model_stage(run, stage, root, instructions, context):
    """Save the model response for review without letting it approve a step."""
    if not run.get("use_llm"):
        return None
    result = ask_model(stage, instructions, context)
    write_json(root / f"{stage}_model_output.json", result)
    return result


def execute(name, run):
    root = artifact_dir(run)
    requirement = run["requirement"].strip()
    if name == "intake":
        if any(x in requirement.lower() for x in BANNED_REQUESTS):
            raise PermissionError("Policy denies requests to weaken audit, security, or approvals")
        outcomes = {
            "greenfield": ["create", "redirect", "analytics", "disable", "tests"],
            "brownfield": ["inspect existing validation", "extend reserved alias policy", "regression tests"],
            "ambiguous": ["record assumptions", "human clarification", "prove privacy and analytics contracts"]
        }[run["scenario"]]
        interpretation = model_stage(run, "intake", root,
            "Interpret the requirement. Return JSON with three arrays of concise strings: "
            "acceptance_criteria, assumptions, ambiguities. Do not invent approvals or bypass controls.",
            {"requirement": requirement, "scenario": run["scenario"]})
        if interpretation is not None:
            for key in ("acceptance_criteria", "assumptions", "ambiguities"):
                string_list(interpretation.get(key))
        artifact = write_json(root / "normalized_requirement.json", {
            "original": requirement, "scenario": run["scenario"], "version": run["version"],
            "acceptance_criteria": interpretation["acceptance_criteria"] if interpretation else outcomes,
            "assumptions": interpretation["assumptions"] if interpretation else
                ["Local-only prototype with SQLite persistence", "No public deploy without human review"],
            "ambiguities": interpretation["ambiguities"] if interpretation else
                (["Meaning of privacy and analytics retention; no IP storage assumed"] if run["scenario"] == "ambiguous" else []),
            "execution_mode": "model_assisted" if interpretation else "bounded_scenario"})
        return {"requirement": artifact}
    if name == "plan":
        model_plan = model_stage(run, "plan", root,
            "Decompose the requirement into 3-10 concrete tasks. Return JSON with tasks array "
            "of {id,action,depends_on} where all dependency IDs refer to earlier task IDs. "
            "Do not try to edit the mandatory governance DAG.",
            {"normalized_requirement": json.loads((root / "normalized_requirement.json").read_text()),
             "mandatory_workflow": run["graph"]})
        if model_plan is not None:
            tasks = model_plan.get("tasks")
            if not isinstance(tasks, list) or not 3 <= len(tasks) <= 10:
                raise ModelError("Model task decomposition is invalid")
            known = set()
            for task in tasks:
                if not isinstance(task, dict) or not isinstance(task.get("id"), str) or \
                   not isinstance(task.get("action"), str) or not isinstance(task.get("depends_on"), list) or \
                   not all(isinstance(dep, str) and dep in known for dep in task["depends_on"]) or \
                   len(task["id"]) > 40 or len(task["action"]) > 300 or task["id"] in known:
                    raise ModelError("Model task dependency is invalid")
                known.add(task["id"])
        return {"plan": write_json(root / "plan.json", {
            "nodes": run["graph"], "critical_path": ["intake", "plan", "design", "proposal", "implement", "test", "review", "release_approval", "release"],
            "parallel_work": ["security, test and docs after implementation"],
            "model_decomposition": model_plan["tasks"] if model_plan else None,
            "version": run["version"]})}
    if name == "design":
        modules = sorted(p.relative_to(ROOT).as_posix() for p in (ROOT / "url_shortener").glob("*.py"))
        impacted = {
            "greenfield": ["url_shortener/app.py", "url_shortener/storage.py", "url_shortener/validation.py"],
            "brownfield": ["url_shortener/validation.py", "url_shortener/app.py", "tests/test_api.py"],
            "ambiguous": ["url_shortener/storage.py", "url_shortener/app.py", "tests/test_api.py"],
        }[run["scenario"]]
        design = (f"# Design / impact analysis (v{run['version']})\n\n"
                  f"Requirement: {requirement}\n\n"
                  "Flow: POST /api/v1/links -> validate -> SQLite transaction -> short code; "
                  "GET /{code} -> lookup -> expiry check -> best-effort analytics -> 302.\n\n"
                  "Inventory: " + ", ".join(modules) + "\n\n"
                  "Impacted files and API/flow dependencies: " + ", ".join(impacted) + "\n\n"
                  "Change isolation: candidate copy; no direct write to application source; human approves releases.\n")
        p = root / "architecture_impact.md"
        p.write_text(design)
        model_design = model_stage(run, "design", root,
            "Return JSON with impacted_modules (list of paths from provided source inventory) "
            "and tradeoffs (list of concise strings). No code or shell commands.",
            {"requirement": requirement, "modules": modules,
             "plan": json.loads((root / "plan.json").read_text())})
        if model_design is not None:
            impacted = string_list(model_design.get("impacted_modules"))
            if any(module not in modules for module in impacted):
                raise ModelError("Model suggested source outside bounded inventory")
            string_list(model_design.get("tradeoffs"))
            with p.open("a") as report:
                report.write("\nProposed affected modules: " + ", ".join(impacted) +
                             "\nTrade-offs: " + "; ".join(model_design["tradeoffs"]) + "\n")
        return {"impact": str(p)}
    if name == "proposal":
        proposed = make_proposal(ROOT, run["scenario"], requirement, run.get("use_llm", False))
        diff = root / "proposed_change.diff"
        diff.write_text(proposed.pop("diff"))
        manifest = write_json(root / "proposal.json", proposed)
        return {"proposal": manifest, "review_diff": str(diff), "mode": proposed["mode"]}
    if name == "implement":
        candidate = root / "candidate"
        if candidate.exists():
            shutil.rmtree(candidate)
        shutil.copytree(ROOT / "url_shortener", candidate / "url_shortener", ignore=shutil.ignore_patterns("__pycache__"))
        (candidate / "tests").mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "tests" / "test_api.py", candidate / "tests" / "test_api.py")
        proposal = json.loads((root / "proposal.json").read_text())
        modified = apply_proposal(candidate, proposal)
        if run["scenario"] == "ambiguous" and not run.get("use_llm"):
            (candidate / "tests" / "test_privacy_contract.py").write_text(
                "from url_shortener.storage import LinkStore\n\n"
                "def test_click_storage_has_no_ip_or_user_agent_columns(tmp_path):\n"
                "    store = LinkStore(str(tmp_path / 'privacy.sqlite'))\n"
                "    with store.connection() as db:\n"
                "        columns = {row[1] for row in db.execute('PRAGMA table_info(clicks)')}\n"
                "    assert 'ip' not in columns and 'user_agent' not in columns\n")
            modified.append("tests/test_privacy_contract.py")
        changes = modified or ["Staged verified URL shortener for isolated contract tests"]
        return {"candidate": str(candidate), "changes": changes,
                "manifest": write_json(root / "change_manifest.json", {
                    "scenario": run["scenario"], "mode": proposal["mode"], "changes": changes,
                    "proposal_diff": str(root / "proposed_change.diff")})}
    if name == "security":
        candidate = root / "candidate"
        if not candidate.exists():
            raise RuntimeError("Security gate requires staged candidate")
        inspected = []
        for source in sorted((candidate / "url_shortener").glob("*.py")) + sorted((candidate / "tests").glob("*.py")):
            check_python(source.read_text())
            inspected.append(source.relative_to(candidate).as_posix())
        return {"security_report": write_json(root / "security.json", {
            "status": "passed", "inspected": inspected,
            "policy": "staged Python AST checks; exact anchored edits; human review before code execution",
            "residual_risks": ["AST checks are not an OS sandbox", "No production auth/rate limiting",
                               "No destination reputation screening; run code in a sandbox before real deployment"]})}
    if name == "test":
        candidate = root / "candidate"
        if not candidate.exists():
            raise RuntimeError("Candidate directory missing")
        # Run a known test command, not a command from the proposed change.
        proc = subprocess.run([sys.executable, "-m", "pytest", "-q", "tests"],
                              cwd=candidate, text=True, capture_output=True, timeout=70,
                              env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                                   "PYTHONPATH": os.pathsep.join((str(candidate), site.getusersitepackages())),
                                   "HOME": str(candidate),
                                   "PYTHONDONTWRITEBYTECODE": "1", "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1"})
        result = {"command": "python -m pytest -q tests", "returncode": proc.returncode,
                  "stdout": proc.stdout[-5000:], "stderr": proc.stderr[-3000:]}
        report = write_json(root / "test_report.json", result)
        if proc.returncode != 0:
            # Compilation helps diagnose a failure but does not pass the test gate.
            fallback = subprocess.run([sys.executable, "-m", "compileall", "-q", "url_shortener"],
                                      cwd=candidate, capture_output=True, text=True, timeout=15,
                                      env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                                           "PYTHONPATH": os.pathsep.join((str(candidate), site.getusersitepackages())),
                                   "HOME": str(candidate),
                                           "PYTHONDONTWRITEBYTECODE": "1"})
            write_json(root / "fallback_diagnostic.json", {"compileall_rc": fallback.returncode})
            raise RuntimeError(f"Test gate failed; see {report}. Compile-only fallback is diagnostic, not a bypass")
        return {"test_report": report, "passed": True}
    if name == "docs":
        p = root / "change_notes.md"
        manifest = json.loads((root / "change_manifest.json").read_text())
        p.write_text("# Change notes\n\n" + "\n".join("- " + c for c in manifest["changes"]) +
                     "\n\nRollout: do not merge or deploy before release approval.\n")
        summary = root / "engineering_summary.md"
        spec = json.loads((root / "normalized_requirement.json").read_text())
        report = json.loads((root / "test_report.json").read_text()) if (root / "test_report.json").exists() else {}
        summary.write_text(
            "# Engineering outcome summary\n\n"
            f"Requirement (v{run['version']}): {requirement}\n\n"
            f"Scenario: {run['scenario']}\n\n"
            "## Plan and rationale\n"
            "Normalize the requirement; decompose work; identify affected modules; "
            "review the proposed diff; stage candidate code; validate security and tests; "
            "require human approval before release readiness.\n\n"
            "## Change artifacts\n" + "\n".join("- " + item for item in manifest["changes"]) +
            "\n\n## Risks and validation\n"
            "All code changes are isolated. The release is dry-run only. "
            "Policy scans are defense in depth, not a full container sandbox. "
            "The independent test task must pass before approval.\n\n"
            "## Assumptions\n" + "\n".join("- " + item for item in spec["assumptions"]) +
            "\n\n## Limitations\n"
            "Local SQLite state and analytics are not production-scale. Human identity is a CLI label; "
            "the model endpoint is optional. Release does not deploy, push or merge.\n")
        return {"change_notes": str(p), "engineering_summary": str(summary)}
    if name == "review":
        checks = [root / "test_report.json", root / "security.json", root / "change_notes.md",
                  root / "engineering_summary.md", root / "proposal.json", root / "proposed_change.diff"]
        if not all(p.is_file() for p in checks):
            raise RuntimeError("Review requires verified tests, security and docs")
        test_report = json.loads(checks[0].read_text())
        if test_report["returncode"] != 0:
            raise RuntimeError("Test report is not passing")
        return {"review": write_json(root / "review.json", {
            "status": "ready_for_human_review", "tests": "passed", "security": "passed",
            "lineage": {"requirement_version": run["version"], "tests": str(checks[0]),
                        "security": str(checks[1]), "summary": str(checks[3]),
                        "proposal": str(checks[4]), "change_diff": str(checks[5])},
            "approval_required": True})}
    if name == "release":
        # Write a readiness report; never deploy or merge from this step.
        approval = run["approvals"].get("release_approval", {})
        if approval.get("decision") != "approved":
            raise PermissionError("Release needs explicit human approval")
        return {"release_manifest": write_json(root / "release_manifest.json", {
            "ready": True, "mode": "dry_run_only", "version": run["version"],
            "approved_by": approval["actor"], "candidate": str(root / "candidate"),
            "disclaimer": "Staged candidate is NOT merged or deployed"})}
    raise ValueError(f"Unknown agent {name}")
