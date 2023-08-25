package io.github.claudineyns.nostr.relay.types;

public enum HttpMethod {
    GET;

    public static HttpMethod from(final String method) {
        if (method == null) {
            return null;
        }
        for (final HttpMethod m : values()) {
            if (m.name().equalsIgnoreCase(method)) {
                return m;
            }
        }
        return null;
    }
}