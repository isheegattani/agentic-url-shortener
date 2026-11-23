# Engineering walkthrough (about 7 minutes)

**Introduction:** "I split the work into a URL shortener and a governed agentic SDLC orchestrator. The key problem is not CRUD; it is making autonomous engineering tasks safe, stateful and reviewable."

**1. Service (1 minute):** Run `python -m uvicorn url_shortener.app:app --reload` and open `/docs`. Show create, redirect, analytics and disable. Explain random unique codes, idempotency keys, soft disable, and best-effort analytics.

**2. DAG and stages (1 minute):** Show `orchestrator/graph.py` and `docs/ARCHITECTURE.md`. Highlight explicit dependencies, human gates, parallel documentation/security work, security before execution of candidate tests, and the final evidence join.

**3. Real changed code (2 minutes):** Start the brownfield scenario, then open `artifacts/RUN_ID/proposed_change.diff`. Note that **proposal and implementation are separate nodes**. Approve the change, advance, inspect `test_report.json` and `engineering_summary.md`, and explain why no direct commit/deploy is allowed. The optional model-backed proposal follows this same review path. In automated tests, a stubbed model produces an actual new patch and test and proves the change gate is not bypassed.

**4. Uncertainty and recovery (1 minute):** Run the ambiguous scenario and show its requirements approval gate, requirement revision and invalidated decisions. Inject a transient test failure and show the bounded retry. Reject a gate and show candidate rollback. Use `replan --changed-stage security` to show independent docs remain complete while review and release approval are invalidated.

**5. Reliability and audit (1 minute):** Run `events` to inspect versioned decisions and event hashes, `metrics` for observed success/retries/rollbacks/replans/latency, and GitHub Actions for the passing automated test suite.

**6. Engineering judgment (1 minute):** "I constrained model autonomy to producing structured, inspectable change proposals. Policy decides what runs and what is approved. Generated code is checked and requires human approval before tests execute. This is a local prototype, not a production sandbox: I would add network-isolated runners, authenticated reviewer identity, an external audit sink, distributed storage and anti-abuse controls before deployment."

## Likely follow-ups

- **Is there real model integration?** Yes, optionally: JSON model calls normalize requirements, decompose tasks, identify impacted code and propose concrete anchored changes plus tests. The offline deterministic demo is intentionally reproducible without an API key. CI also exercises the model-change path with a fake provider. Never claim arbitrary autonomous repository changes or a production LLM sandbox.
- **Why a custom DAG instead of a framework?** The small explicit graph makes policy, dependency decisions and failure paths observable in a short prototype. A mature durable workflow engine is preferable for distributed production execution.
- **What stops approval bypass?** Gates are owned by policy and have no model executor; code-change proposals cannot rewrite the graph. A human must approve before candidate code is applied/executed and again before release readiness. CLI identities are not authenticated and that remains a limitation.
- **What happens when upstream output changes?** Fingerprint mismatches invalidate affected descendants and approvals, but preserve independent branches. Requirement changes conservatively restart the full workflow.
- **What does rollback mean?** Delete the staged candidate only. There is no actual deploy, so it is not a production rollback.
- **What is the greatest risk?** Running untrusted code without an OS-level sandbox. Human review, a restricted environment, and AST gates reduce risk for a demonstration; production would require container isolation and restricted network and credentials.
