# Design decisions and quality gates

1. **FastAPI and SQLite:** fast local reproducibility with typed OpenAPI documentation. Production upgrade: PostgreSQL, cache, asynchronous analytics.
2. **Random codes with a unique index:** unpredictable codes, database-enforced uniqueness and bounded collision retries. Idempotency keys protect repeated create requests.
3. **Reliable redirects:** analytics recording is best effort; unavailable analytics must not break valid redirects. Exact counting needs an event queue and durable consumer.
4. **Policy-owned DAG:** explicit task dependencies and approval nodes are immutable to model suggestions. Parallel work joins at review and cannot bypass failed prerequisites.
5. **Proposal before implementation:** a real model-generated change is shown as an anchored unified diff and requires human approval *before execution*. The source checkout is never modified automatically.
6. **Small allowlist and preconditions:** only three service source files and one generated regression test file are supported. SHA-256 source checks and unique anchors prevent silent application to a changed base.
7. **Fail closed:** invalid or unconfigured model calls fail the model-assisted run; deterministic offline scenarios run independently. Fallback compilation is diagnostic only.
8. **Selective and full re-planning:** changed requirements reset every stage/approval; unexpected stage-output changes re-run dependent stages only. Dependency joins preserve unaffected work.
9. **Audit and isolation:** local chained audit and staged-directory rollback demonstrate safety; neither constitutes a production compliance archive or a full execution sandbox.

## Stage contracts

| Stage | Entry gate | Reviewable exit artifact | On failure |
|---|---|---|---|
| Intake | nonempty requirement + policy rules | normalized requirement | stop |
| Requirements approval | ambiguous intake | human decision | stop if rejected |
| Plan | accepted requirements | plan with typed dependencies | stop |
| Design | plan | impact analysis | stop |
| Proposal | design | validated proposal and unified diff | stop |
| Change approval | brownfield or model change | human decision on diff | stop + candidate rollback |
| Implementation | approved change if needed | isolated candidate and change manifest | rollback |
| Security | candidate | staged source policy report | stop + rollback |
| Tests | security passed | actual pytest result | 1 retry; diagnostic only; rollback |
| Documentation | candidate | change notes and summary | stop |
| Review | tests + security + docs | review with decision lineage | stop |
| Release approval | review passed | human decision | stop + rollback if rejected |
| Release readiness | approved release | dry-run manifest, no deploy | stop |

**Explicit limits:** No authenticated users or public traffic hardening; no protected sandbox; CLI labels are not proof of identity; no automatic pull request/merge/deploy; model-assisted proposals support bounded changes rather than unrestricted repository modifications. These constraints are intentional and documented to avoid overstating autonomy.
