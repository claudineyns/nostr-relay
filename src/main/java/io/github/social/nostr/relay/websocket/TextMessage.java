package io.github.social.nostr.relay.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Basic class for Text Messages
 */
public final class TextMessage extends Message {
    public TextMessage(byte[] data) {
        super(Arrays.copyOf(data, data.length), Message.Type.TEXT);
    }

    public TextMessage(final String message) {
        super(message.getBytes(StandardCharsets.UTF_8), Message.Type.TEXT);
    }

    public String getMessage() {
        return new String(getData(), StandardCharsets.UTF_8);
    }
    
}
