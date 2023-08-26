package io.github.social.nostr.relay.server;

import java.util.UUID;

/**
 * Basic class for acessing websocket context
 */
public abstract class WebsocketContext {
    private final UUID contextID = UUID.randomUUID();

    public UUID getContextID() {
        return contextID;
    }
    
    private boolean connected = false;

    void connect() {
        this.connected = true;
    }

    synchronized void disconnect() {
        this.connected = false;
    }

    public final synchronized boolean isConnected() {
        return connected;
    }

    abstract byte broadcast(String message);
    
}
