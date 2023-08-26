package io.github.claudineyns.nostr.relay.specs;

public final class EventKind {
    private EventKind() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static int METADATA = 0;
    public static int TEXT_NOTE = 1;
    public static int DELETION = 5;
    public static int CHANNEL_CREATE = 40;
    public static int CHANNEL_METADATA = 41;
    public static int CHANNEL_MESSAGE = 42;
    public static int CHANNEL_HIDE = 43;
    public static int CHANNEL_MUTE_USER = 44;
}
