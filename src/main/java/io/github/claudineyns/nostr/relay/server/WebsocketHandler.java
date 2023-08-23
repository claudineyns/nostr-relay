package io.github.claudineyns.nostr.relay.server;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
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

    private ExecutorService eventBroadcaster = Executors.newCachedThreadPool();

    private final Map<String, Collection<JsonObject>> subscriptions = new ConcurrentHashMap<>();

    private final Map<String, EventData> ephemeralEvents = new ConcurrentHashMap<>();

    @Override
    public byte onOpen(final WebsocketContext context) {
        return logger.info("[WS] Server ready to accept data.");
    }

    @Override
    public byte onClose(final WebsocketContext context) {
        return logger.info("[WS] Client gone. Bye.");
    }

    @Override
    public byte onMessage(final WebsocketContext context, final TextMessage message) {
        logger.info("[WS] Server received message of type {}\n{}", message.getType(), message.getMessage());

        final List<String> notice = new ArrayList<>();
        notice.add("NOTICE");

        final Gson gson = new GsonBuilder().create();
        final JsonArray nostrMessage;
        try {
            nostrMessage = gson.fromJson(message.getMessage(), JsonArray.class);
        } catch(JsonParseException failure) {
            notice.add("error: could not parse data.");
            context.broadcast(gson.toJson(notice));

            return logger.warning("[Nostr] could not parse message: {}", message.getMessage());
        }

        logger.info("[Nostr] Message parsed.");

        if( nostrMessage.isEmpty() ) {
            notice.add("warning: empty message.");
            context.broadcast(gson.toJson(notice));

            return logger.warning("[Nostr] Empty message received.");
        }

        final String messageType = nostrMessage.get(0).getAsString();

        switch(messageType) {
            case "EVENT":
                return this.handleEvent(context, nostrMessage, gson);
            case "REQ":
                return this.handleSubscriptionRequest(context, nostrMessage, gson);
            case "CLOSE":
                return this.handleSubscriptionRemoval(context, nostrMessage, gson);
            default:
                return logger.warning("[Nostr] Message not supported yet\n{}", message.getMessage());
        }

    }

    @Override
    public byte onMessage(final WebsocketContext context, final BinaryMessage message) {
        return logger.info("[WS] Server received message of type {}.", message.getType());
    }

    @Override
    public byte onError(WebsocketException exception) {
        return logger.info("[WS] Server got error.");
    }

    private byte handleEvent(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final JsonObject eventJson;
        final String eventRawJson;
        final EventData event;
        try {
            eventJson = nostrMessage.get(1).getAsJsonObject();
            eventRawJson = eventJson.toString();
            event = gson.fromJson(eventRawJson, EventData.class);
        } catch(Exception failure) {
            return logger.info(
                "[Nostr] [Message] could not parse event\n{}: {}",
                failure.getClass().getCanonicalName(),
                failure.getMessage());
        }

        //TODO: Implementar NIP-09 (Event Deletion): https://github.com/nostr-protocol/nips/blob/master/09.md

        logger.info("[Nostr] [Message] event received: {}.", event.getEventId());

        final List<Object> response = new ArrayList<>();
        response.add("OK");
        response.add(event.getEventId());

        if( ! "1a7c9d8ac8a9f50d255573dbe1bacd511677d288a0ba5e2332ae4c15e407f29f".equals(event.getPublicKey())) {
            response.addAll(Arrays.asList(Boolean.FALSE, "blocked: development"));

            return context.broadcast(gson.toJson(response));
        }

        /*
         * Saving event
         */

        final File eventDB = new File(directory, "/events/"+event.getEventId());

        final String responseText;
        if( EventData.State.REGULAR.equals(event.getState()) ) {
            if( eventDB.exists() ) {
                responseText = "duplicate: event has already been registered.";
            } else {
                responseText = persistEvent(eventRawJson, event, eventDB);
            }
        } else if( EventData.State.REPLACEABLE.equals(event.getState()) ) {
            responseText = persistEvent(eventRawJson, event, eventDB);
        } else if( EventData.State.EPHEMERAL.equals(event.getState()) ) {
            responseText = cacheEvent(eventRawJson, event);
        } else {
            responseText = "error: Could not update database";
        }

        Optional.ofNullable(responseText)
        .ifPresentOrElse(
            info -> response.addAll(Arrays.asList(Boolean.FALSE, info)),
            () -> response.addAll(Arrays.asList(Boolean.TRUE, ""))
        );

        return context.broadcast(gson.toJson(response));
    }

    private byte handleSubscriptionRequest(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription created: {}", subscriptionKey);

        final Collection<JsonObject> filter = new ConcurrentLinkedQueue<>();

        for(int i = 2; i < nostrMessage.size(); ++i) {
            final JsonObject entry = nostrMessage.get(i).getAsJsonObject();
            final String json = entry.toString();
            logger.info("[Nostr] [Message] filter received:\n{}", json);
            filter.add(entry);
        }

        subscriptions.put(subscriptionKey, filter);

        final List<String> notice = new ArrayList<>();
        notice.add("NOTICE");
        notice.add("info: subscription "+subscriptionId+" accepted.");

        eventBroadcaster.submit(() -> fetchAndBroadcastEvents(context, subscriptionId));

        return context.broadcast(gson.toJson(notice));
    }

    private byte handleSubscriptionRemoval(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription deleted: {}", subscriptionKey);

        subscriptions.remove(subscriptionKey);

        final List<String> notice = new ArrayList<>();
        notice.add("NOTICE");
        notice.add("info: subscription "+subscriptionId+" removed.");

        return context.broadcast(gson.toJson(notice));
    }

    private String persistEvent(
            final String eventJson,
            final EventData event,
            final File eventDB
    ) {
        /**
         * Save event version
         */
        final File eventVersionDB = new File(eventDB, "/version");
        if ( ! eventVersionDB.exists() ) eventVersionDB.mkdirs();
        final File eventVersion = new File(eventVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream eventRecord = new FileOutputStream(eventVersion)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Event] Version saved");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not save version: {}", failure.getMessage());
            return "error: Development in progress.";
        }

        /**
         * Update event with current data
         */
        final File eventCurrentDB = new File(eventDB, "/current");
        if( ! eventCurrentDB.exists() ) eventCurrentDB.mkdirs();
        final File eventData = new File(eventCurrentDB, "data.json");
        try (final OutputStream eventRecord = new FileOutputStream(eventData)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Event] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not update data: {}", failure.getMessage());
        }

        /*
         * Save author
         */
        final File authorDb = new File(directory, "/authors/" + event.getPublicKey() + "/events");
        if( ! authorDb.exists() ) authorDb.mkdirs();

        final File eventsAuthorFile = new File(authorDb, event.getEventId());
        if( ! eventsAuthorFile.exists() ) {
            try {
                eventsAuthorFile.createNewFile();
                logger.info("[Nostr] [Persistence] [Event] Author linked");
            } catch(IOException failure) {
                logger.warning("[Nostr] [Persistence] [Event] Could not link author: {}", failure.getMessage());
            }
        }

        return null;
    }

    private String cacheEvent(final String eventJson, final EventData event) {
        this.ephemeralEvents.put(event.getEventId(), event);
        return null;
    }

    private synchronized void fetchAndBroadcastEvents(
            final WebsocketContext context,
            final String subscriptionId
    ) {
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        final Collection<JsonObject> filter = this.subscriptions
            .getOrDefault(subscriptionKey, Collections.emptyList());

        final Gson gson = new GsonBuilder().setPrettyPrinting().create();

        final List<JsonObject> events = new ArrayList<>();

        final File eventsDB = new File(directory, "events");
        if(eventsDB.exists()) eventsDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( ! pathname.isDirectory()) return false;

                final File current = new File(pathname, "/current/data.json");
                if( !current.exists() ) return false;

                try(final InputStream in = new FileInputStream(current)) {
                    final JsonObject data = gson.fromJson(new InputStreamReader(in), JsonObject.class);
                    events.add(data);
                } catch(IOException failure) { /***/ }

                return false;
            }
        });

        logger.info("[Nostr] [Event] events fetched:");
        events.stream().forEach(entry -> logger.info("[Nostr] [Event] {}", gson.toJson(entry)));
        logger.info("[Nostr] [Event] ^^^^^-----");

        for(final JsonObject entry: filter) {
            final JsonElement authors = entry.get("authors");
            final JsonElement ids = entry.get("ids");
            final JsonElement since = entry.get("since");
            final JsonElement until = entry.get("until");

            //if(entry.get(subscriptionKey))
        }

    }

}
