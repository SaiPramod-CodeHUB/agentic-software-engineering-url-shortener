# Engineering Summary

## 1. What was built, and the plan behind it

**Goal:** show how AI agents can deliver software *safely*. That means an
explicit plan, policy gates, humans on the decisions that matter, recovery
when things fail, and an audit trail that proves all of it. A demo that
merely calls a model in a loop does not meet that bar.

**Plan, in build order, with the reason for each step:**

1. **Product first (URL shortener).** Agents need a real codebase to reason
   about in the brownfield scenario. The product also carries its own
   production concerns (idempotency, concurrency, SSRF, privacy) that the
   agents' guardrails mirror.
2. **Core model** (`TaskSpec`, `TaskGraph`, `StateStore`, `AuditLog`). This
   is the vocabulary everything else uses. Validation is eager, so bad plans
   fail before any side effect.
3. **Engine** (`Orchestrator`) with *no* business policy: waves, retries,
   fallback, rollback, safe-stop, scoped runs.
4. **Gates** as plug-ins selected by tags and risk tier. Each assessment
   concern became a gate (change control, compliance, impact analysis,
   final sign-off) instead of an `if` statement buried in an agent.
5. **LLM seam** with a deterministic provider, so every behaviour is
   reproducible in CI and a hosted model can be swapped in.
6. **Agents and templates** (`Decomposer`) for FEATURE and INCIDENT
   pipelines.
7. **Scenarios as acceptance tests.** Every claim in this document is a named
   check that must pass for the script to exit 0.
8. **Docs, Javadoc and packaging.**

## 2. How each requirement is met

| Requirement | Where | Evidence |
|---|---|---|
| Requirement normalisation and decomposition | `RequirementNormalizer`, `Decomposer` | `SPEC_v*.md`; `PLAN_CREATED` audit event |
| Brownfield codebase reasoning | `ImpactAnalysisAgent`, `SourceIndex` | `IMPACT_ANALYSIS.md`: 18 classes, 4 endpoints, 3 tables, call paths, covering tests |
| Explicit DAG | `TaskGraph` | `RUN_STARTED.data.waves` |
| Sequential/parallel with sync | `Orchestrator` waves + `invokeAll` | `test ∥ docs` wave; barrier test |
| Stateful context and lineage | `StateStore`, `TaskContext`, `DecisionLog` | `decisions.jsonl` |
| Human checkpoints | `HumanApprovalGate` (approval + final sign-off) | `APPROVAL_REQUESTED/GRANTED/REJECTED` with approver, time and reason |
| Retries, fallback, rollback, safe-stop | `Orchestrator` | Brownfield rollback demo; `OrchestratorTest` |
| Security, compliance, change control | `SecretsScanGate`, `ComplianceGate`, `ChangeControlGate`, `DestructivePolicyGate` | Greenfield leak caught on attempt 1; logs scanned clean |
| Audit | `AuditLog` | `audit.jsonl` |
| Metrics incl. end-to-end latency and MTTR | `Metrics` | `metrics.json` |
| Dynamic re-planning | `Replanner` | Ambiguous scenario `REPLAN` event |
| API / schema definitions | `docs/openapi.yaml`, `V1__init.sql` | `OpenApiContractTest`, `ddl-auto=validate` |
| Refactor and test/doc improvement | `RefactorAgent`, `TestDocImprovementAgent` | Tests 1 → 5; stale doc corrected |

## 3. Validation results (this build)

