package com.ishee.shortener.api;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class AliasPolicy {
    private static final Pattern ALIAS = Pattern.compile("[a-zA-Z0-9_-]{4,32}");
    private static final Set<String> RESERVED = Set.of("api", "docs", "health", "admin", "actuator");

    private AliasPolicy() {}

    public static String validate(String alias) {
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Alias must be 4-32 letters, digits, '-' or '_'");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Alias is reserved");
        }
        return alias;
    }
}
