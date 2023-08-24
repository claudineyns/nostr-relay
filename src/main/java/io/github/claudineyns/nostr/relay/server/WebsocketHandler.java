package io.github.claudineyns.nostr.relay.server;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import io.github.claudineyns.nostr.relay.specs.EventState;
import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.websocket.BinaryMessage;
import io.github.claudineyns.nostr.relay.websocket.TextMessage;
import io.github.claudineyns.nostr.relay.websocket.Websocket;
import io.github.claudineyns.nostr.relay.websocket.WebsocketException;

@SuppressWarnings("unused")
public class WebsocketHandler implements Websocket {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File directory = new File("/var/nostr/data/");

    private ExecutorService eventBroadcaster = Executors.newCachedThreadPool();

    private final Map<String, Collection<JsonObject>> subscriptions = new ConcurrentHashMap<>();

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
        logger.info("[WS] Server received message of type {}", message.getType());

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
                return this.handleSubscriptionRequest(context, nostrMessage);
            case "CLOSE":
                return this.handleSubscriptionRemoval(context, nostrMessage);
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

    private static int METADATA = 0;
    private static int TEXT_NOTE = 1;

    private byte handleEvent(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final JsonObject eventJson;
        final String eventRawJson;
        try {
            eventJson = nostrMessage.get(1).getAsJsonObject();
            eventRawJson = eventJson.toString();
        } catch(Exception failure) {
            return logger.info(
                "[Nostr] [Message] could not parse event\n{}: {}",
                failure.getClass().getCanonicalName(),
                failure.getMessage());
        }

        final String eventId = eventJson.get("id").getAsString();
        final String authorId = eventJson.get("pubkey").getAsString();
        final int kind = eventJson.get("kind").getAsInt();
        final EventState state = EventState.byKind(kind);

        logger.info("[Nostr] [Message] event ID received: {}.", eventId);

        final List<Object> response = new ArrayList<>();
        response.add("OK");
        response.add(eventId);

        if( ! "1a7c9d8ac8a9f50d255573dbe1bacd511677d288a0ba5e2332ae4c15e407f29f".equals(authorId)) {
            response.addAll(Arrays.asList(Boolean.FALSE, "blocked: development"));

            return context.broadcast(gson.toJson(response));
        }

        final String responseText;
        if( EventState.REGULAR.equals(state) ) {
            responseText = persistEvent(eventId, authorId, state, eventRawJson);
        } else if( EventState.REPLACEABLE.equals(state) ) {
            responseText = persistEvent(eventId, authorId, state, eventRawJson);
        } else if( EventState.EPHEMERAL.equals(state) ) {
            responseText = consumeEphemeralEvent(eventJson);
        } else {
            responseText = "error: Could not update database";
        }

        Optional.ofNullable(responseText)
        .ifPresentOrElse(
            info -> response.addAll(Arrays.asList(Boolean.FALSE, info)),
            () -> response.addAll(Arrays.asList(Boolean.TRUE, ""))
        );

        this.subscriptions.keySet()
        .stream()
        .filter(key -> key.endsWith(":"+context.getContextID()))
        .forEach(key -> {
            final String subscriptionId = key.substring(0, key.lastIndexOf(":"));

            this.eventBroadcaster.submit(() -> this.filterAndBroadcastEvents(context, subscriptionId, Collections.singletonList(eventJson)));
        });

        return context.broadcast(gson.toJson(response));
    }

    private byte handleSubscriptionRequest(final WebsocketContext context, final JsonArray nostrMessage) {
        final Gson gson = new GsonBuilder().create();

        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription created: {}", subscriptionKey);

        final Collection<JsonObject> filter = new ConcurrentLinkedQueue<>();

        for(int i = 2; i < nostrMessage.size(); ++i) {
            final JsonObject entry = nostrMessage.get(i).getAsJsonObject();
            filter.add(entry);
        }

        subscriptions.put(subscriptionKey, filter);

        eventBroadcaster.submit(() -> fetchAndBroadcastEvents(context, subscriptionId));

        return 0;
    }

    private byte handleSubscriptionRemoval(final WebsocketContext context, final JsonArray nostrMessage) {
        final Gson gson = new GsonBuilder().create();

        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription deleted: {}", subscriptionKey);

        subscriptions.remove(subscriptionKey);

        return 0;
    }

    private String persistEvent(
        final String eventId,
        final String authorId,
        final EventState state,
        final String eventJson
    ) {
        final File eventDB = new File(directory, "/events/"+eventId);
        if( EventState.REGULAR.equals(state) && eventDB.exists() ) {
            return "duplicate: event has already been registered.";
        }

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

        final File eventCurrentDB = new File(eventDB, "/current");
        if( ! eventCurrentDB.exists() ) eventCurrentDB.mkdirs();
        final File eventData = new File(eventCurrentDB, "data.json");
        try (final OutputStream eventRecord = new FileOutputStream(eventData)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Event] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not update data: {}", failure.getMessage());
        }

