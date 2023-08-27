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
import io.github.social.nostr.relay.specs.EventData;
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

    private ExecutorService eventProcessor = Executors.newCachedThreadPool();

    // [ENFORCEMENT] Keep this executor with only a single thread
    private ExecutorService clientBroadcaster = Executors.newSingleThreadExecutor();

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

            return this.broadcastClient(context, gson.toJson(notice));
        }

        final JsonArray nostrMessage;
        try {
            nostrMessage = gson.fromJson(jsonData, JsonArray.class);
        } catch(JsonParseException failure) {
            logger.warning("[Nostr] could not parse message: {}", message.getMessage());

            notice.add("error: could not parse data: " + failure.getMessage());
            return this.broadcastClient(context, gson.toJson(notice));
        }

        if( nostrMessage.isEmpty() ) {
            logger.warning("[Nostr] Empty message received.");

            notice.add("warning: empty message.");
            return this.broadcastClient(context, gson.toJson(notice));
        }

        final String messageType = nostrMessage.get(0).getAsString();

        switch(messageType) {
            case "EVENT":
                return this.handleEvent(context, nostrMessage, gson);
            case "REQ":
                return this.handleSubscriptionRegistration(context, nostrMessage);
            case "CLOSE":
                return this.handleSubscriptionUnregistration(context, nostrMessage);
            default:
                return logger.warning("[Nostr] Message not supported yet\n{}", message.getMessage());
        }
    }

    private byte broadcastClient(final WebsocketContext context, final String message) {
        this.clientBroadcaster.submit(() -> context.broadcast(message));

        return 0;
    }

    private byte handleEvent(
            final WebsocketContext context,
            final JsonArray nostrMessage,
            final Gson gson
        ) {

        final EventData eventData;
        final EventValidation validation;
        try {
            eventData = EventData.of(nostrMessage.get(1).getAsJsonObject());
            validation = this.validate(eventData.toString());
        } catch(final Exception failure) {
            return logger.info(
                "[Nostr] [Message] could not parse event\n{}: {}",
                failure.getClass().getCanonicalName(),
                failure.getMessage());
        }

        logger.info("[Nostr] [Message] event ID received: {}.", eventData.getId());

        final List<Object> response = new ArrayList<>();
        response.addAll(Arrays.asList("OK", eventData.getId()));

        final int currentTime = (int) (System.currentTimeMillis()/1000L);

        if( eventData.getExpiration() > 0 && eventData.getExpiration() < currentTime ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: event is expired"));

            return broadcastClient(context, gson.toJson(response));
        }

        final String checkRegistration = eventService.checkRegistration(eventData.getPubkey());
        if( checkRegistration != null ) {
            response.addAll(Arrays.asList(Boolean.FALSE, checkRegistration));

            return broadcastClient(context, gson.toJson(response));
        }

        if( Boolean.FALSE.equals(validation.getStatus()) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "error: " + validation.getMessage()));

            return broadcastClient(context, gson.toJson(response));
        }

        final String responseText;
        if( EventState.REGULAR.equals(eventData.getState()) ) {
            responseText = eventService.persistEvent(eventData);
        } else if( EventState.REPLACEABLE.equals(eventData.getState()) ) {
            if( eventData.getKind() == EventKind.METADATA ) {
                responseText = eventService.persistProfile(eventData.getPubkey(), eventData.toString());
            } else if( eventData.getKind() == EventKind.CONTACT_LIST ) {
                responseText = eventService.persistContactList(eventData.getPubkey(), eventData.toString());
            } else {
                responseText = eventService.persistEvent(eventData);
            }
        } else if( EventState.PARAMETERIZED_REPLACEABLE.equals(eventData.getState()) ) {
            responseText = eventService.persistParameterizedReplaceable(eventData);
        } else if( EventState.EPHEMERAL.equals(eventData.getState()) ) {
            responseText = consumeEphemeralEvent(eventData);
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
            this.eventProcessor.submit(() -> this.filterAndBroadcastEvents(
                context, subscriptionId, Collections.singletonList(eventData), newEvents
            ));
        });

        broadcastClient(context, gson.toJson(response));

        if( eventData.getKind() == EventKind.DELETION ) {
            eventService.deletionRequestEvent(eventData);
        }

        return 0;
    }

    private byte handleSubscriptionRegistration(final WebsocketContext context, final JsonArray nostrMessage) {
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
        this.eventProcessor.submit(() -> fetchAndBroadcastEvents(context, subscriptionId));

        return 0;
    }

    private byte handleSubscriptionUnregistration(final WebsocketContext context, final JsonArray nostrMessage) {
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription unregistered: {}", subscriptionId);

        subscriptions.remove(subscriptionKey);

        return 0;
    }

    private String consumeEphemeralEvent(final EventData eventJson) {
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

    private byte fetchEventsFromDB(
        final WebsocketContext context,
        final String subscriptionId,
        final List<EventData> events
    ) {
        final List<EventData> cacheEvents = new ArrayList<>();

        eventService.fetchEvents(cacheEvents);
        eventService.fetchProfile(cacheEvents);
        eventService.fetchContactList(cacheEvents);
        eventService.fetchParameters(cacheEvents);

        final int currentTime = (int) (System.currentTimeMillis()/1000L);

        for(int i = cacheEvents.size() - 1; i >= 0; --i) {
            final EventData event = cacheEvents.get(i);
            if( event.getExpiration() > 0 && event.getExpiration() < currentTime ) {
                cacheEvents.remove(i);
            }
        }

        events.addAll(cacheEvents);
        return 0;
    }

    private byte fetchAndBroadcastEvents(final WebsocketContext context, final String subscriptionId) {
        final List<EventData> events = new ArrayList<>();

        logger.info("[Nostr] [Subscription] [{}] fetching events.", subscriptionId);

        try {
            this.fetchEventsFromDB(context, subscriptionId, events);
        } catch(Exception failure) {
            logger.info("[Nostr] [Subscription] event fetching failure: {}", failure.getMessage());
        }

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
        final Collection<EventData> events,
        final boolean newEvents
    ) {
        final Gson gson = new GsonBuilder().create();

        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        final Collection<JsonObject> filter = this.subscriptions
            .getOrDefault(subscriptionKey, Collections.emptyList());

        final List<EventData> selectedEvents = new ArrayList<>();

        final byte NO_LIMIT = 0;
        final int[] limit = new int[]{ NO_LIMIT };

        logger.info("[Nostr] [Subscription] [{}] filter criteria", subscriptionId);
        filter.stream().forEach(System.out::println);

        logger.info("[Nostr] [Subscription] [{}] performing event filtering.", subscriptionId);

        events.stream().forEach(eventData -> {
            final List<String> evRefPubKeyList = new ArrayList<>();
            final List<String> evRefParamList = new ArrayList<>();

            eventData.getTags().forEach(tagList -> {
                if(tagList.size() != 2) return;

                final String t = tagList.get(0);
                final String v = tagList.get(1);
                switch(t) {
                    case "p": evRefPubKeyList.add(v); break;
                    case "d": evRefParamList.add(v); break;
                    default: break;
                }
            });

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
                // Filter '#d' (data) must not be accept without combination with 'pubkey' or 'kind'

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

                include = include && (eventIdList.isEmpty()   || eventIdList.contains(eventData.getId()));
                include = include && (kindList.isEmpty()      || kindList.contains(eventData.getKind()));
                include = include && (authorIdList.isEmpty()  || authorIdList.contains(eventData.getPubkey()));
                include = include && (refPubkeyList.isEmpty() || any(evRefPubKeyList, refPubkeyList) );
                include = include && (refParamList.isEmpty()  || any(evRefParamList, refParamList) );

                include = include && (since[0] == 0           || eventData.getCreatedAt() >= since[0] );
                include = include && (until[0] == 0           || eventData.getCreatedAt() <= until[0] );

                if( include ) {
                    selectedEvents.add(eventData);
                    logger.info("[Nostr] [Subscription] [{}]\nEvent\n{}\nmatched by filter\n{}\n",
                        subscriptionId, eventData.toString(), entry.toString());
                    break;
                }

            }

        });

        logger.info("[Nostr] [Subscription] [{}] sorting events by creation time.", subscriptionId);

        selectedEvents.sort((a, b) -> b.getCreatedAt() - a.getCreatedAt());

        final Collection<EventData> sendLater = new ArrayList<>();

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

            this.broadcastClient(context, gson.toJson(subscriptionResponse));
        }
        
        if( ! newEvents ) {
            this.broadcastClient(context, gson.toJson(Arrays.asList("EOSE", subscriptionId)));

            logger.info("[Nostr] [Subscription] [{}] number of events to sent later: {}", subscriptionId, sendLater.size());
        }

        sendLater.forEach(event -> {
            final List<Object> deferred = new ArrayList<>();
            deferred.addAll(Arrays.asList("EVENT", subscriptionId));
            deferred.add(event);

            this.broadcastClient(context, gson.toJson(deferred));
        });

        return 0;
    }
    
}
