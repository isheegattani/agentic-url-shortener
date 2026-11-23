# Three executable SDLC scenarios

Install dependencies (`python -m pip install -e '.[dev]'`) and run all commands from the project root. Replace `RUN_ID` with the id returned by `create`. Every run has a persisted graph, staged artifacts, event trail and metrics. All approvals are explicit and release means **dry-run readiness**, not deployment.

## 1. Greenfield: deliver a URL shortener

```bash
python -m orchestrator.cli create --scenario greenfield \
  --requirement 'Implement shortening, redirects, and click analytics'
python -m orchestrator.cli advance RUN_ID --fault-once test
python -m orchestrator.cli status RUN_ID
# Confirm retry, review artifacts, then approve:
python -m orchestrator.cli approve RUN_ID --gate release_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli events RUN_ID
```

The bounded offline implementation stages the tested service; real API tests exercise create, redirect, analytics, expiry, idempotency and disable. Injected transient test failure demonstrates one bounded retry, audit event and MTTR proxy. Normal exit requires release review. The optional model-assisted mode can propose **additional new code** but cannot apply it without human change approval.

## 2. Brownfield: reserve an existing alias

```bash
python -m orchestrator.cli create --scenario brownfield \
  --requirement 'Reserve the support alias in the existing service'
python -m orchestrator.cli advance RUN_ID
# Inspect artifacts/RUN_ID/proposed_change.diff first.
python -m orchestrator.cli approve RUN_ID --gate change_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
# Inspect test_report.json, security.json, engineering_summary.md and review.json.
python -m orchestrator.cli approve RUN_ID --gate release_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
```

Reasoning identifies `url_shortener/validation.py` (policy), `url_shortener/app.py` (request mapping) and existing API tests. The proposal replaces one exact source anchor in the **staged candidate** and adds a regression test. A named reviewer sees the unified diff before execution. The source checkout remains unchanged; final readiness includes traceable validation evidence.

## 3. Ambiguous: make analytics private

```bash
python -m orchestrator.cli create --scenario ambiguous \
  --requirement 'Make analytics more private and efficient'
python -m orchestrator.cli advance RUN_ID
# Planning is blocked: no automatic interpretation of an unresolved privacy policy.
python -m orchestrator.cli revise RUN_ID \
  --requirement 'Keep aggregate click counts; never store IP addresses or user agents'
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli approve RUN_ID --gate requirements_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli approve RUN_ID --gate release_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
```

The requirement version changes; all approvals are revoked and a new plan is required. A new executable privacy contract inspects the click schema to verify the promised behavior. This is an honest scoped interpretation: actual analytics high-volume optimization is outside the prototype.

## Nonlinear execution, change detection and negative evidence

```bash
# On an in-progress run, invalidate only the security stage and dependent work.
python -m orchestrator.cli replan RUN_ID --changed-stage security
python -m orchestrator.cli advance RUN_ID
# Its independent docs stage stays complete; release approval must be collected again.

# Reject a waiting gate to safe-stop and remove the staged candidate.
python -m orchestrator.cli approve RUN_ID --gate release_approval \
  --actor Ishee --decision rejected

python -m orchestrator.cli metrics
python -m orchestrator.cli events RUN_ID
```

External changes to already-produced artifact files are detected by fingerprint on the next `advance` and automatically invalidate dependent tasks and approvals. Tests include a fake model that proposes actual source edits and tests; it verifies pre-execution change approval and that the original checkout never changes. A real model can be configured using the README's `MODEL_*` environment variables, but no credentials are needed to exercise every governance contract in CI.
