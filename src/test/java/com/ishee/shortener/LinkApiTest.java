package com.ishee.shortener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:api_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class LinkApiTest {
    @Autowired MockMvc client;
    @Autowired ObjectMapper json;

    @Test void createRedirectAndCountClicks() throws Exception {
        String alias = "a" + UUID.randomUUID().toString().substring(0, 8);
        String request = "{\"url\":\"https://example.com/article\",\"customAlias\":\"" + alias + "\"}";
        client.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(alias));
        client.perform(get("/" + alias)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/article"));
        client.perform(get("/api/v1/links/" + alias + "/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clicks").value(1));
    }

    @Test void duplicateAliasAndDisable() throws Exception {
        String alias = "a" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"url\":\"https://example.com\",\"customAlias\":\"" + alias + "\"}";
        client.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        client.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        client.perform(delete("/api/v1/links/" + alias)).andExpect(status().isNoContent());
        client.perform(get("/" + alias)).andExpect(status().isNotFound());
    }

    @Test void rejectsUnsafeDestinationsAndExpiredLinks() throws Exception {
        for (String url : new String[] {"file:///etc/passwd", "http://localhost", "http://127.0.0.1", "https://example.local"}) {
            String body = json.writeValueAsString(java.util.Map.of("url", url));
            client.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity());
        }
        client.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void idempotencyReplaysAndRejectsConflictingPayload() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"url\":\"https://example.net\"}";
        client.perform(post("/api/v1/links").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        client.perform(post("/api/v1/links").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        client.perform(post("/api/v1/links").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://example.org\"}"))
                .andExpect(status().isConflict());
    }

    @Test void healthIsReady() throws Exception {
        client.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test void aliasPolicyRejectsReservedValue() {
        assertThrows(IllegalArgumentException.class, () -> com.ishee.shortener.api.AliasPolicy.validate("admin"));
    }
}
