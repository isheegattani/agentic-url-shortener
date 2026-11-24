package com.ishee.shortener.api;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class LinkController {
    private static final Logger log = LoggerFactory.getLogger(LinkController.class);
    private final LinkService links;
    private final JdbcTemplate jdbc;

    @Value("${SHORTENER_BASE_URL:http://localhost:8080}")
    private String baseUrl;

    public LinkController(LinkService links, JdbcTemplate jdbc) {
        this.links = links;
        this.jdbc = jdbc;
    }

    public record CreateRequest(String url, String customAlias, OffsetDateTime expiresAt) {}

    @PostMapping("/api/v1/links")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        try {
            LinkService.CreatedLink created = links.create(request.url(), request.customAlias(), request.expiresAt(), key);
            Map<String, Object> stored = created.link();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", stored.get("CODE"));
            body.put("short_url", baseUrl + "/" + stored.get("CODE"));
            body.put("original_url", stored.get("DESTINATION"));
            body.put("created_at", stored.get("CREATED_AT"));
            body.put("expires_at", stored.get("EXPIRES_AT"));
            return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getMessage());
        }
    }

    @GetMapping("/api/v1/links/{code}/stats")
    public Map<String, Object> stats(@PathVariable String code) {
        return links.stats(code);
    }

    @DeleteMapping("/api/v1/links/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable String code) {
        links.disable(code);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ok");
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String destination = links.destination(code);
        try {
            links.recordClick(code);
        } catch (RuntimeException error) {
            log.warn("Click analytics unavailable for {}", code);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(destination)).build();
    }
}
