# Testing

`mvn -B test` runs **83 tests** in about 20 seconds, with no network access.
JUnit counts each parameterised case separately.

| Layer | Class | Tests | What it proves |
|---|---|---|---|
| Service unit | `ServiceUnitTest` | 30 | Base62 codes are reproducible with a fixed seed and have no collisions in 1,000 draws. 5 URLs are accepted and 22 SSRF/malformed URLs rejected (loopback, RFC 1918, link-local and metadata, CGNAT, IPv6 ULA, IPv4-mapped IPv6, decimal/hex/short IPv4, credentials, non-http schemes). Token bucket burst → 429 with exact `Retry-After` → refill. The HMAC visitor hash is keyed and never contains the IP |
| Service integration | `ShortenerApiIntegrationTest` | 8 | Full stack: MockMvc → controller → service → JPA → Flyway schema on H2. Covers 201/200 idempotent replay/422, 302 + stats with referrer host, 404, 410 after TTL (clock advanced, not slept), 400 cases, 409 alias with the original untouched, **8 concurrent same-alias requests → exactly one winner**, **8 concurrent same-key requests → one code**, `/health`, and no raw IP persisted |
| Service integration | `RateLimitIntegrationTest` | 1 | 429 + `Retry-After: 5` through HTTP; other clients unaffected |
| Contract | `OpenApiContractTest` | 1 | `docs/openapi.yaml` operations match the controller's actual routes exactly |
| Orchestration | `TaskGraphTest` | 4 | Deterministic waves, eager rejection of duplicates, unknown deps, cycles (names the cycle) and bad ids, downstream closure, backoff maths and cap |
| Orchestration | `OrchestratorTest` | 11 | Real parallelism (barrier that only concurrent tasks pass), retry backoff schedule, fallback, reverse-order compensation + SKIPPED downstream + incident, **snapshot-once**, human rejection → safe-stop without rollback, destructive-LOW block before any attempt or approval request, output contract, crashing gate ⇒ block, scoped run reuse, incident resolution on recovery |
| Orchestration | `GatesTest` | 5 | Secrets (AWS key, PEM, generic credential; env-var lookup is clean), PII (email, IPv4, phone; findings never echo the value), change control, tests-must-pass (zero tests fails), impact-analysis sections, spec-ready |
| Orchestration | `ReplannerTest` | 2 | Only the downstream closure is invalidated; unrelated and human-provided state survives; the plan is audited; no change → nothing invalidated |
| Orchestration | `MetricsTest` | 2 | Nearest-rank percentiles; MTTR paired by incident id; success rate counts only attempted tasks; stage means |
| Orchestration | `NormalizerAndLlmTest` | 5 | DRAFT + questions for vague input; clarified → READY with testable criteria; kind classification; **malformed model output fails closed**; provider selection is offline by default, and a failing hosted model falls back |
| Orchestration | `StateAndAuditTest` | 3 | Order-independent, length-prefixed hashing; snapshot/restore; 1,000 concurrent writes; audit `seq` strictly 1..n across 50 concurrent writers and a reopen; keys sorted |
| End-to-end | `ScenarioTest` | 3 | Runs the three scenarios exactly as the scripts do (into `target/test-working-tree`) and asserts every named check |
| Hosted model | `HostedLlmTest` | 4 | Claude provider against a local fake Messages API: request shape and headers, multi-block text, HTTP error → fail closed with a visible fallback, opt-in selection, fence stripping, package naming |
| End-to-end | `RequestScenarioTest` | 4 | The any-requirement pipeline with a scripted model that answers in code fences: clear request built and tested; vague request → human asked once → re-plan → built; human rejects sign-off → safe stop, no rollback; offline mode refuses to build an unknown request |

Outside `mvn test`:

- `./run-greenfield.sh`, `./run-brownfield.sh`, `./run-ambiguous.sh`: the
  same scenarios as CLI programs, exiting 0 only if every check passes.
- `./scripts/smoke-test.sh`: boots the packaged jar and curls
  201 → 200 (same code) → 302 → stats → 400 SSRF → 404 → health, then checks
  the service log contains no client IP.
- `mvn -B javadoc:javadoc`: `doclint=all`, `failOnWarnings=true`.

## Strategy per layer

- **Pure logic** (validator, limiter, graph, metrics, hashing) gets fast unit
  tests with table-style inputs. The SSRF list is parameterised, so adding a
  bypass means adding a row.
- **Persistence and concurrency** get integration tests against the real
  schema. Mocks cannot show that the primary key, not the code, resolves an
  alias race, so those tests start 8 threads behind a latch and count
  winners in the database.
- **Orchestration** tests drive the engine with lambda agents. Each test
  builds the smallest DAG that exhibits one behaviour and asserts on both the
  `RunReport` and the audit events, because the audit trail is itself a
  requirement.
- **Scenarios** are the acceptance tests. They exercise real code
  generation, real `javac`, real JUnit and real file side effects, and
  encode every claim as a named check that also lands in
  `SCENARIO_REPORT.md`.
- **Mutation checks** (done manually during the build) confirmed that the
  tests detect deliberate breakage. Removing the reverse in rollback,
  re-snapshotting before each retry, and "fixing" the race with a plain `put`
  each made the corresponding test fail.

## Determinism

