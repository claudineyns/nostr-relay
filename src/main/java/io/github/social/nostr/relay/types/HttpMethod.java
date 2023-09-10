package io.github.social.nostr.relay.types;

public enum HttpMethod {
    OPTIONS, GET, POST;

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