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

