# Code Walkthrough

Every file in the repository, in reading order. For each meaningful block:

- **What:** what the code does.
- **Why:** why it exists and how it is used.
- **Approach:** why it is done this way.
- **Not:** why not the obvious alternative, and the concrete trade-off.

Blocks are named by member (method, field, section) rather than line number,
so the walkthrough stays correct as lines move. Read it alongside the source.

Reading order: build & config → Part A (shortener) → Part B core → state &
audit → gates & approvals → engine → LLM → spec → tools → metrics &
re-planning → agents → scenarios → tests → scripts & packaging.

---

## 1. Build and configuration

### `pom.xml`

**Parent `spring-boot-starter-parent:3.3.4`**
- What: inherits dependency versions and plugin defaults.
- Why: every Spring, Jackson, Hibernate, H2, Flyway and JUnit version comes from one tested bill of materials.
- Approach: the parent POM is the standard Boot pattern.
- Not: hand-pinning each version, where a mismatched Hibernate/Spring pair fails at runtime, not compile time.

**`java.version=21`**
- What: compiles for Java 21.
- Why: records, pattern matching (`instanceof RateLimitedException limited`), text blocks (code templates) and switch expressions are used throughout.
- Approach: the property feeds `maven.compiler.release`, which checks API usage against the real 21 API.
- Not: `source`/`target` alone, which can compile against a newer JDK's API and break on 21.

**Dependencies: web, data-jpa, validation, flyway-core, h2 (runtime)**
- What: the service stack.
- Why: REST controllers, repositories, `@Valid`, versioned migrations and the embedded database.
- Approach: H2 is `runtime` scope, so no code can import H2 classes and the database stays swappable.
- Not: compile-scope H2, which would let H2-specific code creep in.

**`junit-jupiter` + `junit-platform-launcher` at `compile` scope**
- What: JUnit on the main classpath.
- Why: `TesterAgent` (main code) compiles generated tests and runs them with the JUnit Platform Launcher at runtime.
- Approach: this is the documented way to embed the launcher.
- Not: test scope, where the scenarios would work under `mvn test` but crash from `run-*.sh` with `ClassNotFoundException`. The trade-off is a slightly larger runtime classpath.

**`spring-boot-starter-test` (test)**
- What: AssertJ, MockMvc, Spring test context.
- Why: service and orchestration tests.

**`spring-boot-maven-plugin` with `mainClass`**
- What: builds an executable jar of the shortener.
- Why: the smoke test and the README run `java -jar`.
- Approach: the repository has two `main` methods (service and `ScenarioMain`); naming one avoids an ambiguous repackage.
- Not: letting the plugin guess, which fails the build when there are two candidates.

**Surefire `agentic.test.root`**
- What: points scenario tests at `target/test-working-tree`.
- Why: `mvn test` must not overwrite the evidence that `run-*.sh` leaves in `working_tree/`.
- Approach: a system property read by `ScenarioTest`.
- Not: hard-coding a path in the test, which cannot be redirected in CI.

**Javadoc plugin `doclint=all`, `failOnWarnings=true`, `quiet`**
- What: the Javadoc build fails on any warning.
- Why: the acceptance criterion is zero warnings, and a failing build keeps it true over time.
- Not: checking the count by eye once, after which it regresses silently.

### `src/main/resources/application.properties`

**Datasource block (`jdbc:h2:file:./data/shortener`)**
- What: a file-backed embedded database.
- Why: links survive restarts without installing a server.
- Approach: four properties are the only thing to change for Postgres/MySQL; repositories use standard JPA/JPQL.
- Not: in-memory H2, which loses data on restart; or a Docker Postgres, which needs a daemon and network and breaks "runs offline".

**`spring.flyway.enabled=true`, `ddl-auto=validate`, `open-in-view=false`**
- What: Flyway owns the schema; Hibernate only checks entities match it; no lazy loading during view rendering.
- Why: production-style schema control, where DDL is reviewed, versioned and never generated.
- Not: `ddl-auto=update`, which silently alters production tables and cannot express data migrations. Open-in-view hides N+1 queries and holds connections for the whole request.

**`shortener.*` block**
- What: base URL, code length, TTL cap, rate limit, HMAC key.
- Why: bound to `ShortenerProperties`.
- Approach: `visitor-hash-key` reads `SHORTENER_VISITOR_HASH_KEY` and falls back to an obviously named dev value.
- Not: no default at all, which makes the jar fail to start for a quick demo; the name `local-dev-only-key` makes misuse obvious in review.

**`SqlExceptionHelper=OFF`**
- What: silences Hibernate's per-violation ERROR log.
- Why: primary-key violations are this design's *expected* control flow (409 / idempotent replay). See TESTING.md B2.
- Not: leaving it on, which pages on-call for normal traffic. Unexpected SQL errors still propagate as exceptions.

### `src/main/resources/db/migration/V1__init.sql`

**`links`**
- What: `code` primary key, target, timestamps, custom-alias flag.
- Why: the primary key *is* the uniqueness guarantee for aliases and random codes.
- Approach: `TIMESTAMP(6) WITH TIME ZONE` matches Hibernate 6's mapping of `Instant`, so `validate` passes.
- Not: `TIMESTAMP` without a zone, which fails validation and invites timezone bugs.

**`clicks`**
- What: an identity id, a foreign key to `links`, referrer host, visitor hash; indexed by `(code, clicked_at)`.
- Why: append-only analytics, so a redirect inserts here and never updates `links`.
- Approach: the index serves both "count by code" and "recent by code".
- Not: a `click_count` column on `links`, which turns every redirect into a row-lock hot spot and lets analytics corrupt links.

**`idempotency_keys`**
- What: the key as primary key, request hash, code.
- Why: concurrent first requests with the same key are serialised by the primary key.
- Not: a unique index on a nullable column in `links`, which couples link rows to transport concerns.

### `src/test/resources/application-test.properties`
- What: in-memory H2 with `${random.uuid}` in the URL; large rate-limit capacity; test HMAC key.
- Why: each Spring test context gets its own database, so tests never see each other's rows, and the limiter never throttles unrelated tests.
- Approach: a profile file (`@ActiveProfiles("test")`) layers over the main properties.
- Not: a test `application.properties`, which *replaces* the main file on the classpath and silently drops every other setting.

---

## 2. Part A: URL shortener (`com.agentsdlc.shortener`)

### `ShortenerApplication.java`
- **`@SpringBootApplication` + `@EnableConfigurationProperties(ShortenerProperties.class)`**
  - What: bootstraps Spring and binds `shortener.*` to the record.
  - Not: `@ConfigurationPropertiesScan`, which is more magic for one class.
- **Explicit no-arg constructor with Javadoc**
  - What: satisfies doclint's "default constructor" warning.
  - Why: zero Javadoc warnings is an acceptance criterion.
- **`clock()` bean**
  - What: `Clock.systemUTC()`.
  - Why: TTL expiry, click timestamps and rate limiting read time from here, so tests can substitute `MutableClock`.
  - Not: calling `Instant.now()` inline, which would force tests to sleep 60 s to test a 60 s TTL, or be flaky.
- **`codeRandom()` bean**
  - What: `SecureRandom` as a `RandomGenerator`.
  - Why: codes are bearer tokens.
  - Approach: `RandomGenerator` is the Java 17+ common interface, so tests can pass `new Random(42)`.
  - Not: `new SecureRandom()` inside `CodeGenerator`, which cannot be seeded, so the tests would be non-reproducible.

### `config/ShortenerProperties.java`
- What: an immutable record bound from `shortener.*`, with a nested `RateLimit` record.
- Why: typed, validated-at-startup configuration.
- Approach: records get constructor binding, which gives immutability and no setters.
- Not: `@Value` on fields, which scatters configuration and can't be passed to plain constructors in unit tests.

### `domain/Link.java`
- **Fields and `@Table(name="links")`**
  - What: they map `V1__init.sql`.
  - Approach: explicit `@Column` names and lengths, so `validate` checks them.
