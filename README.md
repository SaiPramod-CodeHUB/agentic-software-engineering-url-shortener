# Agentic SDLC Orchestrator + URL Shortener

A deterministic, auditable framework that runs software-delivery work as an
explicit task DAG of agents, and a production-style URL shortener that the
agents build on, analyse and fix.

- **Part A: URL shortener** (`com.agentsdlc.shortener`). Spring Boot 3 REST
  service with idempotency keys, per-client rate limiting, SSRF protection,
  TTL expiry, append-only click analytics, unguessable codes and no raw IPs at
  rest.
- **Part B: agentic SDLC orchestrator** (`com.agentsdlc.orchestrator`).
  Requirement normalisation, a validated DAG, parallel waves, pluggable
  entry/exit gates, human approval checkpoints, retries/fallback/saga rollback,
  decision lineage, an append-only audit log, metrics and hash-driven
  re-planning.
- **Three runnable scenarios.** *Greenfield*: build a QR-code feature.
  *Brownfield*: fix a race condition in the existing alias path, then
  demonstrate rollback. *Ambiguous*: "make links smarter" goes through
  clarification and re-planning.

Everything runs **offline**. It needs no network and no API keys at runtime,
because a deterministic "LLM" sits behind an `LlmProvider` seam.

## Architecture

```
                         natural-language request
                                    │
                    ┌───────────────▼────────────────┐
                    │ RequirementNormalizer (LLM seam)│──► NormalizedSpec (DRAFT | READY)
                    └───────────────┬────────────────┘
                                    │ kind → template
                           ┌────────▼────────┐
                           │   Decomposer     │──► TaskGraph (validated DAG, waves)
                           └────────┬────────┘
                                    │
┌───────────────────────────────────▼──────────────────────────────────────────┐
│ Orchestrator                                                                   │
│  for each wave: fan out on bounded pool ──► join                               │
│    per task: deps ok? → ENTRY gates → attempt(s) + backoff → EXIT gates        │
│              → fallback? → success | failure ⇒ saga rollback (reverse) + stop  │
│                                                                                │
│  Gates: destructive-policy · spec-ready · impact-analysis · change-control ·   │
│         human-approval · final-signoff │ required-outputs · secrets-scan ·     │
│         compliance-pii · tests-must-pass                                       │
└───────┬──────────────────┬──────────────────┬─────────────────┬──────────────┘
        │                  │                  │                 │
 ┌──────▼──────┐   ┌───────▼───────┐  ┌───────▼──────┐  ┌───────▼────────┐
 │ StateStore  │   │ AuditLog      │  │ DecisionLog  │  │ Replanner       │
 │ namespaced, │   │ audit.jsonl   │  │ decisions.   │  │ output hashes → │
 │ hashable    │   │ seq, flushed  │  │ jsonl (why)  │  │ downstream scope│
 └─────────────┘   └───────┬───────┘  └──────────────┘  └────────────────┘
                           │
                   ┌───────▼───────┐
                   │ Metrics       │──► metrics.json (counters, stage latency,
                   └───────────────┘     e2e p50/p95, success rate, MTTR)

Agents (Requirements, Design, Implementer, Tester, Docs, Triage, ImpactAnalysis,
Reproduce, RegressionTest, MigrateFix, Refactor, TestDocImprovement, ChangeRecord,
Release) talk only through the StateStore, never to each other.
```

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Line-by-line
explanation: [CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md).

## Prerequisites

- JDK 21. A full JDK is required, not a JRE, because the tester agent
  compiles generated code in-process with `javax.tools`.
- Maven 3.9+.
- `curl` and `bash`, for the scripts.

Dependencies download on the first build. After that, nothing touches the network.

## Build and test

```bash
mvn -B test                 # 75 tests: unit, orchestration, integration (MockMvc), 3 end-to-end scenarios
mvn -B javadoc:javadoc      # doclint=all, failOnWarnings=true
```

## Run the scenarios

