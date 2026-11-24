# Agentic URL Shortener

Ishee Gattani

A Java 21 / Spring Boot prototype with two parts: a URL shortener API and an engineering workflow that takes requirements through planning, reviewed changes, tests and release readiness.

## Run

Requires Java 21 and Maven 3.9+.

```bash
mvn test
mvn spring-boot:run
```

The service runs at `http://localhost:8080`. It uses a file-backed H2 database. No database server or AI API key is required for the three local scenarios.

## Short-link API

```bash
curl -X POST http://localhost:8080/api/v1/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/story","customAlias":"mydemo"}'
curl -i http://localhost:8080/mydemo
curl http://localhost:8080/api/v1/links/mydemo/stats
curl -X DELETE -i http://localhost:8080/api/v1/links/mydemo
```

Links support custom aliases, expiry, duplicate protection, idempotency keys, redirects, click counts and disable. Analytics is best effort so a click-recording problem does not stop a valid redirect.

## Engineering workflows

Create a scenario:

```bash
curl -s -X POST http://localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"brownfield","requirement":"Reserve the support alias"}'
```

Copy the returned `id` and replace `RUN_ID` below.

```bash
curl -s -X POST http://localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s http://localhost:8080/api/v1/workflows/RUN_ID/diff
curl -s -X POST http://localhost:8080/api/v1/workflows/RUN_ID/approvals \
  -H 'Content-Type: application/json' \
  -d '{"gate":"change_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST http://localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s -X POST http://localhost:8080/api/v1/workflows/RUN_ID/approvals \
  -H 'Content-Type: application/json' \
  -d '{"gate":"release_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST http://localhost:8080/api/v1/workflows/RUN_ID/advance
```

Inspect `artifacts/RUN_ID/` for the requirement, plan, design impact, proposed diff, test report, engineering summary, audit evidence and dry-run release manifest. The working source is never automatically merged or deployed.

## Three scenarios

| Scenario | What the workflow demonstrates |
|---|---|
| Greenfield | Stages and validates the existing service, then requires release approval |
| Brownfield | Plans and executes an alias validation change with regression test, review and separate approval gates |
| Ambiguous | Stops for requirements approval, accepts revised requirements and validates click-data privacy |

The plan inserts executable task nodes into the dependency graph. Source edits and generated tests are separate tasks; dependencies decide their execution order. The security and documentation paths are independent and synchronize at review. The graph, approval gates and allowed actions are controlled by the application rather than generated model instructions.

See [scenarios](docs/SCENARIOS.md) for the commands for each demo, including retries, rollback and replanning. The [API contract](docs/openapi.yaml) describes the HTTP endpoints.

## Optional model-backed engineering changes

Set the environment variables below to use a compatible chat-completions API:

```bash
export MODEL_API_URL='https://YOUR_ENDPOINT/v1/chat/completions'
export MODEL_API_KEY='YOUR_KEY'
export MODEL_NAME='YOUR_MODEL'
```

Create a workflow with `"useModel":true`. The model interprets requirements, generates an executable task plan, suggests impacted code and proposes an anchored Java edit plus JUnit test. Only allowlisted source files and known actions can be used. The proposal requires human approval before it is staged or tested. Credentials are not required to run the default workflows, and are never part of the staged test runner environment. The full model-assisted approval flow is tested offline with scripted responses:

```bash
mvn -q -Dtest=ModelWorkflowTest test
```

That test verifies model-created tasks, the staged source edit and regression test, human approvals, release readiness and audit integrity. It does not claim that a live model provider was contacted.

## Design decisions and limitations

- Spring Boot keeps the API simple; JDBC/H2 supports a local persistent demo without database infrastructure.
- The orchestration graph is small and explicit. Task state, approval history and chained audit events are stored in H2. Changes to a requirement revoke previous approvals; changing an artifact can invalidate dependent tasks without resetting independent work.
- A failed test run is retried once. If both attempts fail, a separate compilation diagnostic is recorded as a fallback, but release remains blocked. A failed or rejected workflow removes only its staged candidate. It cannot roll back a production deployment.
- Source edits are limited to an allowlist, exact anchors and SHA-256 preconditions. Static checks are only defense in depth, not a hardened sandbox. Production execution would require container isolation, network restrictions and authenticated reviewers.
- The release step writes a readiness manifest only. There is no automatic commit, merge, deployment or public hosting.
- Audit hashes are tamper-evident in this local prototype, but are not an independent compliance archive. `mttr_proxy_ms` measures retry recovery, not service incident recovery.

[Architecture and trade-offs](docs/ARCHITECTURE.md)
