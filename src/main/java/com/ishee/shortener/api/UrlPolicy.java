package com.ishee.shortener.api;

import java.net.URI;
import java.util.Locale;

public final class UrlPolicy {
    private UrlPolicy() {}

    public static String validate(String url) {
        if (url == null || url.length() < 8 || url.length() > 2048 || url.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid destination length or characters");
        }
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid destination URL");
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || host == null || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Only absolute HTTP(S) destinations without credentials are allowed");
        }
        String hostname = host.toLowerCase(Locale.ROOT);
        if (hostname.equals("localhost") || hostname.endsWith(".local") || hostname.endsWith(".internal")
                || hostname.endsWith(".localhost") || hostname.indexOf('.') < 0) {
            throw new IllegalArgumentException("Local destinations are not allowed");
        }
        if (hostname.matches("[0-9.]+") || hostname.contains(":")) {
            throw new IllegalArgumentException("IP literal destinations are not allowed");
        }
        int port = parsed.getPort();
        if (port != -1 && port != 80 && port != 443 && port != 8080 && port != 8443) {
            throw new IllegalArgumentException("Unsupported destination port");
        }
        return url;
    }
}