- **`implements Persistable<String>` + `@Transient isNew` + `@PostPersist/@PostLoad markNotNew()`**
  - What: tells Spring Data whether to `persist` or `merge`.
  - Why: this is the heart of the alias race fix. With an assigned id, `save()` would call `merge`, which *updates* an existing row and silently overwrites another customer's alias. `isNew()==true` forces `persist`, so the primary key rejects the duplicate.
  - Approach: lifecycle callbacks flip the flag after insert or load, so loaded entities are never re-inserted.
  - Not: `existsById()` then `save()`, which is check-then-act and exactly the race. Nor `@Version`, which prevents lost updates on the same row but does not stop a new entity from overwriting.
- **`isExpiredAt(now)`**
  - What: `expiresAt != null && !now.isBefore(expiresAt)`.
  - Why: expiry is inclusive at the boundary, which is tested at exactly +60 s.
  - Not: `now.isAfter(expiresAt)`, which leaves a one-instant window where an expired link still redirects.
- **Protected no-arg constructor**
  - What: required by JPA; hidden from application code.

### `domain/Click.java`
- What: an append-only redirect event with an identity id.
- Why: analytics without touching `links`.
- Approach: stores `visitorHash` (HMAC) and the referrer *host* only, so there is no raw IP, path or query string (which can carry tokens or PII).
- Not: storing the full `Referer`, which leaks things like `?session=` into analytics.

### `domain/IdempotencyRecord.java`
- What: key → (request hash, code), with the same `Persistable` pattern as `Link`.
- Why: the key being the primary key makes "first writer wins" atomic across concurrent requests.
- Not: an in-memory map, which fails across restarts and replicas.

### `domain/LinkRepository.java`, `IdempotencyRepository.java`
- What: plain `JpaRepository` interfaces.
- Why: they are the database seam. They contain nothing vendor-specific.

### `domain/ClickRepository.java`
- **`countByCode`** — derived query.
- **`countUniqueVisitors`** — JPQL `count(distinct visitorHash)`.
- **`referrerBreakdown`** — JPQL group-by returning `Object[]` rows, ordered by count, then name.
  - Why: the name tiebreak makes output deterministic.
- **`findByCodeOrderByClickedAtDescIdDesc(code, Pageable)`** — the latest N clicks.
  - Why: `IdDesc` breaks timestamp ties, since two clicks in the same microsecond must still have a stable order.
- Not: native SQL, which ties analytics to H2's dialect.

### `service/ShortenerException.java`
- What: a runtime exception carrying an `HttpStatus` and a stable `errorCode`, with factory methods `badRequest/notFound/gone/aliasTaken/idempotencyMismatch`.
- Why: services express failures in domain terms; one handler renders them.
- Approach: the status lives on the exception, so there is one place to look.
- Not: one exception class per status plus a mapping table in the handler, which gives two lists to keep in sync. Messages are fixed strings, never client input, to avoid reflection attacks.

### `service/RateLimitedException.java`
- What: a 429 subclass carrying `retryAfterSeconds`.
- Why: the handler needs the value for the `Retry-After` header.

### `service/CodeGenerator.java`
- **`ALPHABET`** — the 62 URL-safe characters.
- **Two constructors, `@Autowired` on the Spring one**
  - Why: Spring needs to know which to use; the explicit-length constructor is for unit tests.
- **Length guard 4..32**
  - Why: fail fast on misconfiguration. 32 matches the column width.
- **`next()`**
  - What: `random.nextInt(62)` per character.
  - Why: `nextInt(bound)` is unbiased.
  - Not: `nextInt() % 62`, which biases toward the first characters. Nor hash-of-URL, which is guessable and gives the same code for the same URL, leaking whether someone else shortened it.

### `service/UrlSafetyValidator.java`
- **Length check (2048)**
  - Why: bounds storage and regex work.
- **`new URI(url)`**
  - What: syntax check.
  - Not: `URL`, which can trigger DNS in `equals`/`hashCode`.
- **Scheme allow-list `http|https`**
  - Why: blocks `javascript:`, `file:`, `ftp:` and `data:`.
  - Not: a deny-list, which is always incomplete.
- **`getRawUserInfo() != null` → reject**
  - Why: `http://trusted.com@evil.com` phishing and parser-confusion tricks.
- **`checkHost`**
  - What: strips a trailing dot; blocks `localhost` and internal suffixes (`.local`, `.internal` including `metadata.google.internal`, `.lan`, `.home.arpa`).
  - Why: these names resolve inside the network.
- **Bracketed IPv6 → `literal()` → `checkAddress`**
- **`DOTTED_QUAD` → `literal()` → `checkAddress`**
- **`NUMERIC_HOST` (decimal, hex or short forms) → reject**
  - Why: `http://2130706433/` and `http://127.1/` are loopback in browsers and curl, and a naive dotted-quad check misses them.
  - Not: trying to normalise every form, which is error-prone. Rejecting non-canonical numeric hosts is simpler and loses nothing legitimate.
- **`literal()` uses `InetAddress.getByName` only on IP literals**
  - Why: for literals it parses without DNS.
- **`checkAddress` + `isOtherReserved`**
  - What: loopback, any-local, link-local (169.254/16, including cloud metadata), site-local (10/8, 172.16/12, 192.168/16), multicast, 0/8, CGNAT 100.64/10, 240/4 plus broadcast, IPv6 ULA fc00::/7. IPv4-mapped IPv6 is parsed by Java as `Inet4Address`, so it is covered too.
- **Class Javadoc on "no DNS"**
  - Why: resolve-then-check is defeated by DNS rebinding and breaks offline operation, so the correct control is at *fetch* time. This service never fetches.

### `service/RateLimiter.java`
- **Token bucket state**
  - What: an immutable `Bucket(tokens, updatedNanos)` per key in a `ConcurrentHashMap`.
- **`acquire`**
  - What: `buckets.compute(key, …)` refills by elapsed time × rate (capped at capacity), then takes one token or computes the wait.
  - Why: `compute` is atomic per key, so two concurrent requests from one client cannot both take the last token.
  - Approach: the wait is converted to whole seconds, at least 1, for `Retry-After`.
  - Not: a fixed window, which allows 2× the limit across a boundary. Nor a sliding log, which stores every timestamp (O(n) memory per client).
- **`evictFullBuckets` when more than 10,000 keys**
  - Why: memory bound. A full bucket is indistinguishable from a new one, so dropping it loses nothing.
- **Time from `Clock`**
  - Why: tests advance time instead of sleeping.
- **Trade-off**
  - Per-instance state means N replicas allow N× the rate. The class is the seam for a Redis or gateway limiter.

### `service/VisitorHasher.java`
- What: HMAC-SHA256 of the client address → 64 hex characters.
- Why: supports unique-visitor counts and rate-limit keys with no stored IP.
- Approach: a keyed hash. A new `Mac` per call because `Mac` is not thread-safe.
- Not: plain SHA-256, which is reversible for IPv4 by enumerating 2³² inputs. Nor a `ThreadLocal<Mac>`, a micro-optimisation not worth its leak risk here.

### `service/LinkService.java`
- **`ALIAS_PATTERN`, `RESERVED_ALIASES`**
  - Why: aliases are URL path segments. Reserved words would shadow `/health`, `/stats` and so on.
- **Constructor builds a `TransactionTemplate`**
  - Why: explicit transaction boundaries per attempt.
- **Records `ShortenCommand`, `ShortenResult`, `RecentClick`, `LinkStats`**
  - Why: immutable DTOs between layers. Jackson serialises records directly.
- **`shorten`**
  1. Validate URL, alias, TTL and key. Everything is rejected before touching the database.
  2. `requestHash = sha256(url\nalias\nttl)`, the canonical body fingerprint for idempotency.
  3. Fast path: an existing key → `replay()`, which returns 200 with the same code, or 422 on a different body.
  4. Loop up to 5 attempts: choose a code (alias or random), then `tx.executeWithoutResult(insert)`.
  5. On `DataIntegrityViolationException` (after rollback): replay the key if a concurrent request won it, else 409 if it was an alias, else retry with a new random code (a collision).
  - Why this order: the idempotency winner must be detected before declaring an alias conflict. Otherwise two same-key requests with an alias would give one 201 and one 409 instead of 201 and 200.
  - Not: `@Transactional` on `shorten`. A constraint violation marks the transaction rollback-only, so you cannot "catch and replay" inside it.