| Command | Result |
|---|---|
| `mvn -B test` | **83 tests, 0 failures, 0 errors** |
| `./run-request.sh "<requirement>"` | Any requirement through the full governed pipeline; with Claude it generates, compiles and tests new code. Verified in tests with a scripted model and a fake Messages API (no live key in this environment) |
| `./run-greenfield.sh` | `RESULT SUCCESS`, 9/9 checks. The implementer's first attempt carried a scripted credential; `secrets-scan` blocked it; the retry was clean; 4 generated JUnit tests compiled and passed in-process; change record approved; final sign-off recorded |
| `./run-brownfield.sh` | `RESULT SUCCESS`, 23/23 checks. The race was reproduced (2 winners for one alias); the regression test failed on legacy code and passed after the fix; refactor stayed green; tests went from 1 to 5; stale doc corrected; destructive-LOW task blocked; human rejection → safe-stop with SKIPPED downstream; canary failure → retries at 10 and 20 ms → compensation in the order `backfill-aliases, apply-migration` → DB byte-identical to before → recovery run succeeded; MTTR computed |
| `./run-ambiguous.sh` | `RESULT SUCCESS`, 9/9 checks. Ambiguity 0.95 → DRAFT with 3 questions → design BLOCKED → human asked **once** → spec v2 READY → re-plan invalidated exactly {design, implement, test, docs, change-record, release}, preserved {repo-inventory} → built and released idle-link expiry (3 tests green) |
| `./scripts/smoke-test.sh` | 201 → 200 same code → 302 → stats show 1 click from `news.example.org` → 400 SSRF → 404 → health ok → no client IP in the service log |
| `mvn -B javadoc:javadoc` and raw `javadoc -Xdoclint:all` | **0 warnings, 0 errors** |

## 4. Risks, assumptions and limitations

**Assumptions**
- "Human approval" is synchronous and scripted. In production the run would
  park and resume on a callback; the `ApprovalProvider` seam stays the same.
- The offline LLM is template-backed for the three scenario features. That is
  deliberate: it proves the *governance*, and the gates judge any provider's
  output the same way.
- The brownfield "legacy" module is a self-contained reconstruction of the
  pre-fix alias logic, so the race can be reproduced deterministically. The
  real service is already fixed (primary key + `Persistable`), and its fix is
  proven separately by the concurrent integration test.

**Limitations**
- In-memory state: a crashed run cannot resume mid-DAG, although the audit
  log records exactly where it stopped.
- Waves wait on their slowest member, which is less throughput than
  dependency-driven scheduling.
