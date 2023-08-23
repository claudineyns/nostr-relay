package io.github.claudineyns.nostr.relay.websocket;

/**
 * Basic class for websocket messages
 */
public abstract class Message {
    public static enum Type { TEXT, BINARY,; }

    private final byte[] data;
    private final Type type;

    Message(final byte[] data, final Type type) {
        this.data = data;
        this.type = type;
    }

    public byte[] getData() {
        return data;
    }

    public Type getType() {
        return type;
    }

}
