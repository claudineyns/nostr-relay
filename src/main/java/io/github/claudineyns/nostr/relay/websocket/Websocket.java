package io.github.claudineyns.nostr.relay.websocket;

import io.github.claudineyns.nostr.relay.server.WebsocketContext;

/**
 * Basic interface for websocket events
 */
public interface Websocket {
    void onOpen(final WebsocketContext context);
    void onClose(final WebsocketContext context);
    void onMessage(final WebsocketContext context, TextMessage message);
    void onMessage(final WebsocketContext context, BinaryMessage message);
    void onError(WebsocketException exception);
}