| Source of non-determinism | Control |
|---|---|
| Wall clock | `Clock` injected everywhere. `MutableClock` advances TTL and rate-limit time in tests; orchestration tests use `Clock.fixed`. No test asserts on elapsed time |
| Sleeping | Retry backoff goes through `Sleeper`. Tests record the requested durations and never sleep |
| Randomness | Code generator takes a `RandomGenerator`: `SecureRandom` in production, `Random(42)` in tests |
| Thread interleaving | Races are *forced* with barriers (the regression test and reproduction), not hoped for with loops |
| LLM output | `DeterministicLlmProvider`: same input, same output |
| Map iteration order | Waves sort ids; audit data keys are sorted; the re-plan sets are sorted |
| Shared state across tests | Each Spring context gets its own in-memory H2 (`${random.uuid}` URL); orchestrator tests use `@TempDir` |
| Network | None at runtime. Hosted providers (Claude, OpenAI) are only built when explicitly enabled; their tests use a local fake server |

Metric *values* such as latency and MTTR depend on the machine. Tests assert
on their relationships (p95 ≥ p50, MTTR present), never their magnitudes.

## Bugs found during the build

Each bug was found by running something, not by inspection alone, except where noted.

### B1: seeded test random was silently ignored
- **Symptom:** found in review before the first test run. The test
  configuration's `@Primary` `Random(42)` bean would not have reached
  `CodeGenerator`.
- **Diagnosis:** `CodeGenerator` injected `@Qualifier("codeRandom")`.
- **Root cause:** a qualifier selects by bean name and bypasses `@Primary`,
  so tests would still have used `SecureRandom`, making codes
  non-reproducible.
- **Fix:** inject `RandomGenerator` by type. Production has exactly one
  bean, and the test's `@Primary` bean overrides it.
- **Prevention:** `ServiceUnitTest` asserts that two generators with the same
  seed produce identical sequences.

### B2: expected conflicts logged at ERROR
- **Symptom:** the first integration run printed
  `ERROR SqlExceptionHelper: Unique index or primary key violation` for every
  409 and idempotent replay.
- **Diagnosis:** Hibernate logs every constraint violation before Spring
  translates it.
- **Root cause:** the design uses the primary key as the arbiter, so these
  violations are normal control flow, but Hibernate cannot know that.
- **Fix:** `logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper=OFF`,
  with a comment. Unexpected SQL failures still propagate as exceptions and
  are logged by the web layer.
- **Prevention:** the comment in `application.properties` records the reason,
  so nobody "fixes" it back.

### B3: impact analysis reported 51 classes, including the orchestrator itself
- **Symptom:** the first brownfield run passed, but `IMPACT_ANALYSIS.md`
  listed agents, gates and scenario classes as affected by an alias bug.
- **Diagnosis:** triage returned the keyword `register`, which matches
  `findAndRegisterModules` and similar. The scan covered all of
  `src/main/java`, including framework code whose templates mention "alias".
- **Root cause:** an unscoped scan combined with an over-generic keyword.
  The existing check only verified that expected classes were *present*,
  not that unexpected ones were *absent*.
- **Fix:** scope the analysis to the product package
  (`Decomposer.PRODUCT_PACKAGE`) and drop the generic keyword. The result is
  18 product classes, 4 endpoints and 3 tables.
- **Prevention:** a new scenario check bounds the blast radius (≤ 20
  classes, no `Agent`/`Orchestrator` classes).

### B4: audit lines were not byte-stable between runs
- **Symptom:** while inspecting `working_tree/ambiguous/audit.jsonl`, the
  `data` keys appeared in a different order from one run to the next
  (`waves` before `scope`, then after).
- **Diagnosis:** events are built with `Map.of(...)`.
- **Root cause:** `Map.of` iteration order is randomised per JVM, so
  identical events serialised differently, which breaks diffing and
  golden-file comparison of evidence.
- **Fix:** `AuditLog.record` stores a `TreeMap` copy, so keys are sorted.
- **Prevention:** `StateAndAuditTest` asserts sorted key order in the file.

### B5: re-plan output unordered
- **Symptom:** `Plan[... invalidated=[change-record, design, test, docs, release, implement] ...]`
  was printed in scenario output.
- **Root cause:** `Set.copyOf` discards the `TreeSet` ordering.
- **Fix:** sorted, unmodifiable sets (`Collections.unmodifiableSortedSet`).
- **Prevention:** `ReplannerTest` uses `containsExactly` (order-sensitive).

### B6: console showed `?` instead of the dash
- **Symptom:** scenario check lines printed `[PASS] name ? detail`.
- **Root cause:** the sandbox JVM's stdout charset was ASCII (POSIX
  locale), so `—` could not be encoded.
- **Fix:** console output uses ASCII `-`. Markdown files, which are
  written as UTF-8 explicitly, keep typographic characters.
- **Prevention:** console strings are restricted to ASCII.

### B7: smoke test flagged a raw IP in the service log (false positive)
- **Symptom:** `FAIL service log contains a raw client IP`, even though every
  HTTP check passed.
- **Diagnosis:** grepping the log showed a single hit on line 1:
  `Picked up JAVA_TOOL_OPTIONS: ... -Dhttps.proxyHost=127.0.0.1`.
- **Root cause:** the JVM prints its own banner to stderr when that variable
  is set, and this environment sets it for its proxy. It is not service
  output.
- **Fix:** the check skips that JVM banner line. Every service line is still
  checked.
- **Prevention:** the comment in `scripts/smoke-test.sh` explains the
  exclusion, so it is not broadened.