        final File authorDb = new File(directory, "/authors/" + authorId + "/events");
        if( ! authorDb.exists() ) authorDb.mkdirs();

        final File eventsAuthorFile = new File(authorDb, eventId);
        if( ! eventsAuthorFile.exists() ) {
            try {
                eventsAuthorFile.createNewFile();
                logger.info("[Nostr] [Persistence] [Event] Author linked");
            } catch(IOException failure) {
                logger.warning(
                    "[Nostr] [Persistence] [Event] Could not link author: {}",
                    failure.getMessage());
            }
        }

        return null;
    }

    private String consumeEphemeralEvent(final JsonObject eventJson) {
        return null;
    }

    private synchronized byte fetchAndBroadcastEvents(
            final WebsocketContext context,
            final String subscriptionId
    ) {
        final Gson gson = new GsonBuilder().create();

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

        return this.filterAndBroadcastEvents(context, subscriptionId, events);
    }

    private byte filterAndBroadcastEvents(
        final WebsocketContext context,
        final String subscriptionId,
        final Collection<JsonObject> events
    ) {
        final Gson gson = new GsonBuilder().create();

        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        final Collection<JsonObject> filter = this.subscriptions
            .getOrDefault(subscriptionKey, Collections.emptyList());

        final List<JsonObject> selectedEvents = new ArrayList<>();

        final int[] limit = new int[]{1};

        for(final JsonObject entry: filter) {
            final List<String> eventIdList = new ArrayList<>();
            Optional.ofNullable(entry.get("ids")).ifPresent(q -> q
                .getAsJsonArray()
                .iterator()
                .forEachRemaining( element -> eventIdList.add(element.getAsString()) )
            );

            final List<Integer> kindList = new ArrayList<>();
            Optional.ofNullable(entry.get("kinds")).ifPresent(q -> q
                .getAsJsonArray()
                .iterator()
                .forEachRemaining( element -> kindList.add(element.getAsInt()) )
            );

            final List<String> authorIdList = new ArrayList<>();
            Optional.ofNullable(entry.get("authors")).ifPresent(q -> q
                .getAsJsonArray()
                .iterator()
                .forEachRemaining( element -> authorIdList.add(element.getAsString()) )
            );

            final int[] since = new int[] {0};
            Optional
                .ofNullable(entry.get("since"))
                .ifPresent(time -> since[0] = time.getAsInt());

            final int[] until = new int[] {0};
            Optional
                .ofNullable(entry.get("until"))
                .ifPresent(time -> until[0] = time.getAsInt());

            Optional
                .ofNullable(entry.get("limit"))
                .ifPresent(q -> {
                    final int v = q.getAsInt();
                    if( v > limit[0] ) limit[0] = v;
                });

            events.stream().forEach(data -> {
                final String eventId   = data.get("id").getAsString();
                final String authorId  = data.get("pubkey").getAsString();
                final int kind         = data.get("kind").getAsInt();
                final Number createdAt = data.get("created_at").getAsNumber();

                boolean include = true;
                include = include && (eventIdList.isEmpty()  || eventIdList.contains(eventId));
                include = include && (authorIdList.isEmpty() || authorIdList.contains(authorId));
                include = include && (kindList.isEmpty()     || kindList.contains(kind));
                include = include && (since[0] == 0          || createdAt.intValue() >= since[0] );
                include = include && (until[0] == 0          || createdAt.intValue() <= until[0] );

                if( include && !selectedEvents.contains(data) ){
                    selectedEvents.add(data);
                    logger.info("[Nostr] [Subscription]\nevent\n{}\nmatch by filter\n{}", data.toString(), entry.toString());
                }
            });

        }

        selectedEvents.sort((a, b) -> 
            b.get("created_at").getAsInt() - a.get("created_at").getAsInt()
        );

        final Collection<JsonObject> sendLater = new ArrayList<>();

        final int stop = limit[0] - 1;
        for(int q = selectedEvents.size() - 1; q >= 0 && q > stop; --q) {
            sendLater.add(selectedEvents.remove(q));
        }

        final List<Object> subscriptionResponse = new ArrayList<>();
        subscriptionResponse.addAll(Arrays.asList("EVENT", subscriptionId));
        subscriptionResponse.addAll(selectedEvents);

        final List<String> packetList = new ArrayList<>();
        if( ! selectedEvents.isEmpty() ) {
            packetList.add(gson.toJson(subscriptionResponse));
        }

        sendLater.forEach(event -> {
            final List<Object> deferred = new ArrayList<>();
            deferred.addAll(Arrays.asList("EVENT", subscriptionId));
            deferred.add(event);
            packetList.add(gson.toJson(deferred));
        });

        packetList.stream().forEach(packet -> this.eventBroadcaster.submit(() -> context.broadcast(packet)));

        return 0;
    }

}
