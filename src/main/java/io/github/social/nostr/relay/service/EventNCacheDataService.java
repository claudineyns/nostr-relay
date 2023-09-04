package io.github.social.nostr.relay.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.IOUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;

import io.github.social.nostr.relay.datasource.CacheService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisException;

public final class EventNCacheDataService extends AbstractCachedEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final CacheService cache = CacheService.INSTANCE;

    public String checkRegistration(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return validateRegistration(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public final Collection<EventData> fetchEventListFromRemote() {
        final Gson gson = new GsonBuilder().create();

        final List<EventData> cacheEvents = new ArrayList<>();
        try {
            final String jsonEvents = fetchRemoteEvents();
            gson.fromJson(jsonEvents, JsonArray.class)
                .forEach(el -> cacheEvents.add(EventData.of(el.getAsJsonObject())) );
        } catch(IOException e) {
            logger.info("[Nostr] [Persistence] Could not fetch remote events: {}", e.getMessage());
        }

        final int currentTime = (int) (System.currentTimeMillis()/1000L);

        for(int i = cacheEvents.size() - 1; i >= 0; --i) {
            final EventData event = cacheEvents.get(i);
            if( event.getExpiration() > 0 && event.getExpiration() < currentTime ) {
                cacheEvents.remove(i);
            }
        }

        return cacheEvents;
    }

    private String validateRegistration(final Jedis jedis, final EventData eventData) {
        final Set<String> registration = jedis.smembers("registration");
        if(registration.contains(eventData.getPubkey())) return null;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(registration.contains(refPubkey)) return null;
        }

        return REG_REQUIRED;
    }

    protected byte proceedToSaveEvent(EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            storeEvent(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
        }
        return 0;
    }

    protected byte proceedToSaveReplaceable(EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            storeReplaceable(jedis, eventData);
        } catch(JedisException e) {
            logger.warning("[Redis] Failure: {}", e.getMessage());
        }

        return 0;
    }

    protected byte proceedToSaveParameterizedReplaceable(final EventData eventData) {
        try (final Jedis jedis = cache.connect()) {
            return storeParameterizedReplaceable(jedis, eventData);
        } catch(JedisException e) {
            return logger.warning("[Redis] Failure: {}", e.getMessage());
        }
    }

    protected byte proceedToRemoveLinkedEvents(EventData eventDeletion) {
        try (final Jedis jedis = cache.connect()) {
            return removeEvents(jedis, eventDeletion);
        } catch(JedisException e) {
            return logger.warning("[Redis] Failure: {}", e.getMessage());
        }
    }

    protected Collection<EventData> proceedToFetchEventList() {
        final Gson gson = new GsonBuilder().create();

        final Collection<EventData> cacheEvents = new ArrayList<>();

        final String jsonEvents;
        try {
            jsonEvents = this.fetchRemoteEvents();
        } catch (IOException e) {
            logger.warning("[Remote] Could not fetch remote data: {}", e.getMessage());
            return Collections.emptyList();
        }

        gson.fromJson(jsonEvents, JsonArray.class)
            .forEach(el -> cacheEvents.add(EventData.of(el.getAsJsonObject())) );

        return cacheEvents;
    }

    protected EventData proceedToFindEvent(String eventId) {
        throw new IllegalCallerException();
    }

    private String storeEvent(final Jedis jedis, EventData eventData) {
        final String cache = "event";

        final String currentDataKey = cache+"#"+eventData.getId();

        final long score = System.currentTimeMillis();

        final String versionKey = cache+"#"+eventData.getId() + ":version";

        final Pipeline pipeline = jedis.pipelined();
        pipeline.sadd(cache+"List", eventData.getId());
        pipeline.set(currentDataKey, eventData.toString());
        pipeline.zadd(versionKey, score, eventData.toString());
        pipeline.sync();

        logger.info("[Event] event {} updated.", eventData.getId());
        return null;
    }

    private String storeReplaceable(final Jedis jedis, final EventData eventData) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        final String data = Utils.sha256(
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

        final String cache = "replaceable";

        final String currentDataKey = cache+"#" + data;
        final String versionKey = currentDataKey + ":version";

        pipeline.sadd(cache+"List", data);
        pipeline.set(currentDataKey, eventData.toString());
        pipeline.zadd(versionKey, score, eventData.toString());

        logger.info("[Replaceable] event {} consumed.", eventData.getId());

        pipeline.sync();

        return null;
    }

    private byte storeParameterizedReplaceable(final Jedis jedis, final EventData eventData) {
        final Pipeline pipeline = jedis.pipelined();

        final long score = System.currentTimeMillis();

        final String cache = "parameter";

        for (final String param : eventData.getInfoNameList()) {
            final String data = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final String currentDataKey = cache+"#" + data;
            final String versionKey = currentDataKey + ":version";

            pipeline.sadd(cache+"List", data);
            pipeline.set(currentDataKey, eventData.toString());
            pipeline.zadd(versionKey, score, eventData.toString());
        }

        logger.info("[Parameter] event {} consumed.", eventData.getId());

        pipeline.sync();

        return 0;
    }
    
    private byte removeEvents(final Jedis jedis, final EventData eventDeletion) {
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
                        && eventDeletion.getReferencedEventList().contains(qEventId)
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

        return logger.info("[Event] events related by event {} has been deleted.", eventDeletion.getId());
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
