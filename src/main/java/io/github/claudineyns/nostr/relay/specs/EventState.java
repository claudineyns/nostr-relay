package io.github.claudineyns.nostr.relay.specs;

public enum EventState {

    REGULAR, REPLACEABLE, EPHEMERAL, PARAMETERIZED_REPLACEABLE;

    static final int METADATA = 0;
    static final int TEXT_NOTE = 1;
    static final int CONTACTS = 3;
    static final int DELETION = 5;

    public static EventState byKind(final int n) {
        if ((10000 <= n && n < 20000) || (n == METADATA) || (n == CONTACTS)) {
            return REPLACEABLE;
        }

        if ((1000 <= n && n < 10000) || (n == TEXT_NOTE) || (n == DELETION) ) {
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
