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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

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
        return context.broadcast(message);
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

        logger.info("[Nostr] [Message] event received\n{}", eventData.toString());

        final List<Object> response = new ArrayList<>();
        response.addAll(Arrays.asList("OK", eventData.getId()));

        final int currentTime = (int) (System.currentTimeMillis()/1000L);

        if( eventData.getExpiration() > 0 && eventData.getExpiration() < currentTime ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: event is expired"));

            return broadcastClient(context, gson.toJson(response));
        }

        if( eventData.getCreatedAt() > (currentTime + 600) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: event creation date is too far off from the current time. Is your system clock in sync?"));

            return broadcastClient(context, gson.toJson(response));
        }

        // final String checkRegistration = eventService.checkRegistration(eventData);
        // if( checkRegistration != null ) {
        //     response.addAll(Arrays.asList(Boolean.FALSE, checkRegistration));
// 
        //     return broadcastClient(context, gson.toJson(response));
        // }

        if( Boolean.FALSE.equals(validation.getStatus()) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "error: " + validation.getMessage()));

            return broadcastClient(context, gson.toJson(response));
        }

        boolean refresh = false;

        String responseText = null;
        if( EventState.REGULAR.equals(eventData.getState()) ) {
            responseText = eventService.persistEvent(eventData);
            refresh = true;
        } else if( EventState.REPLACEABLE.equals(eventData.getState()) ) {
            eventService.persistReplaceable(eventData);
            refresh = true;
        } else if( EventState.PARAMETERIZED_REPLACEABLE.equals(eventData.getState()) ) {
            responseText = eventService.persistParameterizedReplaceable(eventData);
            refresh = true;
        } else if( EventState.EPHEMERAL.equals(eventData.getState()) ) {
            responseText = consumeEphemeralEvent(eventData);
        } else {
            responseText = "error: Not supported yet";
        }

        if( responseText == null ){
            response.addAll(Arrays.asList(Boolean.TRUE, ""));

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
        } else {
            response.addAll(Arrays.asList(Boolean.FALSE, responseText));
        }

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

    private byte fetchEventsFromDB(final WebsocketContext context, final List<EventData> events) {
        return eventService.fetchActiveEvents(events);
    }

    private byte fetchAndBroadcastEvents(final WebsocketContext context, final String subscriptionId) {
        final List<EventData> events = new ArrayList<>();

        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        final Collection<JsonObject> filters = Optional
            .ofNullable(this.subscriptions.get(subscriptionKey))
            .orElseGet(Collections::emptyList);

        if( ! filters.isEmpty() ) {
            logger.info("[Nostr] [Subscription] [{}] fetching events.", subscriptionId);
            this.fetchEventsFromDB(context, events);
            logger.info("[Nostr] [Subscription] [{}] total events fetch: {}", subscriptionId, events.size());
        } else {
            logger.info("[Nostr] [Subscription] [{}] no filters provided.", subscriptionId);
        }

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
        final Collection<JsonObject> filters = Optional
            .ofNullable(this.subscriptions.get(subscriptionKey))
            .orElseGet(Collections::emptyList);

        if( ! filters.isEmpty() ) {
            logger.info("[Nostr] [Subscription] [{}] filter criteria\n{}", subscriptionId, filters);

            logger.info("[Nostr] [Subscription] [{}] performing event filtering.", subscriptionId);
        }

        final List<EventData> selectedEvents = new ArrayList<>();

        fetchFilters:
        for(final JsonObject entry: filters) {
            boolean emptyFilter = true;

            final List<String> filterEventList = new ArrayList<>();
            Optional.ofNullable(entry.get("ids")).ifPresent(e -> e
                .getAsJsonArray().forEach( element -> filterEventList.add(element.getAsString()) )
            );
            emptyFilter = emptyFilter && filterEventList.isEmpty();

            final List<Integer> filterKindList = new ArrayList<>();
            Optional.ofNullable(entry.get("kinds")).ifPresent(k -> k
                .getAsJsonArray().forEach( element -> filterKindList.add(element.getAsInt()) )
            );
            emptyFilter = emptyFilter && filterKindList.isEmpty();

            final List<String> filterPubkeyList = new ArrayList<>();
            Optional.ofNullable(entry.get("authors")).ifPresent(k -> k
                .getAsJsonArray().forEach( element -> filterPubkeyList.add(element.getAsString()) )
            );
            emptyFilter = emptyFilter && filterPubkeyList.isEmpty();

            final List<String> filterRefPubkeyList = new ArrayList<>();
            Optional.ofNullable(entry.get("#p")).ifPresent(p -> p
                .getAsJsonArray().forEach( element -> filterRefPubkeyList.add(element.getAsString()) )
            );
            emptyFilter = emptyFilter && filterRefPubkeyList.isEmpty();

            final List<String> filterRefParamList = new ArrayList<>();
            Optional.ofNullable(entry.get("#d")).ifPresent(d -> d
                .getAsJsonArray().forEach( element -> filterRefParamList.add(element.getAsString()) )
            );
            // Filter '#d' (data) must not be accept without combination with 'pubkey' or 'kind'

            final List<String> filterRefCoordinatedEvent = new ArrayList<>();
            Optional.ofNullable(entry.get("#a")).ifPresent(p -> p
                .getAsJsonArray().forEach( element -> filterRefCoordinatedEvent.add(element.getAsString()) )
            );
            emptyFilter = emptyFilter && filterRefCoordinatedEvent.isEmpty();

            final int[] since = new int[] {0};
            Optional
                .ofNullable(entry.get("since"))
                .ifPresent(time -> since[0] = time.getAsInt());

            final int[] until = new int[] {0};
            Optional
                .ofNullable(entry.get("until"))
                .ifPresent(time -> until[0] = time.getAsInt());

            final int[] limit = new int[]{0};
            Optional
                .ofNullable(entry.get("limit"))
                .ifPresent(q -> limit[0] = q.getAsInt());

            if(emptyFilter) {
                logger.info("[Nostr] [Subscription] [{}] filter has been considered empty:\n{}", subscriptionId, entry);
                continue;
            }

            final Collection<EventData> filteredEvents = new ArrayList<>();

            for(final EventData eventData: events) {
                final List<String> evRefPubKeyList = new ArrayList<>();
                final List<String> evRefParamList = new ArrayList<>();

                eventData.getTags().forEach(tagList -> {
                    if(tagList.size() < 2) return;

                    final String t = tagList.get(0);
                    final String v = tagList.get(1);
                    switch(t) {
                        case "p": evRefPubKeyList.add(v); break;
                        case "d": evRefParamList.add(v); break;
                        default: break;
                    }
                });

                boolean include = true;

                include = include && (filterEventList.isEmpty()     || filterEventList.contains(eventData.getId()));
                include = include && (filterKindList.isEmpty()      || filterKindList.contains(eventData.getKind()));
                include = include && (filterPubkeyList.isEmpty()    || filterPubkeyList.contains(eventData.getPubkey()));
                include = include && (filterRefPubkeyList.isEmpty() || any(evRefPubKeyList, filterRefPubkeyList) );
                include = include && (filterRefParamList.isEmpty()  || any(evRefParamList, filterRefParamList) );

                boolean coordMatch = filterRefCoordinatedEvent.isEmpty();
                for(final String coordEvent : filterRefCoordinatedEvent) {
                    final String[] cEvent = coordEvent.split(":");
                    final int cKind = Integer.parseInt(cEvent[0]);
                    final String cPubkey = cEvent[1];
                    final String cData = cEvent[2];
                    if( eventData.getKind() == cKind
                            && eventData.getPubkey().equals(cPubkey)
                            && evRefParamList.contains(cData) ) {
                        coordMatch = true;
                        break;
                    }
                }
                include = include && coordMatch;

                include = include && (since[0] == 0 || eventData.getCreatedAt() >= since[0] );
                include = include && (until[0] == 0 || eventData.getCreatedAt() <= until[0] );

                if( include ) {
                    filteredEvents.add(eventData);

                    if(newEvents) break;

                    if( limit[0] > 0 && filteredEvents.size() == limit[0] ) break;
                }

            }

            if( filteredEvents.isEmpty() ) {
                // logger.info("[Nostr] [Subscription] [{}] filter did not match any event\n{}", entry);
                continue;
            }

            filteredEvents.stream().forEach(evt -> {
                if( ! selectedEvents.contains(evt) ) selectedEvents.add(evt);
            });

            if(newEvents && selectedEvents.size() > 0) break;

        }

        if( !newEvents ) {
            logger.info("[Nostr] [Subscription] [{}] total events to sent: {}", subscriptionId, selectedEvents.size());
        }

        if( ! selectedEvents.isEmpty() ) {
            final List<Object> subscriptionResponse = new ArrayList<>();
            subscriptionResponse.addAll(Arrays.asList("EVENT", subscriptionId));
            subscriptionResponse.addAll(selectedEvents
                .stream()
                .map(event -> gson.fromJson(event.toString(), JsonObject.class))
                .collect(Collectors.toList()) 
            );

            this.broadcastClient(context, gson.toJson(subscriptionResponse));
        }

        if( ! newEvents ) {
            this.broadcastClient(context, gson.toJson(Arrays.asList("EOSE", subscriptionId)));
        }

        return 0;
    }
    
}