- **`resolve`**
  - What: find or 404 → expired? 410 → insert a `Click` → return the target.
  - Why: insert-only analytics.
  - Not: incrementing a counter on `Link`, which causes lock contention on hot links.
- **`stats`**
  - What: assembles the aggregate queries.
  - Why: still available after expiry, because owners want history.
- **`shortUrl`**
  - What: joins the base URL and the code, tolerating a trailing slash.
- **`insert`**
  - What: `saveAndFlush` of the link, then the idempotency record, in one transaction.
  - Why: flushing surfaces the constraint violation *inside* the template, where it can be caught.
- **`referrerHost`**
  - What: the `Referer` host only, lower-cased and truncated to 255; `direct` if absent; `unknown` if unparsable.
- **`sha256`**
  - What: `HexFormat` hex digest.

### `api/ShortenRequest.java`, `ShortenResponse.java`, `ErrorResponse.java`
- What: records defining the wire format. `@NotBlank url` triggers bean validation.
- Why: the API contract, mirrored in `docs/openapi.yaml`.

### `api/ShortenerController.java`
- **`shorten`**
  - What: rate limit (keyed by the *hashed* address) → service → 201 with `Location` for new links, or 200 for a replay.
  - Why: HTTP semantics. 201 plus `Location` for creation; 200 because nothing new was created on replay.
  - Not: raw IPs as limiter keys, which would hold personal data in memory maps.
- **`health`**
  - What: a static `{"status":"ok"}` liveness probe.
  - Not: Actuator, a whole dependency for one endpoint here.
- **`stats`**
  - What: delegates to `LinkService.stats`.
- **`redirect`**
  - What: 302 with `Location` and `Cache-Control: no-store`.
  - Why: a cached redirect would bypass analytics and TTL expiry.
  - Not: 301, which browsers cache permanently.
- **Route precedence**
  - Spring matches literal paths (`/health`, `/stats/{code}`) before `/{code}`, and reserved aliases prevent shadowing.

### `api/ApiExceptionHandler.java`
- **`handleDomain`**
  - What: maps `ShortenerException` to its status and body, adding `Retry-After` via pattern matching on `RateLimitedException`.
- **`handleBadInput`**
  - What: `MethodArgumentNotValidException` or unreadable JSON → 400 with a fixed message.
  - Why: never echo parser errors, which leak internals and reflect input.

### `docs/openapi.yaml`
- What: an OpenAPI 3.0 description of all four operations, the status codes, the `Idempotency-Key` and `Retry-After` headers, and the schemas.
- Why: the assessment asks for API definitions. `OpenApiContractTest` keeps it honest.
- Not: generating it with springdoc at runtime. That adds a dependency and describes whatever the code does, rather than a contract the code must meet.

---

## 3. Part B core (`orchestrator.core`)

### `RiskTier.java`
- What: `LOW / MEDIUM / HIGH`, with a Javadoc meaning per constant.
- Why: gates key off the tier (HIGH → human, destructive + LOW → blocked).
- Not: a numeric score, which invites arbitrary thresholds. Three named tiers map onto real review policies.

### `RetryPolicy.java`
- **Record `(maxAttempts, initialBackoff, multiplier)` with compact-constructor validation**
  - Why: an invalid policy fails at plan time.
- **`none()`, `exponential(n, d)`**
  - What: named factories for the two common shapes.
- **`backoffBefore(attempt)`**
  - What: `initial × multiplier^(attempt−2)`, zero for attempt 1, capped at `MAX_BACKOFF` (30 s).
  - Why: exponential backoff avoids hammering a struggling dependency; the cap prevents a typo from stalling a pipeline for hours.
  - Not: a fixed delay, which retries too fast under sustained failure or too slowly for blips. No jitter is deliberate: determinism matters more than thundering-herd avoidance inside one orchestrator.

### `Agent.java`
- What: `@FunctionalInterface void execute(TaskContext) throws Exception`.
- Why: the smallest possible contract. Lambdas work for tests and small scenario steps; classes work for real agents.
- Approach: throwing means "attempt failed", and the engine owns what happens next.
- Not: returning a result object, which makes agents choose between exceptions and error values, so both appear.

### `Compensation.java`
- What: `compensate(TaskContext)`.
- Why: saga undo for external side effects.
- Approach: the engine restores the *state* namespace itself. The compensation only reverts the *outside world* (files, DB rows).

### `TaskStatus.java`
- What: nine terminal and intermediate states, and `satisfiesDependents()` (SUCCEEDED, SUCCEEDED_WITH_FALLBACK, REUSED).
- Why: the engine and metrics share one definition of "done enough to build on".
- Not: a boolean `success`, which cannot distinguish BLOCKED from FAILED, or SKIPPED from never considered. Reports and metrics depend on that difference.

### `GraphValidationException.java`
- What: a dedicated unchecked exception for malformed plans.
- Why: tests and callers can tell plan errors from runtime errors.

### `Tags.java`
- What: string constants `release`, `needs-approval`, `tests-must-pass`, `requires-ready-spec`, `requires-impact-analysis`.
- Why: tags are how gates select tasks, so policy attaches to tasks declaratively.
- Not: an enum, which would force every new policy tag into core code.

### `TaskSpec.java`
- **Record fields**
  - What: id, stage, deps, risk, destructive, retry, agent, fallback, compensation, required outputs, tags.
  - Why: everything the engine and gates need to know about a task is declared up front and immutable.
- **Compact constructor**
  - What: validates kebab-case ids, since namespaces starting with `_` are reserved; defaults stage, risk and retry; copies the collections.
  - Not: mutable specs, which can change under a running engine.
- **`hasTag`** — a convenience for gates.
- **`Builder`**
  - What: fluent construction with safe defaults (LOW, no retry).
  - Why: eleven positional record arguments are unreadable and error-prone.

### `TaskGraph.java`
- **Constructor**
  - What: rejects duplicates (`putIfAbsent`) and unknown deps; builds the reverse edges `dependents`; computes waves.
  - Why: everything is validated before a single agent runs.
