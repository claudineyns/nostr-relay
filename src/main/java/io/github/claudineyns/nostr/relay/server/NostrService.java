package io.github.claudineyns.nostr.relay.server;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

import io.github.claudineyns.nostr.relay.dto.EventValidation;
import io.github.claudineyns.nostr.relay.specs.EventState;
import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.websocket.TextMessage;

@SuppressWarnings("unused")
public class NostrService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File directory = new File("/var/nostr/data/");

    // [ENFORCEMENT] Keep this executor with only a single thread
    private ExecutorService eventBroadcaster = Executors.newSingleThreadExecutor();

    private final Map<String, Collection<JsonObject>> subscriptions = new ConcurrentHashMap<>();

    private static int METADATA = 0;
    private static int TEXT_NOTE = 1;
    private static int DELETION = 5;
    private static int CHANNEL_CREATE = 40;
    private static int CHANNEL_METADATA = 41;
    private static int CHANNEL_MESSAGE = 42;
    private static int CHANNEL_HIDE = 43;
    private static int CHANNEL_MUTE_USER = 44;

    private EventValidation validate(final String eventJson) throws IOException {
        final Gson gson = new GsonBuilder().create();

        final URL url = new URL("http://localhost:8888/event");
        final HttpURLConnection http = (HttpURLConnection) url.openConnection();

        http.setRequestMethod("POST");
        http.setDoInput(true);
        http.setDoOutput(true);
        http.setInstanceFollowRedirects(false);

        final byte[] raw = eventJson.getBytes(StandardCharsets.UTF_8);

        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("Content-Length", String.valueOf(raw.length));
        http.setRequestProperty("Connection", "close");

        final OutputStream out = http.getOutputStream();
        out.write(raw);
        out.flush();

        final InputStream in = http.getInputStream();
        final EventValidation validation = gson.fromJson(
            new InputStreamReader(in),
            EventValidation.class);
      
        http.disconnect();

        return validation;
    }

    public byte consume(final WebsocketContext context, final TextMessage message) {
        final String jsonData = message.getMessage();

        final List<String> notice = new ArrayList<>();
        notice.add("NOTICE");
        
        final Gson gson = new GsonBuilder().create();

        if( ! jsonData.startsWith("[") || ! jsonData.endsWith("]") ) {
            notice.add("error: Not a json array payload.");
            return context.broadcast(gson.toJson(notice));
        }

        final JsonArray nostrMessage;
        try {
            nostrMessage = gson.fromJson(jsonData, JsonArray.class);
        } catch(JsonParseException failure) {
            notice.add("error: could not parse data: " + failure.getMessage());
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

    private byte handleEvent(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final JsonObject eventJson;
        final String eventRawJson;
        final EventValidation validation;
        try {
            eventJson = nostrMessage.get(1).getAsJsonObject();
            eventRawJson = eventJson.toString();
            validation = this.validate(eventRawJson);
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

        if( Boolean.FALSE.equals(validation.getStatus()) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "error: " + validation.getMessage()));

            return context.broadcast(gson.toJson(response));
        }

        final String responseText;
        if( EventState.REGULAR.equals(state) ) {
            responseText = persistEvent(kind, eventId, authorId, state, eventRawJson);
        } else if( EventState.REPLACEABLE.equals(state) ) {
            if( kind == METADATA ) {
                responseText = persistProfile(authorId, eventRawJson);
            } else {
                responseText = persistEvent(kind, eventId, authorId, state, eventRawJson);
            }
        } else if( EventState.EPHEMERAL.equals(state) ) {
            responseText = consumeEphemeralEvent(eventJson);
        } else {
            responseText = "error: Not supported yet";
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
            final boolean newEvents = true;
            this.eventBroadcaster.submit(() -> this.filterAndBroadcastEvents(
                context, subscriptionId, Collections.singletonList(eventJson), newEvents
            ));
        });

        this.eventBroadcaster.submit(() -> context.broadcast(gson.toJson(response)));

        if( kind == DELETION ) {
            this.removeEventsForDeletionEvent(eventId, authorId, eventJson);
        }

        return 0;
    }

    private byte handleSubscriptionRequest(final WebsocketContext context, final JsonArray nostrMessage) {
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription registered: {}", subscriptionId);

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
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription unregistered: {}", subscriptionId);

        subscriptions.remove(subscriptionKey);

        return 0;
    }

    private String persistEvent(
        final int kind,
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

    private String persistProfile(final String authorId, final String eventJson) {
        final File profileDB = new File(directory, "/profile/"+authorId);

        if( ! profileDB.exists() ) profileDB.mkdirs();

        final File profileVersionDB = new File(profileDB, "/version");
        if ( ! profileVersionDB.exists() ) profileVersionDB.mkdirs();
        final File profileVersion = new File(profileVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream profileRecord = new FileOutputStream(profileVersion)) {
            profileRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Profile] Version saved");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Profile] Could not save version: {}", failure.getMessage());
            return "error: Development in progress.";
        }

        final File profileCurrentDB = new File(profileDB, "/current");
        if( ! profileCurrentDB.exists() ) profileCurrentDB.mkdirs();
        final File profileData = new File(profileCurrentDB, "data.json");
        try (final OutputStream profileRecord = new FileOutputStream(profileData)) {
            profileRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Profile] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Profile] Could not update data: {}", failure.getMessage());
        }

        return null;
    }

    private byte removeEventsForDeletionEvent(
        final String eventId,
        final String authorId,
        final JsonObject deletionEvent
    ) {
        final Gson gson = new GsonBuilder().create();
        final List<String> linkedEventId = new ArrayList<>();
        Optional
            .ofNullable(deletionEvent.get("tags"))
            .ifPresent(element -> element
                .getAsJsonArray()
                .forEach(entry -> {
                    final JsonArray subItem = entry.getAsJsonArray();
                    final String tagName = subItem.get(0).getAsString();
                    if( ! "e".equals(tagName) ) return;

                    linkedEventId.add(subItem.get(1).getAsString());
                })
        );

        final File eventDB = new File(directory, "/events");

        final List<JsonObject> eventsMarkedForDeletion = new ArrayList<>();
        eventDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( !pathname.isDirectory() ) return false;

                final File eventFile = new File(pathname, "/current/data.json");
                if (!eventFile.exists()) return false;

                try (final InputStream in = new FileInputStream(eventFile) ) {
                    final JsonObject data = gson.fromJson(new InputStreamReader(in), JsonObject.class);

                    final String qAuthorId = data.get("pubkey").getAsString();
                    final String qEventId  = data.get("id").getAsString();
                    final int qEventKind   = data.get("kind").getAsInt();
                    final EventState state = EventState.byKind(qEventKind);

                    if( EventState.REGULAR.equals(state) 
                            && qEventKind != DELETION
                            && qAuthorId.equals(authorId)
                            && linkedEventId.contains(qEventId)
                    ) {
                        eventsMarkedForDeletion.add(data);
                    }
                } catch(IOException failure) {
                    logger.warning("[Nostr] [Persistence] [Event] Could not load event: {}", failure.getMessage());
                }

                return false;
            }
        });

        eventsMarkedForDeletion.stream().forEach(event -> {
            final String deletionEventId = event.get("id").getAsString();

            final File eventVersionDB = new File(directory, "/events/" + deletionEventId + "/version");
            if( !eventVersionDB.exists() ) eventVersionDB.mkdirs();
            final File eventVersionFile = new File(eventVersionDB, "data-" + System.currentTimeMillis() + "-deleted.json");
            if( ! eventVersionFile.exists() ) {
                try {
                     eventVersionFile.createNewFile();
                } catch(IOException failure) {
                    logger.warning("[Nostr] [Persistence] [Event] Could not delete event {}: {}", deletionEventId, failure.getMessage());
                    return;
                }
            }

            final File eventFile = new File(directory, "/events/" + deletionEventId + "/current/data.json");
            if(eventFile.exists()) eventFile.delete();

            logger.info("[Nostr] [Persistence] [Event] event {} deleted.", deletionEventId);
        });

        return 0;
    }

    private String consumeEphemeralEvent(final JsonObject eventJson) {
        return null;
    }

    private synchronized byte fetchAndBroadcastEvents(
            final WebsocketContext context,
            final String subscriptionId
    ) {

        final List<JsonObject> events = new ArrayList<>();
        fetchEvents(events);
        fetchProfile(events);

        final boolean newEvents = false;
        return this.filterAndBroadcastEvents(context, subscriptionId, events, newEvents);
    }

    private byte fetchEvents(final List<JsonObject> events) {
        return this.fetchCurrent(events, new File(directory, "events"));
    }

    private byte fetchProfile(final List<JsonObject> events) {
        return this.fetchCurrent(events, new File(directory, "profile"));
    }

    private byte fetchCurrent(final List<JsonObject> events, final File dataDB) {
        final Gson gson = new GsonBuilder().create();

        if(dataDB.exists()) dataDB.listFiles(new FileFilter() {
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

        return 0;
    }

    private static <T> boolean any(Collection<T> in, Collection<T> from) {
        return from.stream().filter(el -> in.contains(el)).count() > 0;
    }

    private byte filterAndBroadcastEvents(
        final WebsocketContext context,
        final String subscriptionId,
        final Collection<JsonObject> events,
        final boolean newEvents
    ) {
        final Gson gson = new GsonBuilder().create();

        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        final Collection<JsonObject> filter = this.subscriptions
            .getOrDefault(subscriptionKey, Collections.emptyList());

        final List<JsonObject> selectedEvents = new ArrayList<>();

        final byte NO_LIMIT = 0;
        final int[] limit = new int[]{ NO_LIMIT };

        events.stream().forEach(data -> {
            final String eventId    = data.get("id").getAsString();
            final String authorId   = data.get("pubkey").getAsString();
            final int kind          = data.get("kind").getAsInt();
            final JsonElement tags  = data.get("tags");
            final Number createdAt  = data.get("created_at").getAsNumber();

            final List<String> evRefPubKeyList = new ArrayList<>();
            Optional
                .ofNullable(tags)
                .ifPresent(tagEL -> tagEL
                    .getAsJsonArray()
                    .forEach(entryEL -> {
                        final JsonArray entryList = entryEL.getAsJsonArray();
                        final String tagName = entryList.get(0).getAsString();
                        if("p".equals(tagName)) evRefPubKeyList.add(entryList.get(1).getAsString());
                    })
            );

            for(final JsonObject entry: filter) {
                boolean emptyFilter = true;

                final List<String> eventIdList = new ArrayList<>();
                Optional.ofNullable(entry.get("ids")).ifPresent(q -> q
                    .getAsJsonArray()
                    .iterator()
                    .forEachRemaining( element -> eventIdList.add(element.getAsString()) )
                );
                emptyFilter = emptyFilter && eventIdList.isEmpty();

                final List<Integer> kindList = new ArrayList<>();
                Optional.ofNullable(entry.get("kinds")).ifPresent(q -> q
                    .getAsJsonArray()
                    .iterator()
                    .forEachRemaining( element -> kindList.add(element.getAsInt()) )
                );
                emptyFilter = emptyFilter && kindList.isEmpty();

                final List<String> authorIdList = new ArrayList<>();
                Optional.ofNullable(entry.get("authors")).ifPresent(q -> q
                    .getAsJsonArray()
                    .iterator()
                    .forEachRemaining( element -> authorIdList.add(element.getAsString()) )
                );
                emptyFilter = emptyFilter && authorIdList.isEmpty();

                final List<String> refPubkeyList = new ArrayList<>();
                Optional.ofNullable(entry.get("#p")).ifPresent(q -> q
                    .getAsJsonArray()
                    .iterator()
                    .forEachRemaining( element -> refPubkeyList.add(element.getAsString()) )
                );
                emptyFilter = emptyFilter && refPubkeyList.isEmpty();

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

                if(emptyFilter) continue;

                boolean include = true;
                include = include && (eventIdList.isEmpty()   || eventIdList.contains(eventId));
                include = include && (kindList.isEmpty()      || kindList.contains(kind));
                include = include && (authorIdList.isEmpty()  || authorIdList.contains(authorId));
                include = include && (refPubkeyList.isEmpty() || any(evRefPubKeyList, refPubkeyList) );
                include = include && (since[0] == 0           || createdAt.intValue() >= since[0] );
                include = include && (until[0] == 0           || createdAt.intValue() <= until[0] );

                if( include ) {
                    selectedEvents.add(data);
                    logger.info("[Nostr] [Subscription]\nEvent\n{}\nmatched by filter\n{}\n", data.toString(), entry.toString());
                    break;
                }

            }

        });

        selectedEvents.sort((a, b) -> 
            b.get("created_at").getAsInt() - a.get("created_at").getAsInt()
        );

        final Collection<JsonObject> sendLater = new ArrayList<>();

        if( limit[0] != NO_LIMIT ) {
            final int stop = limit[0] - 1;
            for(int q = selectedEvents.size() - 1; q >= 0 && q > stop; --q) {
                sendLater.add(selectedEvents.remove(q));
            }
        }

        if( ! selectedEvents.isEmpty() ) {
            final List<Object> subscriptionResponse = new ArrayList<>();
            subscriptionResponse.addAll(Arrays.asList("EVENT", subscriptionId));
            subscriptionResponse.addAll(selectedEvents);
            this.eventBroadcaster.submit(() -> context.broadcast(gson.toJson(subscriptionResponse)));
        }

        if( ! newEvents ) {
            final String endOfStoredEvents = gson.toJson(Arrays.asList("EOSE", subscriptionId));
            this.eventBroadcaster.submit(()-> context.broadcast(endOfStoredEvents));
        }

        sendLater.forEach(event -> {
            final List<Object> deferred = new ArrayList<>();
            deferred.addAll(Arrays.asList("EVENT", subscriptionId));
            deferred.add(event);
            this.eventBroadcaster.submit(()-> context.broadcast(gson.toJson(deferred)));
        });

        return 0;
    }
    
}
