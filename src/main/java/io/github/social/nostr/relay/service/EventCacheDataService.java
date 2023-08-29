package io.github.social.nostr.relay.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import io.github.social.nostr.relay.cache.CacheService;
import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisException;

public class EventCacheDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheService cache = CacheService.INSTANCE;

    public String checkRegistration(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public String persistEvent(EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return saveEvent(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public String persistProfile(String authorId, String eventJson) {
        try (final Jedis jedis = cache.connect()) {
            return saveProfile(jedis, authorId, eventJson);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public String persistContactList(String authorId, String eventJson) {
        try (final Jedis jedis = cache.connect()) {
            return saveContactList(jedis, authorId, eventJson);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public String persistParameterizedReplaceable(final EventData eventData) {
        final List<String> dTagList = new ArrayList<>();

        eventData.getTags().forEach(tagArray -> {
            if (tagArray.size() < 2) return;

            final String tagName = tagArray.get(0);
            if (!"d".equals(tagName)) return;

            dTagList.add(tagArray.get(1));
        });

        if (dTagList.isEmpty()) {
            return "blocked: event must contain 'd' tag entry";
        }

        try (final Jedis jedis = cache.connect()) {
            return saveParameterizedReplaceable(jedis, eventData, dTagList);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public byte deletionRequestEvent(final EventData eventDeletion){
        
        final List<String> linkedEvents = new ArrayList<>();

        eventDeletion.getTags().forEach(tagArray -> {
            if (tagArray.size() < 2) return;

            final String tagName = tagArray.get(0);
            if (!"e".equals(tagName)) return;

            linkedEvents.add(tagArray.get(1));
        });

        try (final Jedis jedis = cache.connect()) {
            return removeEvents(jedis, eventDeletion, linkedEvents);
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchActiveEvents(List<EventData> events) {
        final Gson gson = new GsonBuilder().create();

        final List<EventData> cacheEvents = new ArrayList<>();
        try {
            final String jsonEvents = fetchRemoteEvents();
            gson.fromJson(jsonEvents, JsonArray.class)
                .forEach(el -> cacheEvents.add(EventData.of(el.getAsJsonObject())) );
        } catch(IOException e) {
            logger.info("[Nostr] [Persistence] Could not fetch remote events: {}", e.getMessage());
        }
        
        // this.fetchEvents(cacheEvents);
        // this.fetchProfile(cacheEvents);
        // this.fetchContactList(cacheEvents);
        // this.fetchParameters(cacheEvents);

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

    public byte fetchEvents(final List<EventData> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchList(jedis, events, "event");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchProfile(final List<EventData> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchList(jedis, events, "profile");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchContactList(final List<EventData> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchList(jedis, events, "contact");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }
    public byte fetchParameters(final List<EventData> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchList(jedis, events, "parameter");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    private String validateRegistration(final Jedis jedis, final EventData eventData) {
        final Set<String> registration = jedis.smembers("registration");
        if(registration.contains(eventData.getPubkey())) return null;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(registration.contains(refPubkey)) return null;
        }

        return REG_REQUIRED;
    }

    private String saveEvent(final Jedis jedis, EventData eventData) {
        final String currentDataKey = "event#" + eventData.getId();

        if (EventState.REGULAR.equals(eventData.getState()) && jedis.exists(currentDataKey)) {
            return "duplicate: event has already been registered.";
        }

        final long score = System.currentTimeMillis();
        final String versionKey = "event#" + eventData.getId() + ":version";

        final Pipeline pipeline = jedis.pipelined();
        pipeline.sadd("eventList", eventData.getId());
        pipeline.set(currentDataKey, eventData.toString());
        pipeline.zadd(versionKey, score, eventData.toString());
        pipeline.sync();

        logger.info("[Nostr] [Persistence] [Event] event {} updated.", eventData.getId());
        return null;
    }

    private String saveProfile(final Jedis jedis, final String pubkey, final String event) {
        final String currentDataKey = "profile#" + pubkey;

        final long score = System.currentTimeMillis();
        final String versionKey = "profile#" + pubkey + ":version";

        final Pipeline pipeline = jedis.pipelined();
        pipeline.sadd("profileList", pubkey);
        pipeline.set(currentDataKey, event);
        pipeline.zadd(versionKey, score, event);
        pipeline.sync();

        logger.info("[Nostr] [Persistence] [Profile] author {} updated.", pubkey);
        return null;
    }

    private String saveContactList(final Jedis jedis, final String pubkey, final String event) {
        final String currentDataKey = "contact#" + pubkey;

        final long score = System.currentTimeMillis();
        final String versionKey = "contact#" + pubkey + ":version";

        final Pipeline pipeline = jedis.pipelined();
        pipeline.sadd("contactList", pubkey);
        pipeline.set(currentDataKey, event);
        pipeline.zadd(versionKey, score, event);
        pipeline.sync();

        logger.info("[Nostr] [Persistence] [Contact] data by author {} updated.", pubkey);
        return null;
    }

    private String saveParameterizedReplaceable(
            final Jedis jedis,
            final EventData eventData,
            final List<String> dTagList
    ) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        for (final String param : dTagList) {
            final String data = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final String currentDataKey = "parameter#" + data;
            final String versionKey = "parameter#" + param + ":version";

            pipeline.sadd("parameterList", data);
            pipeline.set(currentDataKey, eventData.toString());
            pipeline.zadd(versionKey, score, eventData.toString());
        }

        logger.info("[Nostr] [Persistence] [Parameter] event {} consumed.", eventData.getId());

        pipeline.sync();

        return null;
    }

    private byte removeEvents(
        final Jedis jedis,
        final EventData eventDeletion,
        final List<String> linkedEvents
    ) {
        final Gson gson = new GsonBuilder().create();

        final List<EventData> eventsMarkedForDeletion = new ArrayList<>();

        final Set<String> eventIds = jedis.smembers("eventList");

        for(final String eventId: eventIds) {
            Optional.ofNullable(jedis.get("event#"+eventId)).ifPresent(event -> {
                final EventData eventData = EventData.gsonEngine(gson, event);

                final String qAuthorId = eventData.getPubkey();
                final String qEventId  = eventData.getId();
                final int qEventKind   = eventData.getKind();

                if( EventState.REGULAR.equals(eventData.getState()) 
                        && qEventKind != EventKind.DELETION
                        && qAuthorId.equals(eventDeletion.getPubkey())
                        && linkedEvents.contains(qEventId)
                ) {
                    eventsMarkedForDeletion.add(eventData);
                }
            });
        }

        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        eventsMarkedForDeletion.stream().forEach(eventData -> {
            final String eventId = eventData.getId();

            final String dataKey = "event#" + eventId;
            final String versionKey = "event#" + eventId + ":version";

            pipeline.sadd("eventRemovedList", eventId);
            pipeline.zadd(versionKey, score, String.format("{\"id\":\"%s\"}", eventId));
            pipeline.srem("eventList", eventId);
            pipeline.del(dataKey);
        });

        pipeline.sync();

        return logger.info("[Nostr] [Persistence] [Event] events related by event {} has been deleted.", eventDeletion.getId());
    }

    private byte fetchList(final Jedis jedis, final List<EventData> events, final String cache) {
        final Gson gson = new GsonBuilder().create();

        final Set<String> ids = jedis.smembers(cache+"List");

        final List<Response<String>> responses = new ArrayList<>();

        final Pipeline pipeline = jedis.pipelined();
        ids.stream().forEach(id -> responses.add(pipeline.get(cache+"#"+id)));
        pipeline.sync();

        responses.forEach(rsp -> 
            Optional.ofNullable(rsp.get()).ifPresent(event -> 
                events.add(EventData.gsonEngine(gson, event))
            )
        );

        return 0;
    }

    private final String validationHost = AppProperties.getEventValidationHost();
    private final int validationPort = AppProperties.getEventValidationPort();

    private String fetchRemoteEvents() throws IOException {
        final URL url = new URL("http://"+validationHost+":"+validationPort+"/event/activeList");
        final HttpURLConnection http = (HttpURLConnection) url.openConnection();

        http.setRequestMethod("GET");
        http.setDoOutput(true);
        http.setInstanceFollowRedirects(false);

        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Connection", "close");

        final InputStream in = http.getInputStream();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        IOUtils.copy(in, out);

        http.disconnect();

        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public byte close() {
        return cache.close();
    }

}
