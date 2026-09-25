# Architecture

This repository has two parts in one Maven module:

1. **The product.** A URL shortener (`com.agentsdlc.shortener`).
2. **The system under assessment.** An agentic SDLC orchestrator
   (`com.agentsdlc.orchestrator`) that plans, builds, analyses, fixes and
   releases software as an explicit, governed task DAG.

They share a build so the brownfield pipeline can reason about *real*
product source code (impact analysis scans `src/main/java/com/agentsdlc/shortener`).
Nothing in the product depends on the orchestrator.

---

## Part A: URL shortener

```
HTTP ─► ShortenerController ─► LinkService ─► LinkRepository / ClickRepository / IdempotencyRepository ─► H2 (Flyway schema)
            │                     ├─► UrlSafetyValidator (SSRF)
            ├─► RateLimiter       ├─► CodeGenerator (SecureRandom base62)
            └─► VisitorHasher     └─► VisitorHasher (HMAC of IP)
ApiExceptionHandler: ShortenerException(status) → {"error","message"} (+ Retry-After on 429)
```

| Concern | Design | Why |
|---|---|---|
| Uniqueness (aliases, idempotency keys) | Insert and let the primary key decide. `Link` and `IdempotencyRecord` implement `Persistable` so `save()` means `persist`, not `merge` | Check-then-act races under concurrency. `merge` on an assigned id silently **overwrites**, which is the brownfield bug |
| Idempotency | `idempotency_keys(idem_key PK, request_hash, code)`. Replay → 200 and the same code. Same key with a different body → 422 | Returning another request's code for a different URL would be a correctness bug. Rejecting it keeps "same key → same code" |
| Conflict resolution | `TransactionTemplate` per attempt. After a failed insert: first replay the idempotency key, then 409 for an alias, else retry with a new random code | That decision has to happen **after** rollback, which one `@Transactional` method cannot express |
| Codes | 8 chars of base62 from `SecureRandom`, about 47 bits | Codes are bearer tokens and must not be enumerable. Hash-of-URL and sequential ids both are |
| Rate limiting | In-memory token bucket per hashed client IP, `Retry-After` computed from the refill rate | Allows bursts without the edge spikes of a fixed window. Per-instance by design; the class is the seam for Redis or a gateway |
| SSRF | Scheme allow-list, no userinfo, internal hostnames, obfuscated numeric IPs, and address classes of IP literals | No DNS lookup: the runtime must work offline, and resolve-then-check is defeated by DNS rebinding. Fetchers must re-check at connect time |
| Analytics | `clicks` is append-only, and a redirect never updates `links` | No write contention on hot links. Stats cannot corrupt links |
| Compliance | IPs → HMAC-SHA256 (`visitor_hash`), referrer reduced to its host, no request logging of IPs | A plain SHA-256 of IPv4 is brute-forceable (2³² inputs). A keyed hash is not |
| Expiry | `expires_at`, evaluated at redirect time (410). Stats remain available | No sweeper job, and expiry is exact |
| Schema | Flyway `V1__init.sql` with `ddl-auto=validate` | Reviewable, versioned DDL. Hibernate never mutates production schema |
| Contract | `docs/openapi.yaml`, enforced by `OpenApiContractTest` | Docs that can drift will drift |

---

## Part B: orchestrator

### Components

| Package | Component | Responsibility |
|---|---|---|
| `spec` | `RequirementNormalizer` | Request (+ clarifications) → `NormalizedSpec` via the LLM seam. Computes DRAFT/READY itself and fails closed on malformed output |
| `spec` | `Decomposer` | Spec kind → pipeline template → validated `TaskGraph` with risk tiers, retry, compensation, output contracts and tags |
| `core` | `TaskSpec`, `TaskGraph` | Declarative task nodes. Eager validation of duplicate ids, unknown deps and cycles. Kahn layering into waves |
| `core` | `TaskContext` | An agent's only window: its own namespace (write), any namespace (read), artifacts, decisions, audit |
| `engine` | `Orchestrator` | Wave execution, gates, retries with backoff, fallback, saga rollback, safe-stop, scoped runs, incident tracking |
| `gate` | `Gate` + 10 implementations | Entry ("may this start?") and exit ("did it deliver?") policies, selected by risk or tags |
| `approval` | `ApprovalProvider`, `ClarificationProvider` | Human-in-the-loop seams. Scripted implementations keep runs reproducible |
| `state` | `StateStore`, `DecisionLog` | Namespaced, hashable, snapshot-able state; decision lineage ("why") |
| `audit` | `AuditLog` | Append-only JSON Lines ("what"), monotonic `seq`, flushed per event |
| `metrics` | `Metrics` | Counters and MTTR from the audit log; latency from run reports |
| `replan` | `Replanner` | Output-hash diff → downstream closure → invalidate → scoped re-run |
| `llm` | `LlmProvider` (+ deterministic, Claude, OpenAI, fail-closed), `LlmPrompts`, `LlmOutput` | Model seam, offline by default; strict per-purpose output contracts for hosted models |
| `tools` | `JavaToolchain`, `SourceIndex` | In-process javac + JUnit Platform; regex code index for impact analysis |
| `agents` | 15 agents | The work itself |
| `scenario` | 3 scenarios + environment | End-to-end demonstrations with named checks and evidence |

