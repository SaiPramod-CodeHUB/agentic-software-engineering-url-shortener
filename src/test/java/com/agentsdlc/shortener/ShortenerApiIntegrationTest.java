package com.agentsdlc.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentsdlc.shortener.service.LinkService;
import com.agentsdlc.shortener.service.LinkService.ShortenCommand;
import com.agentsdlc.shortener.service.ShortenerException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end tests through the real HTTP layer, JPA and Flyway schema (in-memory H2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class ShortenerApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private MutableClock clock;
    @Autowired
    private LinkService linkService;
    @Autowired
    private JdbcTemplate jdbc;

    private MvcResult shorten(String body, String idemKey) throws Exception {
        var req = post("/shorten").contentType(MediaType.APPLICATION_JSON).content(body);
        if (idemKey != null) {
            req.header("Idempotency-Key", idemKey);
        }
        return mvc.perform(req).andReturn();
    }

    private String codeOf(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString()).get("code").asText();
    }

    @Test
    void createRedirectAndStatsHappyPath() throws Exception {
        MvcResult created = shorten("{\"url\":\"https://example.com/docs\"}", null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String code = body.get("code").asText();
        assertThat(body.get("created").asBoolean()).isTrue();
        assertThat(body.get("shortUrl").asText()).isEqualTo("http://localhost:8080/" + code);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("http://localhost:8080/" + code);

        mvc.perform(get("/" + code).header("Referer", "https://news.example.org/a/b?c=d"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(get("/stats/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.uniqueVisitors").value(1))
                .andExpect(jsonPath("$.referrers['news.example.org']").value(1))
                .andExpect(jsonPath("$.referrers.direct").value(1))
                .andExpect(jsonPath("$.recentClicks.length()").value(2));
    }

    @Test
    void idempotencyKeyReplaysSameCodeAndRejectsDifferentBody() throws Exception {
        String bodyJson = "{\"url\":\"https://example.com/idem\"}";
        MvcResult first = shorten(bodyJson, "key-123");
        MvcResult replay = shorten(bodyJson, "key-123");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(codeOf(replay)).isEqualTo(codeOf(first));
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("created").asBoolean()).isFalse();

        MvcResult mismatch = shorten("{\"url\":\"https://example.com/other\"}", "key-123");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void unknownCodeIs404AndExpiredLinkIs410ButStatsSurvive() throws Exception {
        mvc.perform(get("/doesNotExist")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/stats/doesNotExist")).andExpect(status().isNotFound());

        String code = codeOf(shorten("{\"url\":\"https://example.com/ttl\",\"ttlSeconds\":60}", null));
        mvc.perform(get("/" + code)).andExpect(status().isFound());
        clock.advance(Duration.ofSeconds(60));
        mvc.perform(get("/" + code)).andExpect(status().isGone()).andExpect(jsonPath("$.error").value("expired"));
        mvc.perform(get("/stats/" + code)).andExpect(status().isOk()).andExpect(jsonPath("$.totalClicks").value(1));
    }

    @Test
    void invalidInputIs400() throws Exception {
        assertThat(shorten("{\"url\":\"http://169.254.169.254/latest\"}", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(shorten("{\"url\":\"\"}", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(shorten("{not json", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(shorten("{\"url\":\"https://example.com\",\"customAlias\":\"a b\"}", null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(shorten("{\"url\":\"https://example.com\",\"customAlias\":\"stats\"}", null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(shorten("{\"url\":\"https://example.com\",\"ttlSeconds\":0}", null)
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void customAliasIsHonouredAndDuplicateIs409() throws Exception {
        MvcResult first = shorten("{\"url\":\"https://example.com/a\",\"customAlias\":\"my-link\"}", null);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(codeOf(first)).isEqualTo("my-link");
        MvcResult dup = shorten("{\"url\":\"https://example.com/b\",\"customAlias\":\"my-link\"}", null);
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        // The original link must be untouched (the pre-fix bug silently overwrote it).
        mvc.perform(get("/my-link")).andExpect(header().string("Location", "https://example.com/a"));
    }

    @Test
    void concurrentSameAliasHasExactlyOneWinner() throws Exception {
        int threads = 8;
        List<Integer> outcomes = race(threads, i -> () -> {
            try {
                linkService.shorten(new ShortenCommand("https://example.com/race/" + i, "race-alias", null, null));
                return 201;
            } catch (ShortenerException e) {
                return e.status().value();
            }
        });
        assertThat(outcomes).containsOnly(201, 409);
        assertThat(outcomes.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from links where code = 'race-alias'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameIdempotencyKeyYieldsOneCode() throws Exception {
        List<String> codes = race(8, i -> () -> linkService
                .shorten(new ShortenCommand("https://example.com/idem-race", null, null, "race-key")).code());
        assertThat(codes).hasSize(8);
        assertThat(codes.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    void healthIsOkAndNoRawIpIsPersisted() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        String code = codeOf(shorten("{\"url\":\"https://example.com/pii\"}", null));
        mvc.perform(get("/" + code).with(r -> {
            r.setRemoteAddr("203.0.113.77");
            return r;
        })).andExpect(status().isFound());
        List<String> hashes = jdbc.queryForList("select visitor_hash from clicks where code = ?", String.class, code);
        assertThat(hashes).hasSize(1).allSatisfy(h -> assertThat(h).hasSize(64).doesNotContain("203.0.113.77"));
    }

    private static <T> List<T> race(int threads, IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> body = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return body.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
