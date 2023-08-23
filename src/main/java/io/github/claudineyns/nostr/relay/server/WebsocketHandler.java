package io.github.claudineyns.nostr.relay.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import io.github.claudineyns.nostr.relay.specs.EventData;
import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.websocket.BinaryMessage;
import io.github.claudineyns.nostr.relay.websocket.TextMessage;
import io.github.claudineyns.nostr.relay.websocket.Websocket;
import io.github.claudineyns.nostr.relay.websocket.WebsocketException;

public class WebsocketHandler implements Websocket {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
    private final File directory = new File("/var/nostr/data/");
    
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
        final JsonArray nostrMessage;
        try {
            nostrMessage = gson.fromJson(message.getMessage(), JsonArray.class);
        } catch(JsonParseException failure) {
            logger.warning("[Nostr] could not parse message");
            return;
        }

        if( nostrMessage.isEmpty() ) {
            logger.warning("[Nostr] Empty message received.");
        }

        final String messageType = nostrMessage.get(0).getAsString();

        switch(messageType) {
            case "EVENT":
                this.handleEvent(context, nostrMessage, gson);
                break;
            default:
                logger.warning("[Nostr] Message type {} not supported yet.", messageType);
        }
    }

    @Override
    public void onMessage(final WebsocketContext context, final BinaryMessage message) {
        logger.info("[WS] Server received message of type {}", message.getType());
    }

    @Override
    public void onError(WebsocketException exception) {
        logger.info("[WS] Server got error.");
    }

    private void handleEvent(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        logger.info("[Nostr] Parsing EVENT");

        final String eventJson = nostrMessage.get(1).toString();
        final EventData event = gson.fromJson(eventJson, EventData.class);

        logger.info("[Nostr] [Event]\nID:{}\nPublic Key:{}\nKind:{}\nCreated At:{}\nContent:{}\nSignature:{}",
            event.getEventId(),
            event.getPublicKey(),
            event.getKind(),
            event.getCreatedAt(),
            event.getContent(),
            event.getSignature()
        );

        final List<Object> response = new ArrayList<>();
        response.add("OK");
        response.add(event.getEventId());

        final File eventsDb = new File(directory, "/events");
        eventsDb.mkdirs();

        final File eventsFile = new File(eventsDb, event.getEventId()+".json");
        try (final OutputStream eventRecord = new FileOutputStream(eventsFile)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.warning("[Nostr] [Persistence] Event saved");

            response.add(Boolean.TRUE);
            response.add("");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] Error: {}", failure.getMessage());

            response.add(Boolean.FALSE);
            response.add("error: Development in progress.");
        }

        logger.info("[Nostr] Event parsed");

        final String clientData = gson.toJson(response);
        logger.info("[Nostr] send client response: {}", clientData);

        context.broadcast(clientData);
    }
    
}
