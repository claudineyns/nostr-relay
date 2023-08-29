package io.github.social.nostr.relay.specs;

import java.util.Arrays;

public enum EventState {
    UNKNOWN, REGULAR, REPLACEABLE, EPHEMERAL, PARAMETERIZED_REPLACEABLE;

    public static EventState byKind(final int n) {
        if ((1000 <= n && n < 10000) || Arrays.asList(
                EventKind.TEXT_NOTE,
                EventKind.DELETION,
                EventKind.REACTION,
                EventKind.REPOST,
                EventKind.GENERIC_REPOST,
                EventKind.CHANNEL_CREATE,
                EventKind.CHANNEL_METADATA,
                EventKind.CHANNEL_MESSAGE,
                EventKind.CHANNEL_HIDE,
                EventKind.CHANNEL_MUTE_USER
            ).contains(n)) {
            return REGULAR;
        }

        if ((10000 <= n && n < 20000) || Arrays.asList(EventKind.METADATA, EventKind.CONTACT_LIST).contains(n)) {
            return REPLACEABLE;
        }

        if (20000 <= n && n < 30000) {
            return EPHEMERAL;
        }

        if (30000 <= n && n < 40000) {
            return PARAMETERIZED_REPLACEABLE;
        }

        return UNKNOWN;
    }
    
}
