package io.github.social.nostr.relay.websocket;

import io.github.social.nostr.relay.server.WebsocketContext;

public class WebsocketException extends Exception {
    private WebsocketContext context;

    public WebsocketException(final String message) {
        super(message);
    }

    public WebsocketException(final Throwable cause) {
        super(cause);
    }

    public WebsocketException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public WebsocketException setContext(final WebsocketContext context) {
        this.context = context;

        return this;
    }

    public WebsocketContext getContext() {
        return context;
    }
    
}