### Execution model

```
run(pipeline, graph, scope):
  RUN_STARTED
  for wave in graph.waves():                      # topological layers, ids sorted
      out-of-scope tasks  → REUSED (if they succeeded before) — outputs untouched
      if halted           → SKIPPED("safe-stop: …")
      else fan out on a fixed pool (parallelism N) → invokeAll = join
         per task:
           dependency not satisfied?      → SKIPPED
           ENTRY gates in order           → BLOCKED (policy) | REJECTED (human)
           snapshot namespace ONCE
           attempt 1..max: clear ns → agent → EXIT gates      (TASK_RETRY + backoff between)
           fallback agent once if configured
           success → SUCCEEDED | SUCCEEDED_WITH_FALLBACK, record output hash, completion order
           failure → restore snapshot, FAILED
      after the join:
         FAILED   → rollback: completed tasks in REVERSE completion order:
                    compensation(), restore snapshot → COMPENSATED ; then halt
         BLOCKED/REJECTED → halt (no rollback: nothing went wrong)
  SAFE_STOP (if halted), INCIDENT_OPENED/RESOLVED, RUN_FINISHED
```

Key semantics:

- **Join barrier per wave.** `invokeAll` returns only when every task in the
  wave has finished. Waves are a conservative schedule: a task can wait for an
  unrelated slow sibling. The trade is simplicity and deterministic failure
  handling (see decisions).
- **Snapshot-once.** The pre-first-attempt snapshot is the rollback baseline.
  Re-snapshotting before a retry would bake a failed attempt's partial writes
  into the baseline. `OrchestratorTest.snapshotIsTakenOnce…` fails if that
  happens.
- **Clean attempts.** Each attempt starts with an empty namespace, so exit
  gates never pass on stale outputs from a previous attempt or run.
- **Failure vs. stop.** A failure triggers rollback. A policy block or human
  rejection only stops the run. Downstream tasks are always explicitly
  `SKIPPED`, never left `PENDING` and never reported as failures.
- **Crashing gates block.** An exception inside a gate becomes a `BLOCK`. A
  broken policy service must never wave work through.

### Gates (evaluation order)

| # | Gate | Phase | Applies to | Rule |
|---|---|---|---|---|
| 1 | `destructive-policy` | entry | destructive tasks | LOW risk ⇒ hard block (misclassification would dodge approval) |
| 2 | `spec-ready` | entry | tag `requires-ready-spec` | `requirements/spec.status == READY` |
| 3 | `impact-analysis` | entry | tag `requires-impact-analysis` | `IMPACT_ANALYSIS.md` exists with all 4 sections |
| 4 | `change-control` | entry | tag `release` | `change-record/status == APPROVED` |
| 5 | `human-approval` | entry | HIGH risk or tag `needs-approval` (not releases) | Human decision, audited as request + grant/reject |
| 6 | `human-final-signoff` | entry | tag `release` | Mandatory human sign-off for every release |
| 7 | `required-outputs` | exit | tasks with an output contract | Every declared key written |
| 8 | `secrets-scan` | exit | all | No credential patterns in artifacts |
| 9 | `compliance-pii` | exit | all | No emails, phone numbers or raw IPs in artifacts |
| 10 | `tests-must-pass` | exit | tag `tests-must-pass` | `tests.run > 0 && tests.failed == 0` |

Cheap policy checks run before human checkpoints, so nobody is asked to
approve something a policy would block. Findings name the rule and location,
never the matched value, so the audit log does not become a second copy of a
leaked secret.

### Pipelines (from `Decomposer`)

```
FEATURE:   requirements ─┐
           repo-inventory┴► design ► implement ► ┌ test ┐ ► change-record ► release
                                                  └ docs ┘
INCIDENT:  requirements ► triage ► impact-analysis ► reproduce ► regression-test ► migrate-fix
           ► refactor ► improve-tests-docs ► change-record ► release
```

### State, audit and lineage

- **StateStore.** One namespace per task, plus reserved `_status`,
  `_approvals` and `_human`. Values are strings, which makes them hashable
  (re-planning), snapshot-able (rollback) and persistable without a
  serialisation framework. Hashes are length-prefixed and order-independent.
