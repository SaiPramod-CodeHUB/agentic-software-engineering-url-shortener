package com.agentsdlc.shortener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Verifies 429 + Retry-After through the HTTP layer with a tiny bucket. */
@SpringBootTest(properties = {"shortener.rate-limit.capacity=2", "shortener.rate-limit.window-seconds=10"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private MockHttpServletRequestBuilder create(String ip) {
        return post("/shorten").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/rl\"}")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
    }

    @Test
    void thirdRequestFromSameClientIs429WithRetryAfter() throws Exception {
        mvc.perform(create("198.51.100.1")).andExpect(status().isCreated());
        mvc.perform(create("198.51.100.1")).andExpect(status().isCreated());
        mvc.perform(create("198.51.100.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error").value("rate_limited"));
        mvc.perform(create("198.51.100.2")).andExpect(status().isCreated());
    }
}
