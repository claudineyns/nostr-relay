package io.github.social.nostr.relay.server;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import org.apache.commons.codec.binary.Hex;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.dto.EventValidation;
import io.github.social.nostr.relay.service.EventCacheDataService;
import io.github.social.nostr.relay.service.EventDiskDataService;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;
import io.github.social.nostr.relay.websocket.TextMessage;

@SuppressWarnings("unused")
public class NostrService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
    private final IEventService eventService = IEventService.INSTANCE;

    private final File directory = new File("/var/nostr/data/");

    private final String validationHost = AppProperties.getEventValidationHost();
    private final int validationPort = AppProperties.getEventValidationPort();

    // [ENFORCEMENT] Keep this executor with only a single thread
    private ExecutorService eventBroadcaster = Executors.newSingleThreadExecutor();

    private final Map<String, Collection<JsonObject>> subscriptions = new ConcurrentHashMap<>();

    public byte close() {
        return eventService.close();
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

        final String checkRegistration = eventService.checkRegistration(authorId);
        if( checkRegistration != null ) {
            response.addAll(Arrays.asList(Boolean.FALSE, checkRegistration));

            return context.broadcast(gson.toJson(response));
        }

        if( Boolean.FALSE.equals(validation.getStatus()) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "error: " + validation.getMessage()));

            return context.broadcast(gson.toJson(response));
        }

        final String responseText;
        if( EventState.REGULAR.equals(state) ) {
            responseText = eventService.persistEvent(kind, eventId, authorId, state, eventRawJson);
        } else if( EventState.REPLACEABLE.equals(state) ) {
            if( kind == EventKind.METADATA ) {
                responseText = eventService.persistProfile(authorId, eventRawJson);
            } else if( kind == EventKind.CONTACT_LIST ) {
                responseText = eventService.persistContactList(authorId, eventRawJson);
            } else {
                responseText = eventService.persistEvent(kind, eventId, authorId, state, eventRawJson);
            }
        } else if( EventState.PARAMETERIZED_REPLACEABLE.equals(state) ) {
            responseText = eventService.persistParameterizedReplaceable(
                kind, eventId, authorId, eventJson, eventRawJson);
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

        if( kind == EventKind.DELETION ) {
            eventService.removeEventsByDeletionEvent(eventId, authorId, eventJson);
        }

        return 0;
    }

    private byte handleSubscriptionRequest(final WebsocketContext context, final JsonArray nostrMessage) {
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Subscription] [{}] request received.", subscriptionId);

        final Collection<JsonObject> filter = new ConcurrentLinkedQueue<>();
        for(int i = 2; i < nostrMessage.size(); ++i) {
            final JsonObject entry = nostrMessage.get(i).getAsJsonObject();
            filter.add(entry);
        }

        this.subscriptions.put(subscriptionKey, filter);
        logger.info("[Nostr] [Subscription] [{}] registered.", subscriptionId);

        logger.info("[Nostr] [Subscription] [{}] await for data fetch.", subscriptionId);
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

    private String consumeEphemeralEvent(final JsonObject eventJson) {
        return null;
    }

    private EventValidation validate(final String eventJson) throws IOException {
        final Gson gson = new GsonBuilder().create();

        final URL url = new URL("http://"+validationHost+":"+validationPort+"/event");
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

    private byte fetchAndBroadcastEvents(
            final WebsocketContext context,
            final String subscriptionId
    ) {

        final List<JsonObject> events = new ArrayList<>();

        logger.info("[Nostr] [Subscription] [{}] fetching events.", subscriptionId);

        eventService.fetchEvents(events);
        eventService.fetchProfile(events);
        eventService.fetchContactList(events);
        eventService.fetchParameters(events);

        logger.info("[Nostr] [Subscription] [{}] total events fetch: {}", subscriptionId, events.size());

        final boolean newEvents = false;
        return this.filterAndBroadcastEvents(context, subscriptionId, events, newEvents);
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

        logger.info("[Nostr] [Subscription] [{}] performing event filtering.", subscriptionId);

        events.stream().forEach(data -> {
            final String eventId    = data.get("id").getAsString();
            final String authorId   = data.get("pubkey").getAsString();
            final int kind          = data.get("kind").getAsInt();
            final JsonElement tags  = data.get("tags");
            final Number createdAt  = data.get("created_at").getAsNumber();

            final List<String> evRefPubKeyList = new ArrayList<>();
            final List<String> evRefParamList = new ArrayList<>();
            Optional
                .ofNullable(tags)
                .ifPresent(tagEL -> tagEL
                    .getAsJsonArray()
                    .forEach(entryEL -> {
                        final JsonArray entryList = entryEL.getAsJsonArray();
                        final String tagName = entryList.get(0).getAsString();
                        final String tagValue = entryList.get(1).getAsString();
                        switch(tagName) {
                            case "p": evRefPubKeyList.add(tagValue); break;
                            case "d": evRefParamList.add(tagValue); break;
                            default: break;
                        }
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

                final List<String> refParamList = new ArrayList<>();
                Optional.ofNullable(entry.get("#d")).ifPresent(q -> q
                    .getAsJsonArray()
                    .iterator()
                    .forEachRemaining( element -> refParamList.add(element.getAsString()) )
                );
                // Filter '#d' (data) cannot be used without combine it with 'pubkey' or 'kind'

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
                include = include && (refParamList.isEmpty()  || any(evRefParamList, refParamList) );

                include = include && (since[0] == 0           || createdAt.intValue() >= since[0] );
                include = include && (until[0] == 0           || createdAt.intValue() <= until[0] );

                if( include ) {
                    selectedEvents.add(data);
                    logger.info("[Nostr] [Subscription] [{}]\nEvent\n{}\nmatched by filter\n{}\n",
                        subscriptionId, data.toString(), entry.toString());
                    break;
                }

            }

        });

        logger.info("[Nostr] [Subscription] [{}] sorting events by creation time.", subscriptionId);

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

        if( !newEvents ) {
            logger.info("[Nostr] [Subscription] [{}] number of events to sent: {}", subscriptionId, selectedEvents.size());
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

            logger.info("[Nostr] [Subscription] [{}] number of events to sent later: {}", subscriptionId, sendLater.size());
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