- **AuditLog.** One JSON object per line: `seq, ts, runId, type, taskId, data`.
  `seq` is the ordering authority, not `ts`. Each line is flushed before
  `record()` returns. Keys are sorted, so identical events serialise
  identically. Reopening continues the sequence.
- **DecisionLog.** `decisions.jsonl`: *why* (decision, rationale, inputs),
  kept separate from *what happened*.

### Re-planning

`Replanner.fingerprint()` hashes every task namespace. After an upstream task
re-runs, `replan()` diffs the hashes. The changed tasks are the roots, and
their transitive downstream closure is invalidated (namespace cleared, status
`PENDING`) and returned as the next run's scope. The plan (changed,
invalidated, preserved) is audited **before** anything re-runs. Human input
lives in reserved namespaces and is never invalidated. That is how the
ambiguous scenario builds v2 without asking the human again, and without
re-running the independent `repo-inventory`.

### Metrics

From the audit log: counters for tasks, attempts, retries, fallbacks,
compensations, safe-stops, re-plans, gate failures and approvals, plus
**MTTR**: the mean of `INCIDENT_RESOLVED.ts − INCIDENT_OPENED.ts` paired by
`incidentId`. From run reports: per-stage mean execution time, **end-to-end
latency p50/p95** (nearest rank), task and run success rates. Written to
`metrics.json`.

---

## Key decisions and trade-offs

| Decision | Chosen | Rejected alternative | Trade-off |
|---|---|---|---|
| Scheduling | Topological waves with a join | Per-task futures, start as soon as deps finish | Waves can idle behind a slow sibling. In exchange they give a simple mental model, deterministic failure points (rollback happens between waves, never mid-flight) and readable audit logs |
| Agent communication | String key/value store, namespaced by task | Passing objects between agents | Less type safety. Every hand-off becomes visible, hashable, snapshot-able and auditable, and agents stay decoupled and replaceable |
| Policy placement | Pluggable gates selected by tag/risk | `if` statements in the engine or agents | One more indirection. Policy becomes testable in isolation and extendable without touching the engine |
| Rollback | Saga compensations, reverse completion order, snapshot-once | Global transaction / two-phase commit | Compensations must be written and can fail (audited, flagged for a human). But real side effects (files, deploys) cannot join a transaction |
| Blocked vs failed | Different outcomes (safe-stop vs rollback) | Treat every stop as a failure | Two paths to test. Rolling back good work because a human said "not yet" would destroy value |
| Audit format | JSON Lines, flushed per event | Database table / single JSON document | Not queryable without tooling. Crash-safe, append-only, greppable, streamable, zero dependencies |
| LLM | Deterministic offline provider behind a seam | Call a hosted model directly | Offline "generation" is template-based, which is honest but not creative. In exchange, reproducible CI, no keys, and gates are proven on known outputs |
| Test execution | In-process javac + JUnit Launcher | Fork `mvn test` | Shares the JVM, so generated code could interfere (mitigated with a fresh class loader per run). Fast, offline, structured results |
| Impact analysis | Regex index + one hop of references | Full parser / call-graph library | Over-reports (e.g. a name in a comment). That is the safe direction for impact analysis, with no dependencies and a transparent method |
| Human approval | Synchronous `ApprovalProvider` | Async workflow with persistence and resume | The demo blocks in-process. A production version would park the run and resume on a webhook; the seam is the same |

## Known limitations

- The state store and open-incident tracking are in memory. A crashed run
  cannot resume, although its audit log survives and describes exactly where
  it stopped.
- A task that *fails* is not compensated: only completed tasks are. A failed
  task's partial external effects must be idempotent or handled by its own
  error path (the backfill demo's agent is idempotent).
- Rate limiting is per instance, and SSRF checks do not resolve DNS (by
  design, see above).
- The offline LLM recognises the three scenario features. Other requests
  normalise to `feature=unknown` and stay DRAFT, which is the correct
  fail-safe but means the offline mode only builds what it has templates for.
  With `AGENTIC_LLM=claude`, `run-request.sh` builds arbitrary requirements.
- Code generated for an arbitrary requirement is a standalone component with
  its own generated tests; it is not merged into the service automatically.
  The generated tests are only as good as the model's reading of the spec,
  which is why a human signs off before release.
- Generated code is compiled with `-Xlint:all` but not `-Werror`: a model's
  harmless warning should not fail an otherwise correct attempt. The gates
  (tests-must-pass, secrets, PII) decide what ships.
- Generated code runs inside the orchestrator JVM. That is acceptable for
  templates and a reviewed demo; production would sandbox it (separate process
  or container, no network, time and memory limits).
