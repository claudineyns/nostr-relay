package io.github.social.nostr.relay.service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import io.github.social.nostr.relay.datasource.CacheDS;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.utilities.LogService;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisException;

public final class EventCacheDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheDS cache = CacheDS.INSTANCE;

    boolean validateRegistration(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
            return false;
        }
    }

    Set<String> acquireRegistrationFromStorage() {
        try (final Jedis jedis = cache.connect()) {
            return jedis.smembers("registration");
        } catch(JedisException e) {
            logger.warning("[Redis] Could not fetch registrations: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    boolean validateRegistration(final Jedis jedis, final EventData eventData) {
        final Set<String> registration = jedis.smembers("registration");
        if(registration.contains(eventData.getPubkey())) return true;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(registration.contains(refPubkey)) return true;
        }

        return false;
    }


    byte storeEvent(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return storeEvent(jedis, eventData);
        } catch(JedisException e) {
            return logger.warning("[Redis] Failure: {}", e.getMessage());
        }
    }

    byte removeStoredEvents(Collection<EventData> events) {
        try (final Jedis jedis = cache.connect()) {
            return removeStoredEvents(jedis, events);
        } catch(JedisException e) {
            return logger.warning("[Redis] Failure: {}", e.getMessage());
        }
    }

    Collection<EventData> acquireListFromStorage() {
        try (final Jedis jedis = cache.connect()) {
            return acquireListFromStorage(jedis);
        } catch(JedisException e) {
             logger.warning("[Redis] Failure: {}", e.getMessage());
             return Collections.emptyList();
        }
    }

    EventData acquireEventFromStorageById(final String id) { 
        try (final Jedis jedis = cache.connect()) {
            return this.acquireEventFromStorageById(null, id);
        } catch(JedisException e) {
             logger.warning("[Redis] Failure: {}", e.getMessage());
             return null;
        }
    }

    Collection<EventData> acquireEventsFromStorageByIds(final Set<String> set) { 
        try (final Jedis jedis = cache.connect()) {
            return set
                .stream()
                .map(id -> acquireEventFromStorageById(jedis, id))
                .filter(event -> event != null)
                .collect(Collectors.toList());
        } catch(JedisException e) {
             logger.warning("[Redis] Failure: {}", e.getMessage());
             return null;
        }
    }

    private byte storeEvent(final Jedis jedis, final EventData eventData) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        eventData.storableIds().forEach(paramId -> {
            final String currentKey = "current#"+paramId;

            final Map<String, String> currentData = new HashMap<>();
            currentData.put("status", "inserted");
            currentData.put("payload", eventData.toString());

            final String versionKey = "version#"+paramId;

            pipeline.sadd("idList", paramId);
            pipeline.hset(currentKey, currentData);
            pipeline.zadd(versionKey, score, eventData.toString());
        });

        pipeline.sync();

        logger.info("[Redis] parameterized replaceabe event {} updated.", eventData.getId());

        return 0;
    }

    private byte removeStoredEvents(final Jedis jedis, final Collection<EventData> events) {
        final Pipeline pipeline = jedis.pipelined();

        events.forEach(eventData -> {
            final String currentKey = "current#"+eventData.getId();

            final Map<String, String> currentData = new HashMap<>();
            currentData.put("status", "removed");
            currentData.put("payload", eventData.toString());

            pipeline.srem("regular", eventData.getId());
            pipeline.srem("idList", eventData.getId());
            pipeline.hset(currentKey, currentData);

            logger.info("[Redis] event {} has been removed",  eventData.getId());
        });

        pipeline.sync();

        return 0;
    }

    private EventData acquireEventFromStorageById(final Jedis jedis, final String id) {
        final Map<String, String> eventMap = jedis.hgetAll("current#"+id);

        return eventMap != null && "inserted".equals(eventMap.get("status")) 
            ? EventData.gsonEngine(gsonBuilder.create(), eventMap.get("payload"))
            : null;
    }

    private Collection<EventData> acquireListFromStorage(final Jedis jedis) {
        final Gson gson = gsonBuilder.create();

        return jedis.smembers("idList")
            .stream()
            .map(eventId -> jedis.hgetAll("current#"+eventId))
            .filter(eventMap -> eventMap != null)
            .filter(eventMap -> "inserted".equals(eventMap.get("status")))
            .map(eventMap -> EventData.gsonEngine(gson, eventMap.get("payload")))
            .filter(eventData -> EventKind.DELETION != eventData.getKind() )
            .collect(Collectors.toList());
    }

    public byte close() {
        this.beforeClosing();
        return cache.close();
    }

}
