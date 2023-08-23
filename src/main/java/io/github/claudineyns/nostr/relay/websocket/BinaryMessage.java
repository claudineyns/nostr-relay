package io.github.claudineyns.nostr.relay.websocket;

public class BinaryMessage extends Message {

    public BinaryMessage(byte[] data) {
        super(data, Message.Type.BINARY);
    }
    
}