- Only completed tasks are compensated. A failing task's partial external
  effects must be idempotent (the demo's backfill is).
- Impact analysis is regex-based and over-reports. That is the safe
  direction, but a human still prunes the results.
- The migration SQL in the brownfield scenario is generated, not executed:
  the legacy module is in-memory. The service's real schema already enforces
  uniqueness.
- Rate limiting is per instance; SSRF checks do not resolve DNS.

**Risks if taken to production**
- The TesterAgent runs generated code inside the orchestrator JVM. That is
  fine for templates. With a hosted model it needs a sandbox (separate
  process or container, no network, CPU and time limits).
- Secrets/PII scanning is pattern-based. It will miss novel formats and
  should be layered with a dedicated scanner.

## 5. Interview questions and answers

**Q1. Why an explicit DAG instead of letting an agent decide what to do next?**
Because the plan becomes an artifact you can review, validate and audit
before anything runs. Cycles, unknown dependencies and duplicate ids fail at
construction time, and every task declares its risk tier, retry policy,
compensation and output contract up front. A free-running agent loop makes
those properties emergent and unverifiable. Agents keep full freedom *inside*
a task; the DAG governs how tasks fit together.

**Q2. How do you stop an agent from doing something dangerous?**
With layered gates evaluated by the engine, not by the agent. The
destructive-policy gate hard-blocks destructive work declared LOW risk, since
that misclassification is how dangerous work would dodge review. HIGH-risk
work needs a human approval. Releases need an approved change record *and* a
final human sign-off. Exit gates reject artifacts containing secrets or PII
and refuse "green" test runs that executed zero tests. A gate that throws is
treated as a block, so a broken policy service never waves work through.

**Q3. What happens when a step fails halfway through a pipeline?**
The task is retried with exponential backoff. If retries are exhausted, a
fallback agent runs once if one is configured. If that fails too, the engine
compensates every task completed in this run in *reverse completion order*
(saga pattern), restores each task's state namespace to its pre-first-attempt
snapshot, marks all remaining tasks SKIPPED, emits SAFE_STOP and opens an
incident. The brownfield demo proves the database ends byte-identical to its
starting state, and a later successful run resolves the incident, which gives
MTTR.

**Q4. Why snapshot only once, before the first attempt?**
Retries happen after partial failures. If you re-snapshot before attempt 2,
the "baseline" now contains attempt 1's half-written state, and rollback
restores the corruption. There is a test that fails if the snapshot is taken
per attempt.

**Q5. Why is a human rejection not a rollback?**
Because nothing went wrong. The completed work is valid; a person decided the
next step should not happen yet. Rolling back would destroy correct work and
make people reluctant to say "no". So rejection and policy blocks safe-stop
(downstream SKIPPED), while only failures compensate.

**Q6. How does re-planning avoid redoing human work?**
Every task's outputs live in its own namespace and are hashed. After an
upstream change, only tasks whose hash changed are roots, and only their
transitive dependents are invalidated. The scope is audited before anything
re-runs. Human input lives in a reserved namespace that re-planning never
clears. In the ambiguous scenario the human is asked exactly once, and the
independent repo inventory is reused; both are checked.

**Q7. How did you make an LLM-driven system testable and reproducible?**
All model calls go through `LlmProvider`, and the default provider is
deterministic. Clocks, sleeps and randomness are injected. Races are forced
with barriers instead of being hoped for. The normaliser decides DRAFT/READY
itself from the returned score and fails closed on malformed output, so model
flakiness cannot produce a half-parsed spec. A hosted model is opt-in and
wrapped so failures fall back.

**Q8. Walk me through the race condition and its fix.**
The legacy code did `if (!exists(alias)) put(alias, url)`. Two requests can
both pass the check, both "win", and the second silently overwrites the first
customer's link. The reproduction injects a two-party barrier between the
check and the write, which forces that interleaving every time. The
regression test must fail on the legacy code before it is accepted. The fix
makes the claim atomic (`putIfAbsent`) and adds a unique index so every node
agrees. In the real service the same bug would appear through JPA:
`save()` on an assigned id calls `merge`, which overwrites. `Persistable`
forces an insert so the primary key rejects the duplicate, proven by 8
concurrent requests yielding exactly one 201.

**Q9. Why is MTTR computed from the audit log rather than tracked in memory?**
The audit log is the system of record and survives the process. Computing
MTTR from `INCIDENT_OPENED`/`INCIDENT_RESOLVED` pairs keyed by incident id
means the metric can be recomputed later, by anyone, from the file alone. The
same goes for retry, rollback and gate-failure counts.

**Q10. What would you change for production?**
Durable state (a database-backed store with run resume), asynchronous human
approvals that park and resume the run, dependency-driven scheduling instead
of waves, sandboxed execution of generated code, a shared rate limiter,
connect-time SSRF checks in any component that fetches URLs, and OpenTelemetry
spans per task alongside the audit log.

**Q11. Why store raw client IPs nowhere?**
Analytics only need "same visitor or not", which an HMAC gives without
retaining personal data. A plain hash is not enough: IPv4 has only 2³² values
and is trivially brute-forced. The rate-limit key uses the hash too, so even
in-memory maps hold no IPs, and the smoke test checks the service log.

**Q12. How do you know the tests actually test something?**
Beyond reading them, I mutated the code during the build: removing the
reverse in rollback, re-snapshotting before each retry, and "fixing" the race
with a plain `put`. In each case the corresponding test failed. The
concurrency tests count winners in the database rather than trusting return
values.

**Q13. Aren't your agents just templates?**
In offline mode, yes, deliberately: CI and the three demos must be reproducible.
The same agents run against Claude with one environment variable
(`AGENTIC_LLM=claude`), and `run-request.sh` takes any requirement: Claude
writes the spec, design, code and tests, and the orchestrator compiles and runs
them, scans them, and puts a human in front of the release. The governance does
not change with the model, which is the point: it must hold whatever the model
produces, including when the model is wrong or unavailable (then it fails
closed to offline mode and the request safely stops as a draft).
