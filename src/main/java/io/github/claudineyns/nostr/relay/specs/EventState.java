package io.github.claudineyns.nostr.relay.specs;

public enum EventState {

    REGULAR, REPLACEABLE, EPHEMERAL, PARAMETERIZED_REPLACEABLE;

    public static EventState byKind(final int n) {
        if ((10000 <= n && n < 20000) || (n == 0) || (n == 1) || (n == 3)) {
            return REPLACEABLE;
        }

        if (1000 <= n && n < 10000) {
            return REGULAR;
        }

        if (20000 <= n && n < 30000) {
            return EPHEMERAL;
        }

        if (30000 <= n && n < 40000) {
            return PARAMETERIZED_REPLACEABLE;
        }

        return EPHEMERAL;
    }
    
}
