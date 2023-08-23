package io.github.claudineyns.nostr.relay.server;

import java.util.UUID;

/**
 * Basic class for acessing websocket context
 */
public abstract class WebsocketContext {
    private final UUID contextID = UUID.randomUUID();
    private boolean connected = false;

    public UUID getContextID() {
        return contextID;
    }

    void connect() {
        this.connected = true;
    }

    synchronized void disconnect() {
        this.connected = false;
    }

    public final synchronized boolean isConnected() {
        return connected;
    }

    abstract void broadcast();
    
}