```bash
./run-greenfield.sh   # QR feature: spec → design → implement → test ∥ docs → change record → release
./run-brownfield.sh   # alias race: triage → impact analysis → reproduce → regression test → fix → refactor → tests/docs → release,
                      # plus guardrail demos and rollback → safe-stop → recovery
./run-ambiguous.sh    # "make links smarter": DRAFT + questions → human answers once → spec v2 → re-plan → build
```

Each script prints every check and ends with `RESULT SUCCESS` (exit 0) or
`RESULT FAILURE` (exit 1). Evidence goes to `working_tree/<scenario>/`:

| File | Contents |
|---|---|
| `audit.jsonl` | Every run, task, gate, approval, retry, rollback, re-plan and incident event, one JSON object per line with a monotonic `seq` |
| `decisions.jsonl` | Decision lineage: task, decision, rationale, inputs |
| `metrics.json` | Counters, per-stage mean latency, end-to-end p50/p95, success rates, MTTR |
| `SCENARIO_REPORT.md` | Every check with its observed values, plus run summaries |
| `SPEC_*.md`, `DESIGN.md`, `IMPACT_ANALYSIS.md`, `REPRODUCTION.md`, `TEST_REPORT.md`, `CHANGE_RECORD.md`, `RELEASE_NOTES.md` | Agent artifacts |
| `generated/`, `legacy/`, `rollback-demo/` | Generated and fixed code, compiled classes, the simulated database |

## Run the service

```bash
mvn -B -DskipTests package
java -jar target/agentic-sdlc-1.0.0.jar          # http://localhost:8080, H2 file DB in ./data
```

```bash
# create (201); repeat with the same Idempotency-Key -> 200, same code
curl -i -X POST localhost:8080/shorten -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: demo-1' -d '{"url":"https://example.com/docs","ttlSeconds":3600}'
curl -i -X POST localhost:8080/shorten -H 'Content-Type: application/json' \
     -d '{"url":"https://example.com/a","customAlias":"my-link"}'          # 201; again -> 409
curl -i localhost:8080/<code>                                              # 302 (404 unknown, 410 expired)
curl -s localhost:8080/stats/<code>                                        # clicks, unique visitors, referrers
curl -i -X POST localhost:8080/shorten -H 'Content-Type: application/json' \
     -d '{"url":"http://169.254.169.254/latest/meta-data"}'                # 400 SSRF
curl -s localhost:8080/health                                              # {"status":"ok"}
```

`./scripts/smoke-test.sh` runs the whole sequence against the packaged jar,
using a throwaway database, and asserts every status code.

API contract: [docs/openapi.yaml](docs/openapi.yaml). `OpenApiContractTest`
checks it against the controller. Schema:
[`V1__init.sql`](src/main/resources/db/migration/V1__init.sql), managed by
Flyway; Hibernate only validates it.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `shortener.base-url` | `http://localhost:8080` | Prefix for `shortUrl` |
| `shortener.code-length` | `8` | Base62 code length (~47 bits) |
| `shortener.rate-limit.capacity` / `window-seconds` | `20` / `60` | Token bucket per client for `POST /shorten` |
| `shortener.max-ttl-seconds` | `31536000` | Upper bound for `ttlSeconds` |
| `shortener.visitor-hash-key` | env `SHORTENER_VISITOR_HASH_KEY` | HMAC key that pseudonymises client IPs. Set it in every real deployment |

## Offline and optional hosted model

The default `DeterministicLlmProvider` is rule-based and template-backed, so the
same input always gives the same output. To try a hosted model, set
`AGENTIC_LLM=openai` and `OPENAI_API_KEY` (optionally `OPENAI_MODEL`,
`OPENAI_URL`). Calls are wrapped in `FailClosedLlmProvider`, so any error or
empty answer falls back to the offline provider. The requirement normaliser
also rejects malformed model JSON. Scenario checks are written against the
offline provider's output, so a hosted model may legitimately fail them. The
gates still judge its code.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, execution model, decisions and trade-offs
- [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md): plan, validation results, risks, interview Q&A
- [docs/TESTING.md](docs/TESTING.md): test strategy, determinism, and every bug found during the build
- [CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md): every file: what, why, why this way, why not the alternative

## License

MIT, see [LICENSE](LICENSE).
