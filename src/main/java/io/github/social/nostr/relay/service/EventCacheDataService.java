package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
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
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisException;

public final class EventCacheDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheDS cache = CacheDS.INSTANCE;

    public String checkRegistration(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
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

    byte storeEvent(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            storeEvent(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
        }
        return 0;
    }

    byte storeReplaceable(EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            storeReplaceable(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
        }

        return 0;
    }

    byte storeParameterizedReplaceable(final EventData eventData, final Set<String> idList) {
        try (final Jedis jedis = cache.connect()) {
            return storeParameterizedReplaceable(jedis, eventData, idList);
        } catch(JedisException e) {
            return logger.warning("[Redis] Failure: {}", e.getMessage());
        }
    }

    byte removeLinkedEvents(EventData eventDeletion) {
        try (final Jedis jedis = cache.connect()) {
            return removeEvents(jedis, eventDeletion);
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

    Collection<EventData> acquireEventsFromStorageByIdSet(final Set<String> set) { 
        try (final Jedis jedis = cache.connect()) {
            return set
                .stream()
                .map(id -> acquireEventFromStorageById(jedis, id))
                .collect(Collectors.toList());
        } catch(JedisException e) {
             logger.warning("[Redis] Failure: {}", e.getMessage());
             return null;
        }
    }

    private String storeEvent(final Jedis jedis, EventData eventData) {
        final long score = System.currentTimeMillis();

        final Pipeline pipeline = jedis.pipelined();

        final String currentKey = "current#"+eventData.getId();

        final Map<String, String> currentData = new HashMap<>();
        currentData.put("status", "inserted");
        currentData.put("payload", eventData.toString());

        final String versionKey = "version#"+eventData.getId();

        pipeline.sadd("regular", eventData.getId());
        pipeline.sadd("idList", eventData.getId());
        pipeline.hset(currentKey, currentData);
        pipeline.zadd(versionKey, score, eventData.toString());
        pipeline.sync();

        logger.info("[Redis] event {} updated.", eventData.getId());
        return null;
    }

    private String storeReplaceable(final Jedis jedis, final EventData eventData) {
        final String data = Utils.sha256(
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

        final Pipeline pipeline = jedis.pipelined();

        final String currentKey = "current#"+data;

        final Map<String, String> currentData = new HashMap<>();
        currentData.put("status", "inserted");
        currentData.put("payload", eventData.toString());

        final String versionKey = "version#"+data;
        final long score = System.currentTimeMillis();

        pipeline.sadd("idList", data);
        pipeline.hset(currentKey, currentData);
        pipeline.zadd(versionKey, score, eventData.toString());
        pipeline.sync();

        logger.info("[Redis] replaceable event {} updated.", eventData.getId());
        return null;
    }

    private byte storeParameterizedReplaceable(
            final Jedis jedis,
            final EventData eventData,
            final Set<String> idList
    ) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        idList.forEach(paramId -> {
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
    
    private byte removeEvents(final Jedis jedis, final EventData eventDeletion) {
        final Gson gson = gsonBuilder.create();

        final Collection<EventData> events = jedis.smembers("regular")
            .stream()
            .map(eventId -> jedis.hgetAll("current#"+eventId))
            .filter(eventMap -> eventMap != null)
            .filter(eventMap -> "inserted".equals(eventMap.get("status")))
            .map(eventMap -> EventData.gsonEngine(gson, eventMap.get("payload")))
            .filter(eventData -> EventState.REGULAR.equals(eventData.getState()))
            .filter(eventData -> eventDeletion.getPubkey().equals(eventData.getPubkey()))
            .filter(eventData -> EventKind.DELETION != eventData.getKind() )
            .collect(Collectors.toList());

        final Pipeline pipeline = jedis.pipelined();

        events.forEach(eventData -> {
            final String currentKey = "current#"+eventData.getId();

            final Map<String, String> currentData = new HashMap<>();
            currentData.put("status", "removed");
            currentData.put("payload", eventData.toString());

            pipeline.srem("regular", eventData.getId());
            pipeline.srem("idList", eventData.getId());
            pipeline.hset(currentKey, currentData);

            logger.info("[Redis] event {} has been removed by event deletion {}",  eventData.getId(), eventDeletion.getId());
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
        return cache.close();
    }

}
