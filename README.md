# Agentic URL Shortener

**Ishee Gattani**

This project has two parts: a URL shortener API and a workflow engine that takes a software requirement through planning, code changes, tests, review, and release readiness. I kept them separate so the API can run on its own and the workflow can be tested independently.

## Run the project

Python 3.10+ is required.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e '.[dev]'
python -m pytest -q
python -m uvicorn url_shortener.app:app --reload
```

Open http://localhost:8000/docs to try the API. The project uses a local SQLite database, so there is no separate database setup.

## URL shortener

The service supports creating a short link, choosing a custom alias, redirecting to the original URL, checking click counts, and disabling a link. Links can have expiration dates. It also handles duplicate aliases, repeated requests with an idempotency key, and invalid destinations.

Try the API:

```bash
curl -X POST http://localhost:8000/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/articles","custom_alias":"article1"}'
curl -i http://localhost:8000/article1
curl http://localhost:8000/api/v1/links/article1/stats
curl -X DELETE -i http://localhost:8000/api/v1/links/article1
```

## Engineering workflow

The workflow is a dependency graph, not a fixed sequence of commands. Each step records its result, and a step starts only when its dependencies are complete.

1. Understand the requirement and record assumptions.
2. Break the work into tasks and identify affected code.
3. Prepare a code-change proposal and a diff.
4. Get human approval before applying a proposed change.
5. Apply approved changes in a separate candidate directory, then run security checks and tests.
6. Collect test results and documentation for review.
7. Require final approval before writing a release-readiness report.

Independent work can run in parallel; the review step waits for its required results. Failed tests have a bounded retry. Rejected changes stop the workflow and remove the staged candidate. Changes to requirements or saved outputs invalidate the appropriate work and approvals. Nothing is merged or deployed automatically.

### Run a brownfield example

```bash
python -m orchestrator.cli create --scenario brownfield \
  --requirement 'Reserve the support custom alias'
```

Copy the run ID from the output and replace `RUN_ID` below:

```bash
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli status RUN_ID
```

Review `artifacts/RUN_ID/proposed_change.diff`, then continue:

```bash
python -m orchestrator.cli approve RUN_ID --gate change_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli approve RUN_ID --gate release_approval --actor Ishee
python -m orchestrator.cli advance RUN_ID
python -m orchestrator.cli events RUN_ID
python -m orchestrator.cli metrics
```

Run state and audit events are stored in `runs/workflows.sqlite`. Reports and the staged candidate are saved under `artifacts/RUN_ID/`. These local files are excluded from Git.

## Three scenarios

| Scenario | What it demonstrates |
|---|---|
| Greenfield | Stage and test the URL shortener, then request release approval |
| Brownfield | Inspect the existing code, propose an alias-validation change, review the diff, and run regression tests |
| Ambiguous requirement | Pause for clarification, update the requirement, invalidate old approvals, and check the analytics privacy contract |

The commands for each scenario, including retry, rollback, and re-planning demos, are in [docs/SCENARIOS.md](docs/SCENARIOS.md).

## Optional model-backed changes

The three local scenarios run without a model account. With `--llm`, the workflow can ask a configured model to interpret requirements, suggest tasks, identify impacted files, and propose source edits with a regression test. The proposal must pass file, syntax, and safety checks and receive human approval before it is applied to the staged candidate.

To use this path, configure a compatible HTTPS chat-completions endpoint:

```bash
export MODEL_API_URL='https://YOUR_ENDPOINT/v1/chat/completions'
export MODEL_API_KEY='YOUR_SECRET_KEY'
export MODEL_NAME='YOUR_MODEL_ID'
python -m orchestrator.cli create --scenario greenfield \
  --requirement 'Reserve the help alias and add a test' --llm
```

Do not commit credentials. Automated tests use a fake model response to verify this path without network access. A live model call is optional and isn't part of the default test run.

## Design decisions and limits

- **SQLite** keeps the demo easy to run. A larger service would need distributed storage and asynchronous click processing.
- **Redirects take priority over analytics.** A failed click-recording write does not prevent a valid redirect.
- **Source changes stay staged.** Approval creates a dry-run release report, not a merge or deployment.
- **Safety checks have limits.** File allowlists, review, and static checks reduce risk, but the candidate test runner is not a hardened sandbox.
- **This is a local prototype.** Authentication, rate limiting, link-abuse protection, authenticated reviewers, and production monitoring would be needed for a public service.

See [Architecture](docs/ARCHITECTURE.md), [Design decisions](docs/DECISIONS.md), and [Engineering walkthrough](docs/INTERVIEW_GUIDE.md) for the reasoning and validation steps.
