package com.ishee.shortener.api;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LinkService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public LinkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CreatedLink(Map<String, Object> link, boolean replayed) {}

    @Transactional
    public CreatedLink create(String url, String alias, OffsetDateTime expiresAt, String key) {
        UrlPolicy.validate(url);
        if (alias != null) AliasPolicy.validate(alias);
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        if (key != null) {
            List<Map<String, Object>> found = jdbc.queryForList("SELECT * FROM links WHERE idempotency_key=?", key);
            if (!found.isEmpty()) return repeat(found.getFirst(), url, expiresAt);
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = alias == null ? generateCode() : alias.toLowerCase(Locale.ROOT);
            try {
                jdbc.update("INSERT INTO links(code,destination,created_at,expires_at,idempotency_key) VALUES (?,?,CURRENT_TIMESTAMP,?,?)",
                        code, url, expiresAt, key);
                return new CreatedLink(get(code), false);
            } catch (DuplicateKeyException duplicate) {
                if (key != null) {
                    List<Map<String, Object>> found = jdbc.queryForList("SELECT * FROM links WHERE idempotency_key=?", key);
                    if (!found.isEmpty()) return repeat(found.getFirst(), url, expiresAt);
                }
                if (alias != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias already exists");
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a short code");
    }

    private CreatedLink repeat(Map<String, Object> record, String url, OffsetDateTime expiry) {
        OffsetDateTime stored = (OffsetDateTime) record.get("EXPIRES_AT");
        boolean sameExpiry = expiry == null ? stored == null : stored != null && expiry.isEqual(stored);
        if (!url.equals(record.get("DESTINATION")) || !sameExpiry) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key used with different parameters");
        }
        return new CreatedLink(get((String) record.get("CODE")), true);
    }

    private String generateCode() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Map<String, Object> get(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM links WHERE code=?", code);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short link not found");
        return rows.getFirst();
    }

    public String destination(String code) {
        Map<String, Object> link = get(code);
        if (Boolean.TRUE.equals(link.get("DISABLED"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short link disabled");
        Object expiry = link.get("EXPIRES_AT");
        if (expiry instanceof OffsetDateTime date && !date.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.GONE, "Short link expired");
        }
        return (String) link.get("DESTINATION");
    }

    @Transactional
    public void recordClick(String code) {
        jdbc.update("UPDATE links SET clicks=clicks+1 WHERE code=?", code);
        jdbc.update("INSERT INTO clicks(code,clicked_at) VALUES (?, CURRENT_TIMESTAMP)", code);
    }

    public Map<String, Object> stats(String code) {
        Map<String, Object> link = get(code);
        List<Map<String, Object>> days = jdbc.queryForList(
                "SELECT CAST(clicked_at AS DATE) AS day, COUNT(*) AS clicks FROM clicks WHERE code=? GROUP BY CAST(clicked_at AS DATE) ORDER BY day DESC LIMIT 30", code);
        return Map.of("code", code, "clicks", link.get("CLICKS"), "disabled", link.get("DISABLED"), "daily_clicks", days);
    }

    public void disable(String code) {
        if (jdbc.update("UPDATE links SET disabled=TRUE WHERE code=? AND disabled=FALSE", code) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active short link not found");
        }
    }
}