- **`computeWaves` (Kahn's algorithm)**
  - What: repeatedly takes all zero-indegree tasks as a wave; ids are sorted within a wave; decrements children.
  - Why: waves are the unit of parallelism and of synchronisation.
  - Approach: sorting makes plans and audit logs deterministic. If fewer tasks are placed than exist, the leftovers with positive indegree are exactly the cycle members, and the error names them.
  - Not: DFS topological sort, which gives a single order, not parallel layers, and whose cycle reporting is fiddlier.
- **`downstreamOf`**
  - What: BFS over `dependents`, excluding the roots.
  - Why: the re-planner's invalidation set.

### `TaskContext.java`
- **Fields**
  - What: run id, spec, store, logs, LLM, working directory, attempt, last failure, snapshot, per-attempt artifact list.
- **`put`**
  - What: writes to the *task's own* namespace only.
  - Why: an agent cannot overwrite another agent's outputs, which is the isolation guarantee.
- **`get` / `require`**
  - What: read any namespace. `require` throws with the exact missing key.
  - Why: upstream contracts fail loudly and specifically.
- **`lastFailure()`**
  - Why: lets an agent adapt on retry. The implementer strips a secret after seeing the gate message.
- **`snapshotOf`**
  - What: pre-first-attempt values, for compensations.
- **`writeArtifact`**
  - What: resolves under `workDir`, rejects path traversal (`normalize` + `startsWith`), writes UTF-8, records the artifact for exit-gate scanning, and stores `artifact:<path>` in state.
  - Why: exit gates scan exactly what this attempt produced, and the state hash changes when artifacts change.
  - Not: letting agents write files directly, which escapes scanning and the audit trail.
- **`decide`, `audit`**
  - What: lineage and custom events (e.g. incident open/resolve) attributed to the task.

---

## 4. State, audit, lineage

### `state/StateStore.java`
- **Reserved namespaces**
  - What: `_status`, `_approvals`, `_human`.
  - Why: engine bookkeeping and human input live apart from task outputs, so re-planning can clear task namespaces without touching human work.
- **Storage**
  - What: a `ConcurrentHashMap` of `ConcurrentHashMap`s.
  - Why: thread-safe for parallel waves without a global lock.
- **`put` rejects null**
  - Why: "absent" and "null" must not be two different things.
- **`snapshot` / `restore` / `clear`**
  - What: sorted immutable copies; restoring an empty snapshot removes the namespace.
  - Why: rollback and invalidation.
- **`hash`**
  - What: SHA-256 over sorted entries, each length-prefixed.
  - Why: change detection for re-planning.
  - Approach: sorting makes it independent of insertion order. Length-prefixing prevents `("ab","c")` colliding with `("a","bc")`, and a test proves it.
  - Not: `Map.hashCode()`, which is 32 bits, collision-prone and not stable across implementations.
- **String values only**
  - Not: storing objects, which cannot be hashed, snapshotted or persisted generically.

### `audit/AuditLog.java`
- **`Event` record**
  - What: `seq, ts, runId, type, taskId, data`.
- **Constructor**
  - What: creates parent directories; if the file exists, continues `seq` from its line count; opens the file in append mode.
  - Why: reopening never reuses a sequence number.
- **`record` (synchronized)**
  - What: increments `seq`, sorts the data keys (TreeMap, TESTING.md B4), writes one JSON line, `flush()`es, keeps an in-memory copy.
  - Why: order is defined by `seq` under a lock, not by timestamps. A flush per event means a crash loses at most the line being written.
  - Not: buffered writes, which lose the tail on a crash. `fsync` per event would survive power loss too, but costs milliseconds per event; the trade-off is documented. A database would add a dependency and schema for append-only data.
- **`read`**
  - What: parses the file back into events.
  - Why: metrics and verification can work from the file alone.

### `state/DecisionLog.java`
- What: `Decision(ts, runId, taskId, decision, rationale, inputs)` appended to `decisions.jsonl`.
- Why: lineage (*why*) is separate from audit (*what*), because the readers differ.
- Not: putting rationale into audit events, which bloats the audit trail and mixes the concerns.

---

## 5. Approvals (`orchestrator.approval`)

### `approval/ApprovalRequest.java`, `ApprovalDecision.java`
- What: records carrying checkpoint, risk and summary / approved, approver, reason, time.
- Why: every field is audited, which answers "who approved what, when and why".

### `approval/ApprovalProvider.java`, `ClarificationProvider.java`
- What: functional seams for human input.
- Why: production implementations (Slack, Jira, a web form) plug in here; the engine doesn't change.

### `approval/ScriptedApprovalProvider.java`
- What: approves by default; `reject(task, checkpoint, reason)` scripts a rejection; records every request.
- Why: reproducible "humans". Tests assert who was asked; the destructive-LOW test asserts *nobody* was asked.

### `approval/ScriptedClarificationProvider.java`
- What: returns scripted answers for the asked question ids; counts calls.
- Why: the ambiguous scenario proves the human was asked exactly once.


### `approval/ConsolePrompter.java`, `ConsoleApprovalProvider.java`, `ConsoleClarificationProvider.java`
- What: a real human at the terminal. `ConsolePrompter` pairs each question with its answer under one lock, since parallel tasks could ask at once. The approval provider accepts only `y`/`yes`; anything else, including end of input, is a rejection (fail closed). `--auto-approve` grants and records "auto-approve" as the approver note. The clarification provider skips blank answers, so an under-specified request stays a draft.
- Why: `run-request.sh` lets an interviewer be the human checkpoint live.

---

## 6. Gates (`orchestrator.gate`)

### `Gate.java`
- What: `name`, `phase` (ENTRY/EXIT), `appliesTo(TaskSpec)`, `evaluate(GateContext)`.
- Why: gates choose their own tasks, so the engine is policy-free.
- Not: separate EntryGate/ExitGate interfaces, which duplicate the same four methods; the phase is data.

### `GateResult.java`
- What: a `PASS / BLOCK / REJECT` verdict plus a reason.
- Why: the engine needs to distinguish policy blocks (BLOCKED) from human rejections (REJECTED). Both stop the run, but they are reported differently.
- Not: a boolean, which loses that distinction.

### `GateContext.java`
- What: run id, task, state, working directory, artifacts (empty at entry), audit.
- Why: everything a gate may inspect, and nothing it may mutate by accident, except the approval gate's own records.

### `DestructivePolicyGate.java`
- What: blocks a destructive task declared LOW.
- Why: misclassifying risk is how dangerous work would skip human review, so the combination itself is a policy violation.

### `SpecReadyGate.java`
- What: `requirements/spec.status == READY`.
- Why: stops build work from starting on a DRAFT spec, which is the core of the ambiguous scenario.

### `ImpactAnalysisGate.java`
- What: `IMPACT_ANALYSIS.md` exists and has all four required sections.
- Why: brownfield changes require written blast-radius reasoning first.
- Not: only checking the file exists, which an empty file would satisfy.

### `ChangeControlGate.java`
- What: `change-record/status == APPROVED` for release-tagged tasks.
- Why: the change-management requirement.

### `HumanApprovalGate.java`
- **Two factories**
  - What: `forHighRisk` (HIGH or `needs-approval`, excluding releases) and `finalSignoff` (all releases).
  - Why: releases always get a sign-off regardless of tier, because humans own final quality control. Excluding releases from `forHighRisk` avoids asking twice.
- **`evaluate`**
  - What: audits `APPROVAL_REQUESTED`, asks the provider, audits `APPROVAL_GRANTED/REJECTED` with approver, time and reason, and stores the decision in `_approvals/<task>#<checkpoint>`.
  - Why: downstream agents (change record, release notes) cite the stored decision; the audit trail proves it.

### `ContentScanner.java`
- **`SECRET_RULES`**
  - What: AWS access key, PEM private key, GitHub token, and a generic `key/secret/password/token = "12+ chars"`.
- **`PII_RULES`**
  - What: email, NANP-style phone, IPv4.
  - Approach: the phone regex has lookarounds so ISO dates like `2026-01-01` are not matched.
- **`scan`**
  - What: line by line; rules applied in sorted order.
  - Why: deterministic findings.
- **`Finding.toString`**
  - What: `rule at file:line`, never the matched text.
  - Why: the audit log must not become a second copy of a leaked secret or of PII.

### `SecretsScanGate.java`, `ComplianceGate.java`
- What: exit gates applying the rule sets to the attempt's artifacts.
- Why: a failure fails the *attempt*, so a retry can fix it, as the greenfield implementer does.

### `RequiredOutputsGate.java`
- What: every key in `requiredOutputs` is present in the task's namespace.
- Why: output contracts. Downstream `require()` calls cannot fail mysteriously later.

### `TestsMustPassGate.java`
- What: `tests.run > 0 && tests.failed == 0`.
- Why: "zero tests ran" is treated as failure, because a vacuous green is the classic CI lie.

### `StandardGates.java`
- What: the canonical order: policy → spec → impact → change control → human approval → final sign-off, then the exit gates.
- Why: cheap and automatic checks come first, so humans are never asked about something a policy would block.

---

## 7. Engine (`orchestrator.engine`)

### `Sleeper.java`
- What: `sleep(Duration)`; `real()` is `Thread::sleep`.
- Why: tests record the requested backoffs instead of waiting.

### `RunStatus.java`
- What: `SUCCEEDED / SAFE_STOPPED / ROLLED_BACK`.

### `TaskRecord.java`
- What: per-task result (status, attempts, duration, reason, output hash); `withStatus` for rollback; `executed()`.

### `RunReport.java`
- What: run id, pipeline, status, start, end-to-end duration, records in graph order, halt reason; `task(id)`, `count(status)`, `summary()`.
- Why: scenario checks and metrics read reports; `summary()` is the console table.

### `Orchestrator.java`
- **Fields and `Builder`**
  - What: required collaborators are checked with `requireNonNull`. Parallelism defaults to 4; the sleeper and clock are injectable.
  - Not: a constructor with nine parameters.
- **`run(pipeline, graph)`**
  - What: delegates to the scoped run with every id in scope.
- **`run(pipeline, graph, scope)`**
  - What: generates the run id (`pipeline-run-N`), audits `RUN_STARTED` with scope, waves and LLM name, and measures time with `System.nanoTime()`.
  - Why: nanoTime is monotonic, so durations can't go negative on a clock step. The clock is used only for timestamps.
  - Per wave:
    - Out-of-scope tasks → `reused()`.
    - If already halted → `skip()`.
    - Else collect `toRun`, wrap each in a `Callable`, and `pool.invokeAll`, which is the **join**.
    - Results go into `records`.
    - Then, if not already halted: the first FAILED task sets `failed` and the halt reason and triggers `rollback`; BLOCKED/REJECTED set only the halt reason.
    - The try-with-resources `ExecutorService` (Java 21 `AutoCloseable`) shuts the pool down.
  - After the loop:
    - Derive `RunStatus`.
    - Audit `SAFE_STOP` with the skipped list.
    - `trackIncident`.
    - Audit `RUN_FINISHED`.
    - Return records in graph order.
  - Not: `CompletableFuture` chains per dependency, which give higher throughput but make "stop everything and roll back" racy, because tasks can start while rollback runs.
- **`execute(runId, task, …)`**
  1. Dependency check: current-run record, else stored `_status`. An unsatisfied dependency → SKIPPED with the reason.
  2. Entry gates in order. The first non-pass writes `_status`, audits `TASK_BLOCKED/REJECTED` and returns. No snapshot or attempt happens, so blocked tasks leave no trace in state.
  3. `snapshot` once, stored for rollback.
  4. Attempt loop. Before attempts ≥ 2: audit `TASK_RETRY` with the attempt, backoff and cause, then sleep. Call `attempt()`; `null` means success.
  5. Fallback once, if configured (`FALLBACK_STARTED`).
  6. Failure: restore the snapshot so no partial outputs leak, write `_status=FAILED`, audit `TASK_FAILED`.
  7. Success: hash the namespace, write `_status`, append to `completionOrder`, audit `TASK_SUCCEEDED` with the hash.
- **`attempt(...)`**
  - What: clears the namespace (clean attempt), builds a fresh `TaskContext`, audits `TASK_ATTEMPT`, runs the agent (exception → failure message), then the exit gates (first failure → message).
  - Why: exit gates run *per attempt*, so a retry can repair what a gate rejected.
- **`evaluate(...)`**
  - What: calls the gate and audits `GATE_EVALUATED` (gate, phase, verdict, reason). A gate exception becomes a BLOCK.
  - Why: fail safe.
- **`rollback(...)`**
  - What: copies and reverses `completionOrder` and audits `ROLLBACK_STARTED` with the order. For each task: no compensation → `ROLLBACK_STEP no-compensation-needed`; else compensate → restore the snapshot → `_status=COMPENSATED` → record updated → `ROLLBACK_STEP compensated`. A failing compensation is audited as `ROLLBACK_STEP_FAILED` with "manual intervention required", and rollback continues.
  - Why: reverse *completion* order, not graph order, undoes things in the opposite order they actually happened, which matters when parallel tasks finished in either order.
  - Not: aborting rollback on the first error, which leaves more damage in place.
- **`trackIncident`**
  - What: a ROLLED_BACK run opens `INC-<pipeline>-<n>` (once per pipeline); the next SUCCEEDED run of the same pipeline resolves it.
  - Why: MTTR pairs.
- **`reused` / `skip` / `storedStatus`**
  - What: out-of-scope tasks are REUSED only if they succeeded before. Otherwise they are SKIPPED with an honest reason.
- **`openIncidents()`**
  - What: a copy, for tests and diagnostics.

---

## 8. LLM seam (`orchestrator.llm`)

### `LlmRequest.java`
- What: `purpose`, `system`, `prompt`, `vars`, with `vars` defensively copied.
- Why: `purpose` lets the offline provider route deterministically, and lets a real provider choose prompts. `vars` carries structured inputs such as `feature` and `template`.

### `LlmProvider.java`
- What: `name()`, `complete(LlmRequest)`, nested `LlmException`.
- Why: the only thing agents know about models.

### `DeterministicLlmProvider.java`
- **`complete`**
  - What: a switch on purpose: `normalize`, `triage`, `design`, `docs`, `code`.
  - Why: an unknown purpose throws, so a misrouted call is loud.
- **`ambiguityScore`**
  - What: base 0.15; +0.3 for vague words; +0.3 if there are no domain nouns; +0.2 if under 8 words; −0.2 for digits or "must"; clamped and rounded.
  - Why: a transparent, explainable heuristic for "is this buildable?". "make links smarter" scores 0.95; the clarified version scores 0.25.
  - Not: an opaque classifier, which could not be reasoned about in review or tested for boundaries.
- **`normalize`**
  - What: scores the request plus the answers, detects the feature, and emits a JSON spec (title, kind, feature, goals, acceptance criteria, constraints, score, questions when ambiguous).
- **`detectFeature`**
  - What: keyword rules for `qr-code`, `alias-race-fix` (INCIDENT kind) and `idle-expiry`; otherwise `unknown`.
- **`acceptance`**
  - What: testable criteria per feature.
  - Why: the spec states what the tests will check.
- **`questions`**
  - What: three fixed clarifying questions (meaning, measurable criterion, constraints).
- **`triage`**
  - What: severity, component, hypothesis and keywords from the report text.
- **`design`, `docs`**
  - What: Markdown per feature. The design quotes the spec's first lines as a traceability link.
- **`parseMap` / `write`**
  - What: Jackson helpers. Output is pretty-printed for readable artifacts.

### `CodeTemplates.java`
- **`QR_MAIN` / `QR_TEST`**
  - What: `QrCodeSizer` (byte-mode capacity table for level M, versions 1–10: 14, 26, 42, 62, 84, 106, 122, 152, 180, 213; modules = 17 + 4v) and 4 JUnit tests including the inclusive boundaries 14/15 and 213/214.
- **`IDLE_MAIN` / `IDLE_TEST`**
  - What: `IdleExpiryPolicy` (no clicks for the limit → 410; a click resets the timer) and 3 tests.
- **`ALIAS_LEGACY`**
  - What: the buggy check-then-put registry, with a `faultInjection` hook between the check and the write.
  - Why: the hook is what makes the race reproducible. In production it is a no-op lambda.
- **`ALIAS_REGRESSION_TEST`**
  - What: sets the hook to a two-party `CyclicBarrier`, submits two registrations, asserts exactly one winner and that the winner's target is kept.
  - Why: the barrier *forces* both threads through the check before either writes.
  - Not: looping 10,000 times and hoping, which is slow, flaky and can pass on buggy code.
- **`ALIAS_FIXED`**
  - What: `putIfAbsent(...) == null`, an atomic claim. The pre-check stays only as a fast path.
- **`ALIAS_REFACTORED`**
  - What: same behaviour; extracted `isTaken`/`claim`, clearer field name, explicit `requireNonNull`, which keeps the NPE semantics `ConcurrentHashMap` already had.
- **`ALIAS_EDGE_TEST`**
  - What: 4 edge cases (unknown alias, sequential duplicate, independent aliases, null alias).
- **`BY_KEY` + `get`**
  - What: lookup by `feature/role`; an unknown key throws.
  - Why: generated code is real Java that is compiled and executed, not strings compared by equality. Any provider's output goes through the same compile, test and gate path.

### `FailClosedLlmProvider.java`
- What: tries the primary; on an exception or blank answer, counts a fallback, prints one line to stderr (provider, cause, purpose; never the request or key; at most 3 times), and uses the secondary.
- Why: a model outage degrades to known-good behaviour.
- Not: propagating the error, which would stop every pipeline during a vendor outage.

### `OpenAiLlmProvider.java`
- What: a Chat Completions call via `java.net.http` at temperature 0, with timeouts. Non-200 or missing content → `LlmException`.
- Why: shows the seam works with a real vendor, without an SDK dependency.
- Approach: the key comes from the environment and is never logged. `vars` are appended to the prompt.

### `LlmProviders.java`
- What: `fromEnvironment(env)`. Claude is used only if `AGENTIC_LLM=claude` **and** `ANTHROPIC_API_KEY` is set (model from `ANTHROPIC_MODEL`, default `claude-sonnet-5`); OpenAI only if `AGENTIC_LLM=openai` **and** `OPENAI_API_KEY` is set. Hosted models are always wrapped in `FailClosedLlmProvider`; otherwise the offline provider is used.
- Why: the default is offline, so a key that happens to be in the environment never turns on network calls by itself.

### `AnthropicLlmProvider.java`
- What: a Messages API call (`POST /v1/messages`) via `java.net.http`: headers `x-api-key` and `anthropic-version`, body `model`, `max_tokens`, `system`, one user message. Text blocks in `content` are concatenated. Non-200, or no text, raises `LlmException`.
- Why: Claude as a real planner and coder behind the same seam, with no SDK dependency.
- Approach: `.proxy(ProxySelector.getDefault())` so corporate proxies and `https.proxyHost` work; 120 s timeout for long code answers; the key is never logged.
- Not: the vendor SDK, a heavy dependency for one HTTP call, and it would hide the request shape we test against a fake server.

### `LlmPrompts.java`
- What: `system(request)` = the agent's instruction plus a strict **output contract** per purpose (normalize/triage → JSON only with named keys; code/main → one compilable Java 21 file in `generated.<feature>`, JDK only; code/test → one JUnit 5 class, 3+ tests; design/docs → Markdown). `user(request)` = the prompt plus the structured inputs. `packageFor(feature)` makes a legal package name.
- Why: every model answer is parsed or compiled by code, so the shape must be specified exactly. The offline provider ignores these contracts.
- Not: letting each agent write its own free-form prompt, which scatters the contracts and makes model swaps risky.

### `LlmOutput.java`
- What: `stripFences(text)` returns the body of the first Markdown code fence, else the trimmed text.
- Why: models often wrap JSON or Java in ``` fences even when told not to; cleaning is cheaper and safer than failing the attempt. Used by the normaliser, triage, implementer and tester.

---

## 9. Spec (`orchestrator.spec`)

### `NormalizedSpec.java`
- **Record fields**
  - What: version, request, title, kind, feature, goals, criteria, constraints, score, questions, clarifications, `Status {DRAFT, READY}`.
- **`toMarkdown`**
  - What: a DRAFT gets a `# DRAFT (DO NOT BUILD) — …` banner; a READY spec gets `# Spec vN`. Sections for goals, criteria, constraints, questions and clarifications, with maps sorted.
  - Why: humans skim artifacts, so the banner makes a draft unmistakable.

### `RequirementNormalizer.java`
- **`normalize`**
  - What: builds the request (clarifications as sorted JSON), asks the model, parses via `fromJson`. On *any* parse or validation failure it re-asks the deterministic provider.
  - Why: fail closed. Malformed model output never becomes a half-populated spec.
- **`fromJson`**
  - What: requires `ambiguityScore` and `feature`. **Computes the status itself**: DRAFT if the score is at or above the threshold or the feature is unknown.
  - Why: the model proposes and code decides, so a model cannot talk the pipeline into building an ambiguous request.
- **`list` / `writeJson`**
  - What: helpers.

### `Decomposer.java`
- **`SPEC_TASK`, `CHANGE_TASK`, `PRODUCT_PACKAGE`**
  - What: the ids and paths gates and agents rely on, defined once.
- **`Bindings`**
  - What: scenario parameters (repo root, version, change id, incident id, the credential-injection flag).
- **`decompose`**
  - What: kind → template.
- **`feature(b)`**
  - What: 8 tasks. requirements ∥ repo-inventory → design (needs a READY spec) → implement (MEDIUM, 3 attempts, compensation deletes the generated file) → test (tests-must-pass) ∥ docs → change-record (needs-approval) → release (HIGH, release tag).
- **`incident(b)`**
  - What: 10 tasks. requirements → triage → impact-analysis → reproduce → regression-test → migrate-fix (HIGH, destructive, requires impact analysis, tests-must-pass, compensation) → refactor (requires impact analysis, tests-must-pass, compensation) → improve-tests-docs (tests-must-pass) → change-record → release.
  - Why sequential: refactor and improve both rebuild the same registry file, so running them in parallel would race on it.
- Not: letting an LLM invent the DAG. Templates make every pipeline of a kind governed identically, and the spec still drives *what* is built.

---

## 10. Tools (`orchestrator.tools`)

### `JavaToolchain.java`
- **`hostClasspath`**
  - What: prefers `surefire.test.class.path`, else `java.class.path`.
  - Why: under Surefire, `java.class.path` is a single manifest-only booter jar, and javac would not find JUnit.
- **`compile`**
  - What: `ToolProvider.getSystemJavaCompiler()`, options `--release 21 -proc:none -Xlint:all`. Diagnostics are captured.
  - Why: warnings are reported but do not fail the attempt, because a hosted model's harmless warning should not reject correct code; tests and gates decide what ships. `-proc:none` stops stray annotation processors running.
  - A null compiler means a JRE, reported clearly.
- **`runTests`**
  - What: a fresh `URLClassLoader` over the output directory (parent = this class's loader, which holds JUnit); the TCCL is swapped for engine discovery; JUnit Platform `LauncherFactory` with a `SummaryGeneratingListener`; returns counts and failure messages; restores the TCCL and closes the loader.
  - Why: each run gets a fresh loader, so legacy, fixed and refactored versions of `AliasRegistry`, including their static hook, never collide.
  - Not: forking `mvn test`, which is seconds slower per task, needs network for plugin resolution on a cold cache, and means parsing console output.
- **`javaSources`, `join`**
  - What: helpers.

### `SourceIndex.java`
- **`scan(repoRoot, sourceRoot)`**
  - What: indexes `.java` files by simple name (TreeMap for order); a missing directory gives an empty index.
- **`mentioning(keywords)`**
  - What: a case-insensitive content match.
- **`references` / `referencedBy`**
  - What: whole-word simple-name matches between indexed classes; one hop.
- **`endpoints`**
  - What: the `@(Get|Post|…)Mapping("…")` regex.
- **`tables`**
  - What: the `@Table(name="…")` regex.
- **`callPaths`**
  - What: DFS from entry points within the affected set; cycle-safe via the path set; emits maximal paths.
- Why regex: no dependencies, milliseconds to run, and the heuristic is written in the report. Over-reporting, such as names in comments, is the safe direction.
- Not: JavaParser or Spoon. More precise, but a heavy dependency, and still not semantic across reflection or Spring wiring.

---

## 11. Metrics and re-planning

### `metrics/MetricsReport.java`
- What: a record serialised as `metrics.json`: runs, run/task success rates, counters, per-stage mean ms, end-to-end p50/p95, resolved incidents, MTTR.

### `metrics/Metrics.java`
- **`compute`**
  - What: counters from the audit events (attempts, retries, fallbacks, compensations, safe-stops, re-plans, gate evaluations and failures, approvals granted and rejected, per-status task events). Task success rate counts only *attempted* terminal statuses. Stage latency averages executed tasks by `stage`. End-to-end percentiles come from run durations.
  - Why: skipped and reused tasks aren't attempts; counting them would inflate or deflate the rate.
- **`percentile`**
  - What: nearest rank, `ceil(p/100·n)`.
  - Why: always returns an observed value, and is simple to verify by hand.
  - Not: interpolation, which invents latencies no run had.
- **`mttr`**
  - What: pairs `INCIDENT_OPENED`/`RESOLVED` by `incidentId`, taking the first open per id; the mean of the durations.
  - Why: computable from the audit file alone.
- **`write`**
  - What: indented JSON.

### `replan/Replanner.java`
- **`Plan`**
  - What: changed, invalidated and preserved sets, sorted and unmodifiable (TESTING.md B5).
- **`fingerprint`**
  - What: task id → namespace hash.
- **`replan(before, audit, runId)`**
  - What: diffs the hashes → the changed roots → `graph.downstreamOf(changed)` → clears those namespaces and sets `_status=PENDING` → audits `REPLAN` → returns the plan.
  - Why: invalidation is explicit and audited *before* anything re-runs. Reserved namespaces, where human input lives, are never cleared.
  - Not: timestamps ("modified after"), which are wrong under clock skew and when content is rewritten unchanged. Nor "re-run everything", which redoes approved work and re-asks humans.

---

## 12. Agents (`orchestrator.agents`)

All agents read inputs with `ctx.require(...)`, which fails loudly on a broken upstream contract, write outputs with `ctx.put(...)` or `ctx.writeArtifact(...)`, and record *why* with `ctx.decide(...)`.

### `AgentSupport.java` (package-private)
- What: a shared Jackson mapper; `toJson`, `jsonList`, `jsonMap`; `qualifiedClassName` (package + first class, via regex) and `sourcePath`.
- Why: generated code decides its own file location, so the agents work with any provider's output, not just the templates.

### `RequirementsAgent.java`
- What: reads `_human/request` and optional `_human/clarifications`; version 1 or 2; normalises; writes `SPEC_vN[_DRAFT].md` and the `spec.*` keys; decision rationale cites the score against the threshold.

### `RepoInventoryAgent.java`
- What: counts main and test classes; lists the shortener's endpoints.
- Why: an input to design, with *no* dependency on requirements, so re-planning can preserve it (that is the proof in the ambiguous scenario).

### `DesignAgent.java`
- What: LLM `design` with the spec as prompt → `DESIGN.md` and `design.md`.

### `ImplementerAgent.java`
- **Code generation**
  - What: LLM `code` with template `<feature>/main`.
- **Credential injection on attempt 1 when enabled**
  - What: appends `api_key = "sk_demo_…"` as a debug comment.
  - Why: scripted fault injection proving the secrets gate works. The value is assembled at runtime so this repository's own source never matches the scanner.
- **Location**
  - What: the qualified name decides the path under `generated/src/main/java/`.
- **Decision on retry**
  - What: if the last failure mentions `secrets-scan`, the decision "regenerated without the flagged credential" is recorded.

### `TesterAgent.java`
- What: generates the test (`<feature>/test`), writes it, deletes and recreates `generated/classes`, compiles main and test together, runs the test class in-process, publishes `tests.*`, writes `TEST_REPORT.md`. A compile failure throws with the diagnostics, which makes the attempt retryable.
- **`deleteRecursively`**
  - What: package-visible helper, reused by `LegacyModule`.

### `DocsAgent.java`
- What: LLM `docs` → `FEATURE_README.md`. Runs in parallel with the tester.

### `ChangeRecordAgent.java`
- What: reads its own `_approvals/change-record#approval` (written by the approval gate *before* the agent ran). Status is APPROVED only if that decision was APPROVED. Writes `CHANGE_RECORD.md` with evidence (`tests.run`/`failed` from the given tasks) and a rollback plan.
- Why: the status mirrors a real human decision; the agent cannot approve itself.

### `ReleaseAgent.java`
- What: requires the final sign-off record; writes `RELEASE_NOTES.md` citing the change id and sign-off. If `triage/incident.id` exists, audits `INCIDENT_RESOLVED`.
- Why: closes the incident pair for MTTR at the moment the fix ships.

### `LegacyModule.java`
- What: path constants for the brownfield module; `compileAndTest(tests, outName)` compiles the registry and the named tests into `legacy/<outName>` and runs them; `compile`; `read`.
- Why: a distinct output directory per stage (`classes-legacy`, `-fixed`, `-refactored`, `-improved`) keeps the evidence of each stage.

### `TriageAgent.java`
- What: LLM `triage` → severity, component, keywords; `TRIAGE.md`; audits `INCIDENT_OPENED` (the MTTR clock starts here).

### `ImpactAnalysisAgent.java`
- What: keywords from triage → `SourceIndex` over `src/main/java/<productPackage>`. Direct matches plus one hop of dependencies; endpoints and tables per class; entry points are the classes owning endpoints; call paths; covering tests from `src/test/java/<productPackage>`. Writes `IMPACT_ANALYSIS.md` with the four gate-required sections and the method stated.
- Why scoped: TESTING.md B3. The first version scanned the whole repository and reported the orchestrator's own classes.

### `ReproduceAgent.java`
- What: compiles only the legacy registry; loads it in a fresh `URLClassLoader`; sets the static `faultInjection` field via reflection to a barrier; runs two registrations; counts winners; reads the surviving target; writes `REPRODUCTION.md`. **Throws if it does not reproduce.**
- Why: no fix without a confirmed bug.
- Not: calling the legacy class directly, which is impossible: it is compiled at runtime and not on the build classpath.

### `RegressionTestAgent.java`
- What: writes the barrier-based test and runs it against legacy code. **Throws if the test passes**, since a test that passes on buggy code does not detect the bug.

### `MigrateFixAgent.java`
- What: backs up the original source into state; writes the fixed registry and `V2__unique_alias_index.sql`; runs the regression test (it must now pass, enforced by the gate).
- **`compensation()`**
  - What: restores the backup byte for byte and deletes the migration file.
- Why the DB index as well as `putIfAbsent`: the in-process fix protects one node; a unique index protects every node and writer.

### `RefactorAgent.java`
- What: backs up the source; writes the refactored version; the regression test must stay green; the compensation restores the backup.

### `TestDocImprovementAgent.java`
- What: adds the edge-case test; replaces the stale doc sentence ("last write wins") with the correct one; runs regression + edge tests (5 total, up from 1); records whether the doc was stale.
- Why: covers the "test/documentation improvement" task type, with measurable before/after.

---

## 13. Scenarios (`orchestrator.scenario`)

### `ScenarioEnvironment.java`
- **Constructor**
  - What: wipes `outRoot/<name>` (reproducible evidence); creates the audit and decision logs, the LLM (`fromEnvironment`), and the orchestrator with `StandardGates.all(...)` and parallelism 4.
- **`run(...)` → `record`**
  - What: keeps every `RunReport` for metrics; prints `summary()`.
- **`check(name, passed, detail)`**
  - What: a named assertion that is printed as `[PASS]`/`[FAIL]` (ASCII, TESTING.md B6) and kept for the report.
  - Why: scenario claims are executable, and the evidence file lists the observed values.
- **`finish()`**
  - What: scans `audit.jsonl` and `decisions.jsonl` with the PII rules (a compliance check on the logs themselves), computes and writes `metrics.json`, writes `SCENARIO_REPORT.md`, prints `RESULT SUCCESS|FAILURE`.
- **Accessors, `events(type, task)`, `read(relative)`**
  - What: helpers used by the checks.
- **`close()`**
  - What: closes the audit stream (try-with-resources).

### `ScenarioResult.java`
- What: name, success, checks, metrics, working directory. Returned to `ScenarioMain` and `ScenarioTest`.

### `GreenfieldScenario.java`
- What: puts `REQUEST` into `_human`; normalises (the planning step) → `Decomposer.decompose` with the credential-injection flag; audits `PLAN_CREATED`; runs.
- Checks:
  - The run succeeded.
  - READY spec for qr-code.
  - test ∥ docs in one wave.
  - `secrets-scan` BLOCK on implement.
  - Two attempts, and clean source containing `class QrCodeSizer`.
  - ≥ 4 tests run, 0 failed.
  - Change record approved.
  - Final sign-off granted.
  - Plus the log compliance check in `finish()`.

### `BrownfieldScenario.java`
- **`incidentPipeline`**
  - What: seeds the legacy module (buggy registry, stale doc); puts the report into `_human`; normalises (INCIDENT) → the incident DAG; runs.
  - Checks: impact analysis content and bounded blast radius; the impact gate passed *before* migrate-fix; reproduction winners > 1; regression fails on legacy and passes after the fix; approval of the HIGH destructive fix; refactor green and actually refactored (`claim(` present); tests 1 → 5 and the doc corrected; sign-off; incident opened by triage and resolved by release.
- **`guardrails`**
  - What: two tiny pipelines. (1) destructive + LOW → BLOCKED, downstream SKIPPED, and the agent never ran. (2) a HIGH destructive purge scripted to be rejected → REJECTED, the upstream snapshot kept, downstream SKIPPED, one `APPROVAL_REJECTED`.
- **`rollbackAndRecovery`**
  - What: writes a small properties-file "database"; `backfillPipeline` with backup → migrate (HIGH, destructive, compensation resets `schema_version` from the stored previous value) → backfill (attempt 1 writes a row then throws; attempt 2 completes; compensation removes the listed rows) → canary (fails three times while the flag is set) → verify.
  - Checks: backoffs 10 ms and 20 ms; `ROLLBACK_STARTED` order `[backfill-aliases, apply-migration, backup-db]`; statuses COMPENSATED; verify SKIPPED; DB equal to the original map; backfill namespace empty after rollback (snapshot-once). Then clear the flag, re-run: SUCCEEDED, schema 2 with the new rows, and `INCIDENT_RESOLVED` for the pipeline (MTTR).
- **`readDb` / `writeDb`**
  - What: sorted `k=v` lines, so the file is diffable and deterministic.
  - Not: `java.util.Properties.store`, which writes a timestamp comment and makes the file non-deterministic.

### `AmbiguousScenario.java`
- What: `REQUEST = "make links smarter"`; `ANSWERS` for q1–q3.
- Steps:
  1. Run 1 → DRAFT and SAFE_STOPPED (design BLOCKED, the rest SKIPPED).
  2. Parse `spec.questions`; ask the human once; store the answers in `_human`; audit `HUMAN_CLARIFICATION`.
  3. `fingerprint`, then run 2 with scope `{requirements}` → spec v2 READY.
  4. `replan` → assert the exact changed, invalidated and preserved sets.
  5. Run 3 with scope `invalidated` → SUCCEEDED, with repo-inventory and requirements REUSED.
- Checks: `IdleExpiryPolicy` generated and tested; `human.calls()==1` and exactly one repo-inventory attempt across all runs; final sign-off.

### `RequestScenario.java`
- What: any requirement through the **feature** template: normalise (planning audit event) → run → if the spec is DRAFT, ask the human once, store the answers in `_human`, re-run `requirements`, `replan`, run the invalidated scope. Checks: spec READY, pipeline completed, generated tests ran and passed, human sign-off recorded. Prints the path of the generated source.
- Why the feature template only: the incident template is wired to the alias-race demo fixture; a live incident would need its own reproduction harness.
- Boundary: the generated code is a standalone tested component, not merged into the service.

### `ScenarioMain.java`
- What: `<greenfield|brownfield|ambiguous> [outDir] [repoRoot]` runs a fixed scenario; `request [--auto-approve] "<requirement>"` wires `ConsoleApprovalProvider`, `ConsoleClarificationProvider` and `LlmProviders.fromEnvironment()` into `RequestScenario`. `System.exit(0|1|2)`.
- Why: shell scripts and CI can gate on the exit code.

---

## 14. Tests

### `shortener/MutableClock.java`, `TestClockConfig.java`
- What: a hand-advanced `Clock`; `@TestConfiguration` with `@Primary` beans for the clock and `Random(42)`.
- Why: TTL and rate-limit time are advanced, never slept. Codes are reproducible.

### `shortener/ServiceUnitTest.java`
- What: code determinism, uniqueness and length guard; parameterised SSRF accept/reject lists; token-bucket burst, exact `Retry-After` (capacity 3 per 60 s → 20 s), per-client isolation, refill; HMAC properties.

### `shortener/ShortenerApiIntegrationTest.java`
- What: `@SpringBootTest` + `@AutoConfigureMockMvc` + test profile + test clock.
- Tests: the happy path (Location header, referrer host, `no-store`, stats counts); idempotency 201/200/422; 404/410 with stats surviving expiry; six 400 cases; 409 with the original untouched; `race()` helper (fixed pool, start latch, 8 threads) for alias (exactly one 201, one DB row) and idempotency (one distinct code); health, plus a JDBC check that stored hashes are 64 characters and exclude the IP.
- Why JDBC in the assertions: it verifies what is actually persisted, not what the service claims.

### `shortener/RateLimitIntegrationTest.java`
- What: capacity 2 per 10 s via `@SpringBootTest(properties=…)`; the third request → 429, `Retry-After: 5`; another IP → 201.

### `shortener/OpenApiContractTest.java`
- What: loads `docs/openapi.yaml` with SnakeYAML (already on the classpath via Boot); collects `METHOD path` pairs; compares them with `RequestMappingHandlerMapping` entries from `com.agentsdlc` handlers.

### `orchestrator/Harness.java`
- What: a fixed clock, a recording sleeper, scripted approvals, a temp directory, the standard gates (overridable); `events(type, task)`.

### `orchestrator/HostedLlmTest.java`
- What: starts a JDK `HttpServer` on 127.0.0.1 as a fake Messages API and checks the request headers and body, multi-block text parsing, HTTP 401 → `LlmException` → fail-closed fallback with the counter incremented, opt-in selection, fence stripping and package naming.
- Why: proves the Claude path without a key or network.

### `orchestrator/RequestScenarioTest.java`
- What: a `FakeModel` answers like a hosted model (fenced JSON, fenced Java, a feature name it chose). Four tests: clear request built with 3 passing generated tests; vague request → human asked once → re-plan → built; human rejects the final sign-off → safe stop with zero rollbacks; offline provider refuses an unknown request (blocked after 2 attempts).

### `orchestrator/TaskGraphTest.java`, `OrchestratorTest.java`, `GatesTest.java`, `ReplannerTest.java`, `MetricsTest.java`, `NormalizerAndLlmTest.java`, `StateAndAuditTest.java`, `ScenarioTest.java`
- What: see the table in [docs/TESTING.md](docs/TESTING.md). Techniques worth noting:
  - `OrchestratorTest.tasksInAWaveRunConcurrently…` proves real parallelism with a `CyclicBarrier(2)`: sequential execution would time out.
  - `snapshotIsTakenOnce…` distinguishes a pre-first-attempt snapshot from a per-attempt one by seeding a baseline value.
  - `GatesTest` builds secret and PII fixtures by string concatenation, so the test sources themselves never trip scanners (including GitHub's).
  - `StateAndAuditTest` writes from 50 parallel threads, reopens the log, and checks that `seq` is exactly 1..51.
  - `ScenarioTest` runs the real scenarios quietly (output to a discarded stream) into `target/`.

---

## 15. Scripts and packaging

### `scripts/run-scenario.sh`
- What: `set -euo pipefail`; `mvn -q -DskipTests compile dependency:build-classpath` (runtime scope into `target/classpath.txt`); `java -cp target/classes:$(cat …) ScenarioMain <name> working_tree .`.
- Why: a plain JVM classpath, so the in-process compiler sees every dependency.
- Not: `mvn exec:java`, which runs in Maven's isolated class loader with the wrong `java.class.path`, so `javac` would not find JUnit.

### `run-request.sh`
- What: builds, then runs `ScenarioMain request "$@"`, so stdin stays attached to the terminal for live approvals and answers. Usage text explains `AGENTIC_LLM=claude` and `ANTHROPIC_API_KEY`.

### `run-greenfield.sh`, `run-brownfield.sh`, `run-ambiguous.sh`
- What: one-line `exec` wrappers.
- Why: they match the documented commands; the logic lives in one script.

### `scripts/smoke-test.sh`
- What: packages; boots the jar on a free port with a temporary H2 file and matching `base-url`; waits for `/health`; `expect` helper; runs 201 → 200 same code → 302 target → stats click → 400 SSRF → 404 → 200 health; greps the service log for client IPs, excluding the JVM's own `JAVA_TOOL_OPTIONS` banner (TESTING.md B7); a `trap` kills the process and deletes the temp directory; the exit code reflects any failure.

### `.github/workflows/ci.yml`
- What: on push and PR: checkout, Temurin 21 with Maven cache, `mvn -B test`, `mvn -B javadoc:javadoc`.
- Why: the acceptance criteria run on every change. No secrets are needed because the runtime is offline.

### `.gitignore`
- What: `target/`, `data/`, `working_tree/`, logs, IDE and OS files.
- Why: evidence and databases are regenerated, never committed.

### `LICENSE`
- What: MIT.
