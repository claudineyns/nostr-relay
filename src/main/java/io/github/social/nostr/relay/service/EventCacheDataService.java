package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.social.nostr.relay.cache.CacheService;
import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisException;

public class EventCacheDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheService cache = CacheService.INSTANCE;

    public synchronized String checkRegistration(final String pubkey) {

        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, pubkey);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public synchronized String persistEvent(
            final int kind,
            final String eventId,
            final String authorId,
            final EventState state,
            final String eventJson
    ) {
        try (final Jedis jedis = cache.connect()) {
            return saveEvent(jedis, kind, eventId, authorId, state, eventJson);
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

    public synchronized String persistParameterizedReplaceable(
            final int kind,
            final String eventId,
            final String authorId,
            final JsonObject eventData,
            final String eventJson) {
        final List<String> dTagList = new ArrayList<>();
        Optional.ofNullable(eventData.get("tags"))
                .ifPresent(tagEL -> tagEL.getAsJsonArray().forEach(tagEntry -> {
                    final JsonArray tagArray = tagEntry.getAsJsonArray();
                    if (tagArray.size() < 2)
                        return;

                    final String tagName = tagArray.get(0).getAsString();
                    if (!"d".equals(tagName))
                        return;

                    dTagList.add(tagArray.get(1).getAsString());
                }));

        if (dTagList.isEmpty()) {
            return "blocked: event must contain 'd' tag entry";
        }

        try (final Jedis jedis = cache.connect()) {
            return saveParameterizedReplaceable(jedis, kind, eventId, authorId, eventData, eventJson, dTagList);
        } catch(JedisException e) {
            logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public byte removeEventsByDeletionEvent(
        final String eventId,
        final String authorId,
        final JsonObject deletionEvent
    ) {
        
        final List<String> linkedEvents = new ArrayList<>();
        Optional
            .ofNullable(deletionEvent.get("tags"))
            .ifPresent(element -> element
                .getAsJsonArray()
                .forEach(entry -> {
                    final JsonArray subItem = entry.getAsJsonArray();
                    final String tagName = subItem.get(0).getAsString();
                    if( ! "e".equals(tagName) ) return;

                    linkedEvents.add(subItem.get(1).getAsString());
                })
        );

        try (final Jedis jedis = cache.connect()) {
            return removeEvents(jedis, eventId, authorId, deletionEvent, linkedEvents);
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchEvents(final List<JsonObject> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchCurrent(jedis, events, "event");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchProfile(final List<JsonObject> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchCurrent(jedis, events, "profile");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    public byte fetchContactList(final List<JsonObject> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchCurrent(jedis, events, "contact");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }
    public byte fetchParameters(final List<JsonObject> events) {
        try (final Jedis jedis = cache.connect()) {
            return this.fetchCurrent(jedis, events, "parameter");
        } catch(JedisException e) {
            return logger.warning("[Nostr] [Persistence] [Redis] Failure: {}", e.getMessage());
        }
    }

    private String validateRegistration(final Jedis jedis, final String pubkey) {
        return jedis.smembers("registration").contains(pubkey) ? null : REG_REQUIRED;
    }

    private String saveEvent(
            final Jedis jedis,
            final int kind,
            final String eventId,
            final String authorId,
            final EventState state,
            final String event) {

        final String currentDataKey = "event#" + eventId;

        if (EventState.REGULAR.equals(state) && jedis.exists(currentDataKey)) {
            return "duplicate: event has already been registered.";
        }

        final long score = System.currentTimeMillis();
        final String versionKey = "event#" + eventId + ":version";

        final Pipeline pipeline = jedis.pipelined();
        pipeline.sadd("eventList", eventId);
        pipeline.set(currentDataKey, event);
        pipeline.zadd(versionKey, score, event);
        pipeline.sync();

        logger.info("[Nostr] [Persistence] [Event] event {} updated.", eventId);
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
            final int kind,
            final String eventId,
            final String authorId,
            final JsonObject eventData,
            final String event,
            final List<String> dTagList
    ) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        for (final String param : dTagList) {
            final String data = Utils.sha256((authorId + "#" + kind + "#" + param).getBytes(StandardCharsets.UTF_8));

            final String currentDataKey = "parameter#" + data;
            final String versionKey = "parameter#" + param + ":version";

            pipeline.sadd("parameterList", data);
            pipeline.set(currentDataKey, event);
            pipeline.zadd(versionKey, score, event);
        }

        logger.info("[Nostr] [Persistence] [Parameter] event {} consumed.", eventId);

        pipeline.sync();

        return null;
    }

    private byte removeEvents(
        final Jedis jedis,
        final String deletionEventId,
        final String authorId,
        final JsonObject deletionEvent,
        final List<String> linkedEvents
    ) {
        final Gson gson = new GsonBuilder().create();

        final List<JsonObject> eventsMarkedForDeletion = new ArrayList<>();

        final Set<String> eventIds = jedis.smembers("eventList");

        for(final String eventId: eventIds) {
            Optional.ofNullable(jedis.get("event#"+eventId)).ifPresent(event -> {
                final JsonObject data = gson.fromJson(event, JsonObject.class);

                final String qAuthorId = data.get("pubkey").getAsString();
                final String qEventId  = data.get("id").getAsString();
                final int qEventKind   = data.get("kind").getAsInt();
                final EventState state = EventState.byKind(qEventKind);

                if( EventState.REGULAR.equals(state) 
                        && qEventKind != EventKind.DELETION
                        && qAuthorId.equals(authorId)
                        && linkedEvents.contains(qEventId)
                ) {
                    eventsMarkedForDeletion.add(data);
                }
            });
        }

        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        eventsMarkedForDeletion.stream().forEach(event -> {
            final String eventId = event.get("id").getAsString();

            final String dataKey = "event#" + eventId;
            final String versionKey = "event#" + eventId + ":version";

            pipeline.zadd(versionKey, score, String.format("{\"id\":\"%s\"}", eventId));
            pipeline.del(dataKey);

        });

        pipeline.sync();

        logger.info("[Nostr] [Persistence] [Event] events related by event {} has been deleted.", deletionEventId);
        return 0;
    }

    private byte fetchCurrent(final Jedis jedis, final List<JsonObject> events, final String cache) {
        final Gson gson = new GsonBuilder().create();

        jedis.smembers(cache+"List").stream().forEach(id -> 
             Optional.ofNullable(jedis.get(cache+"#" + id)).ifPresent(event -> 
                events.add(gson.fromJson(event, JsonObject.class))
             )
        );

        return 0;
    }

    public byte close() {
        return cache.close();
    }

}
