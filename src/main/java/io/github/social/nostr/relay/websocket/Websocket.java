package io.github.social.nostr.relay.websocket;

import io.github.social.nostr.relay.server.WebsocketContext;

/**
 * Basic interface for websocket events
 */
public interface Websocket {
    byte onServerStartup();
    byte onServerShutdown();

    byte onOpen(final WebsocketContext context);
    byte onClose(final WebsocketContext context);
    byte onMessage(final WebsocketContext context, TextMessage message);
    byte onMessage(final WebsocketContext context, BinaryMessage message);
    byte onError(WebsocketException exception);
}
