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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final Map<String, Boolean> subscriptions = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> challenges = new HashMap<>();
    private final Map<String, Set<String>> authUsers = new HashMap<>();

    private final Map<String, AtomicInteger> countFailure = new ConcurrentHashMap<>();

    private final GsonBuilder gsonBuilder = new GsonBuilder();

    private final String protocol = AppProperties.isTls() ? "wss" : "ws";
    private final String host = AppProperties.getHost();
    private final int tlsPort = AppProperties.getTlsPort();
    private final int port = AppProperties.getPort();

    byte close() {
        return eventService.close();
    }

    byte openSession(final WebsocketContext context) {
        logger.info("[Nostr] [Context] {} startup session.", context.getContextID());

        countFailure.put(context.getContextID().toString(), new AtomicInteger());

        synchronized(this.authUsers) {
            this.authUsers.put(context.getContextID().toString(), new HashSet<>());
        }
        synchronized(this.challenges) {
            this.challenges.put(context.getContextID().toString(), new HashSet<>());
        }

        return 0;
    }

    byte closeSession(final WebsocketContext context) {
        logger.info("[Nostr] [Context] {} cleanup session.", context.getContextID());

        countFailure.remove(context.getContextID().toString());

        synchronized(this.authUsers) {
            this.authUsers.remove(context.getContextID().toString());
        }
        synchronized(this.challenges) {
            this.challenges.remove(context.getContextID().toString());
        }

        return 0;
    }

    byte consume(final WebsocketContext context, final TextMessage message) {
        if(!context.isConnected()) return 0;

        final String jsonData = message.getMessage();

        final List<String> notice = new ArrayList<>();
        notice.add("NOTICE");

        final Gson gson = gsonBuilder.create();

        if( ! jsonData.startsWith("[") || ! jsonData.endsWith("]") ) {
            notice.add("error: Not a json array payload.");
            this.broadcastClient(context, gson.toJson(notice));

            if( countFailure.get(context.getContextID().toString()).incrementAndGet() == 5 ) {
                logger.error(
                    "[Nostr] [Context] Abnormal state of connection\nRemote Address: {}\nUser-Agent:{}",
                    context.getRemoteAddress(), context.getUserAgent());            
                return context.requestClose();
            }

            return 0;
        }

        countFailure.get(context.getContextID().toString()).set(0);

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
            case "AUTH":
                return this.handleAuthentication(context, nostrMessage);
            default:
                notice.add("warning: message type '"+messageType+"' not supported yet");
                return this.broadcastClient(context, gson.toJson(notice));
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
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: the event 'created_at' field is out of the acceptable range (, +10min) for this relay"));

            return broadcastClient(context, gson.toJson(response));
        }

        if( eventData.getKind() == EventKind.ENCRYPTED_DIRECT) {
            if( !this.checkAuthentication(context, eventData) ) {
                response.addAll(Arrays.asList(Boolean.FALSE, "restricted: we do not accept such kind of event from unauthenticated users, does your client implement NIP-42?"));

                broadcastClient(context, gson.toJson(response));

                return this.requestAuthentication(context);
            }
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
            this.broadcastNewEvent(context, gson, eventData);
        } else {
            response.addAll(Arrays.asList(Boolean.FALSE, responseText));
        }

        this.broadcastClient(context, gson.toJson(response));

        if( eventData.getKind() == EventKind.DELETION ) {
            eventService.deletionRequestEvent(eventData);
        }

        return 0;
    }

    private byte handleSubscriptionRegistration(final WebsocketContext context, final JsonArray nostrMessage) {
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();

        final Collection<JsonObject> filters = new ConcurrentLinkedQueue<>();
        for(int i = 2; i < nostrMessage.size(); ++i) {
            final JsonObject entry = nostrMessage.get(i).getAsJsonObject();
            filters.add(entry);
        }

        this.subscriptions.put(subscriptionKey, Boolean.TRUE);
        logger.info("[Nostr] [Subscription] [{}] registered.", subscriptionId);

        if( filters.isEmpty() ) {
            logger.info("[Nostr] [Subscription] [{}] no filters were provided.", subscriptionId);
            final String response = gsonBuilder.create().toJson(Arrays.asList("EOSE", subscriptionId));
            return this.broadcastClient(context, response);
        }

        this.eventProcessor.submit(() -> fetchAndBroadcastEvents(context, subscriptionId, filters));

        return 0;
    }
    

    private byte handleSubscriptionUnregistration(final WebsocketContext context, final JsonArray nostrMessage) {
        final String subscriptionId = nostrMessage.get(1).getAsString();
        final String subscriptionKey = subscriptionId+":"+context.getContextID();
        logger.info("[Nostr] [Message] subscription unregistered: {}", subscriptionId);

        subscriptions.remove(subscriptionKey);

        return 0;
    }

    private byte handleAuthentication(final WebsocketContext context, final JsonArray nostrMessage) {
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

        logger.info("[Nostr] Received request for authentication");

        final Gson gson = gsonBuilder.create();

        final List<Object> response = new ArrayList<>();
        response.add("OK");

        final int now = (int) (System.currentTimeMillis()/1000L);

        if( eventData.getCreatedAt() < (now - 300) || eventData.getCreatedAt() > (now + 300) ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: the event 'created_at' field is out of the acceptable range (-5min, +5min) for this relay."));

            return broadcastClient(context, gson.toJson(response));
        }

        if( EventKind.CLIENT_AUTH != eventData.getKind() ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: the event kind for authentication must be '22242'."));

            return broadcastClient(context, gson.toJson(response));
        }
        
        final boolean[] ok = new boolean[] {true};

        final int serverPort = "wss".equals(this.protocol) ? tlsPort : port;
        final URI expectedFullUri = URI.create(this.protocol+"://"+this.host+":"+serverPort);
        final URI expectedSimpleUri = URI.create(this.protocol+"://"+this.host+(serverPort == 443 || serverPort == 80 ? "" : ":"+serverPort));

        eventData.getTagsByName("relay")
            .stream()
            .filter(tagList -> tagList.size() > 1)
            .map(tagList -> tagList.get(1))
            .peek(tagValue -> logger.info("[Nostr] [Auth] givenUri -> {}", tagValue))
            .map(tagValue -> URI.create(tagValue))
            .forEach(givenUri -> {
                ok[0] = ok[0] && (givenUri.equals(expectedFullUri) || givenUri.equals(expectedSimpleUri));
            });

        synchronized(this.challenges) {
            eventData.getTagsByName("challenge")
            .stream()
            .filter(tagList -> tagList.size() > 1)
            .map(tagList -> tagList.get(1))
            .forEach(challenge -> {
                final Set<String> challengeSet = this.challenges.get(context.getContextID().toString());
                ok[0] = ok[0] && challengeSet.contains(challenge);
                challengeSet.remove(challenge);
            });
        }

        if( !ok[0] ) {
            response.addAll(Arrays.asList(Boolean.FALSE, "invalid: the authentication event does not contain valid 'challenge' or 'relay' tag values."));

            return broadcastClient(context, gson.toJson(response));
        }

        synchronized(this.authUsers)  {
            this.authUsers.get(context.getContextID().toString()).add(eventData.getPubkey());
        }

        response.addAll(Arrays.asList(Boolean.TRUE, ""));
        broadcastClient(context, gson.toJson(response));

        return broadcastClient(context, gson.toJson(Arrays.asList("NOTICE", "Client has been sucessfully authenticated")));
    }

    private String consumeEphemeralEvent(final EventData eventJson) {
        return null;
    }

    private boolean checkAuthentication(final WebsocketContext context, final EventData eventData) {
        // Deixa passar tudo por enquanto
        return true;

        // final Set<String> users;

        // synchronized(this.authUsers) {
        //     users = this.authUsers.getOrDefault(context.getContextID().toString(), Collections.emptySet());
        // }

        // return users.contains(eventData.getPubkey()) || eventData
        //     .getTagsByName("p")
        //     .stream()
        //     .map(tagList -> tagList.get(1))
        //     .filter(pubkey -> users.contains(pubkey))
        //     .count() > 0
        // ;
    }

    private byte requestAuthentication(final WebsocketContext context) {
        final String challenge = Utils.secureHash();

        synchronized(this.challenges) {
            this.challenges.get(context.getContextID().toString()).add(challenge);
        }

        final String auth = gsonBuilder.create().toJson(Arrays.asList("AUTH", challenge));
        return this.broadcastClient(context, auth);
    }

    private EventValidation validate(final String eventJson) throws IOException {
        final Gson gson = gsonBuilder.create();

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

    private byte fetchAndBroadcastEvents(
            final WebsocketContext context,
            final String subscriptionId,
            final Collection<JsonObject> filters
    ) {
        final List<EventData> events = new ArrayList<>();
        this.fetchEventsFromDB(context, events);

        return this.filterAndBroadcastEvents(context, subscriptionId, events, filters);
    }

    private static <T> boolean any(Collection<T> in, Collection<T> from) {
        return from.stream().filter(el -> in.contains(el)).count() > 0;
    }

    private byte filterAndBroadcastEvents(
        final WebsocketContext context,
        final String subscriptionId,
        final Collection<EventData> events,
        final Collection<JsonObject> filters
    ) {
        final Gson gson = gsonBuilder.create();

        boolean notifyUnauthUsers = false;

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
            boolean checkUnauthUsers = filterKindList.contains(EventKind.ENCRYPTED_DIRECT);

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
            emptyFilter = emptyFilter && since[0] == 0;

            final int[] until = new int[] {0};
            Optional
                .ofNullable(entry.get("until"))
                .ifPresent(time -> until[0] = time.getAsInt());
            emptyFilter = emptyFilter && until[0] == 0;

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

                evRefPubKeyList.addAll(
                    eventData.getTagsByName("p")
                        .stream()
                        .map(tagList -> tagList.get(1))
                        .collect(Collectors.toList())
                );

                evRefParamList.addAll(
                    eventData.getTagsByName("d")
                        .stream()
                        .map(tagList -> tagList.get(1))
                        .collect(Collectors.toList())
                );

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
                    if( EventKind.ENCRYPTED_DIRECT == eventData.getKind() ) {
                        notifyUnauthUsers = notifyUnauthUsers || (checkUnauthUsers && !checkAuthentication(context, eventData));
                        continue;
                    }

                    filteredEvents.add(eventData);

                    if( limit[0] > 0 && filteredEvents.size() == limit[0] ) break;
                }

            }

            if( filteredEvents.isEmpty() ) {
                continue;
            }

            filteredEvents.stream().forEach(evt -> {
                if( ! selectedEvents.contains(evt) ) selectedEvents.add(evt);
            });
        }

        if( ! selectedEvents.isEmpty() ) {
            final List<Object> subscriptionResponse = new ArrayList<>();
            subscriptionResponse.addAll(Arrays.asList("EVENT", subscriptionId));
            subscriptionResponse.addAll(
                selectedEvents.stream().map(event -> event.toJson()).collect(Collectors.toList())
            );

            this.broadcastClient(context, gson.toJson(subscriptionResponse));
        }

        this.broadcastClient(context, gson.toJson(Arrays.asList("EOSE", subscriptionId)));

        if( notifyUnauthUsers ) {
            this.broadcastClient(context, gson.toJson(Arrays.asList("NOTICE", "restricted: some kind of events cannot be served by this relay to unauthenticated users, does your client implement NIP-42?")));
            this.requestAuthentication(context);
        }

        return 0;
    }

    private void broadcastNewEvent(
            final WebsocketContext context,
            final Gson gson,
            final EventData eventData
    ) {
        this.subscriptions.keySet()
            .stream()
            .filter(key -> key.endsWith(":"+context.getContextID()))
            .map(key -> key.substring(0, key.lastIndexOf(":")))
            .map(subscriptionId -> Arrays.asList("EVENT", subscriptionId, eventData.toJson()))
            .map(gson::toJson)
            .forEach(jsonResponse -> this.eventProcessor.submit(() ->  context.broadcast(jsonResponse)));
    }
    
}
