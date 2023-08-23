package io.github.claudineyns.nostr.relay.server;

import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.websocket.BinaryMessage;
import io.github.claudineyns.nostr.relay.websocket.TextMessage;
import io.github.claudineyns.nostr.relay.websocket.Websocket;
import io.github.claudineyns.nostr.relay.websocket.WebsocketException;

public class WebsocketHandler implements Websocket {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
    
    @Override
    public void onOpen(final WebsocketContext context) {
        logger.info("[WS] Server ready to accept data.");
    }

    @Override
    public void onClose(final WebsocketContext context) {
        logger.info("[WS] Server gone. Bye.");
    }

    @Override
    public void onMessage(final WebsocketContext context, final TextMessage message) {
        logger.info("[WS] Server received message of type {}", message.getType());
        logger.info("[WS] Data: {}", message.getMessage());
    }

    @Override
    public void onMessage(final WebsocketContext context, final BinaryMessage message) {
        logger.info("[WS] Server received message of type {}", message.getType());
        final StringBuilder raw = new StringBuilder("");
        for(final byte b: message.getData()) {
            raw.append(String.format("%d, ", b));
        }
        logger.info("[WS] Raw data: {}", raw);
    }

    @Override
    public void onError(WebsocketException exception) {
        logger.info("[WS] Server got error.");
    }
    
}
