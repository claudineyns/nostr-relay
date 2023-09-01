package io.github.social.nostr.relay.specs;

public final class EventKind {
    private EventKind() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static int METADATA = 0;
    public static int TEXT_NOTE = 1;
    public static int CONTACT_LIST = 3;
    public static int ENCRYPTED_DIRECT = 4;
    public static int DELETION = 5;
    public static int REPOST = 6;
    public static int REACTION = 7;
    public static int BADGE_AWARD = 8;
    public static int GENERIC_REPOST = 16;
    public static int CHANNEL_CREATE = 40;
    public static int CHANNEL_METADATA = 41;
    public static int CHANNEL_MESSAGE = 42;
    public static int CHANNEL_HIDE = 43;
    public static int CHANNEL_MUTE_USER = 44;

    public static int CLIENT_AUTH = 22242;
}
