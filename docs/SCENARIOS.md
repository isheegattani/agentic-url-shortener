# Scenario walkthroughs

Start the service with `mvn spring-boot:run`. Each `create` request returns a run `id`; substitute it for `RUN_ID`.

## Greenfield

```bash
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"greenfield","requirement":"Build URL shortening, redirects and click analytics"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance?faultOnce=true
curl -s localhost:8080/api/v1/workflows/RUN_ID/events
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/approvals -H 'Content-Type: application/json' \
  -d '{"gate":"release_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
```

The baseline service is staged, contract tests run, a single injected transient failure is retried and release approval is required. Read `artifacts/RUN_ID/release_manifest.json`.

## Brownfield

```bash
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"brownfield","requirement":"Reserve the support alias"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s localhost:8080/api/v1/workflows/RUN_ID/diff
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/approvals -H 'Content-Type: application/json' \
  -d '{"gate":"change_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/approvals -H 'Content-Type: application/json' \
  -d '{"gate":"release_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
```

The plan creates actual `SOURCE_PATCH` and `ADD_REGRESSION_TEST` nodes. Both wait for staging, which waits for change approval. `security` joins all plan tasks; `docs` is independent of `security` and `test`, and `review` joins their results. The source checkout stays unchanged.

## Ambiguous requirement

```bash
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' \
  -d '{"scenario":"ambiguous","requirement":"Make analytics private and efficient"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s -X PUT localhost:8080/api/v1/workflows/RUN_ID/requirement -H 'Content-Type: application/json' \
  -d '{"requirement":"Keep daily click counts without storing IP addresses or user-agent information"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/approvals -H 'Content-Type: application/json' \
  -d '{"gate":"requirements_approval","actor":"Ishee","decision":"approved"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
```

The workflow pauses before planning, revokes old approvals after revision, creates an executable schema privacy test and waits for release approval after validation. Approve `release_approval`, then advance again to finish.

## Failure handling and observability

```bash
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/replan \
  -H 'Content-Type: application/json' -d '{"changedStage":"security"}'
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/advance
curl -s -X POST localhost:8080/api/v1/workflows/RUN_ID/approvals \
  -H 'Content-Type: application/json' -d '{"gate":"release_approval","actor":"Ishee","decision":"rejected"}'
curl -s localhost:8080/api/v1/workflows/metrics
curl -s localhost:8080/api/v1/workflows/RUN_ID/events
```

A selective replan invalidates the changed task and descendants, preserving independent documentation. Rejection stops the run and deletes staged code. Audit hashes are checked when events are requested.
