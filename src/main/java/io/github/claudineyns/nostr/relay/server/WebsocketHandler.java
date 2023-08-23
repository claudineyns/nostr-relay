package io.github.claudineyns.nostr.relay.server;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.claudineyns.nostr.relay.specs.EventData;
import io.github.claudineyns.nostr.relay.specs.MessageType;
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
        logger.info("[WS] Parsing data");

        final Gson gson = new GsonBuilder().create();
        final JsonArray nostrMessage = gson.fromJson(message.getMessage(), JsonArray.class);
        final MessageType nostrMessageType = MessageType.valueOf(nostrMessage.get(0).getAsString());

        logger.info("[Nostr] Message Type: {}", nostrMessageType);

        if( ! MessageType.EVENT.equals(nostrMessageType) ) {
            return;
        }

        logger.info("[Nostr] Parsing event");

        final List<EventData> events = new ArrayList<>();

        for(int i = 1; i < nostrMessage.size(); ++i) {
            final JsonObject object = nostrMessage.get(i).getAsJsonObject();
            logger.info("[Nostr] Json Object: {}", object);

            final EventData event = gson.fromJson(gson.toJson(object.getAsString()), EventData.class);
            events.add(event);

            logger.info("[Nostr] [Event]\nID:{}\nPublic Key:{}\nKind:{}\nCreated At:{}\nContent:{}\nSignature:{}",
                event.getEventId(),
                event.getPublicKey(),
                event.getKind(),
                // event.getTags(),
                event.getCreatedAt(),
                event.getContent(),
                event.getSignature()
            );
        }

        logger.info("[Nostr] Event parsed");
        
    }

    @Override
    public void onMessage(final WebsocketContext context, final BinaryMessage message) {
        logger.info("[WS] Server received message of type {}", message.getType());
    }

    @Override
    public void onError(WebsocketException exception) {
        logger.info("[WS] Server got error.");
    }
    
}
