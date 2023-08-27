package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.social.nostr.relay.cache.CacheService;
import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.exceptions.JedisException;

public class EventCacheDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheService cache = CacheService.INSTANCE;

    public String checkRegistration(final String pubkey) {

        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, pubkey);
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

    private String validateRegistration(final Jedis jedis, final String pubkey) {
        return jedis.smembers("registration").contains(pubkey) ? null : REG_REQUIRED;
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

    public byte close() {
        return cache.close();
    }

}
