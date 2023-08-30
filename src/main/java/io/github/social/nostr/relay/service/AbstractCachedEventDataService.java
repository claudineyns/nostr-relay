package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public abstract class AbstractCachedEventDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final Map<String, EventData> eventCache = new HashMap<>();

    private final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    protected AbstractCachedEventDataService() {
        Executors.newScheduledThreadPool(1)
            .schedule(()->this.refreshCacheList(), 1500, TimeUnit.MILLISECONDS);
    }

    public final String persistEvent(EventData eventData) {
        synchronized(eventCache) {
            if (EventState.REGULAR.equals(eventData.getState()) && eventCache.containsKey(eventData.getId())) {
                return "duplicate: event has already been registered.";
            }
        }

        final Thread task = new Thread(() -> saveEventAndUpdateCache(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public final byte persistReplaceable(final EventData eventData) {
        final Thread task = new Thread(() -> saveReplaceableAndUpdateCache(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public String persistParameterizedReplaceable(final EventData eventData) {
        if ( eventData.getInfoNameList().isEmpty() ) {
            return "blocked: event must contain 'd' tag entry";
        }

        final Thread task = new Thread(() -> saveParameterizedReplaceableAndUpdateCache(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public byte deletionRequestEvent(final EventData eventDeletion){
        final List<String> linkedEvents = new ArrayList<>();

        eventDeletion.getTags().forEach(tagArray -> {
            if (tagArray.size() < 2) return;

            final String tagName = tagArray.get(0);
            if (!"e".equals(tagName)) return;

            linkedEvents.add(tagArray.get(1));
        });

        final Thread task = new Thread(() -> removeLinkedEventsAndUpdateCache(eventDeletion));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public byte fetchActiveEvents(Collection<EventData> events) {
        synchronized(this.eventCache) {
            if( this.eventCache.isEmpty() ) {
                this.fetchAndParseEventList();
            }

            events.addAll(new TreeSet<>(this.eventCache.values()));
        }

        return 0;
    }

    private Collection<EventData> fetchAndParseEventList() {
        final Set<EventData> cacheEvents = new TreeSet<>(this.proceedToFetchEventList());

        final int currentTime = (int) (System.currentTimeMillis()/1000L);

        return cacheEvents
            .stream()
            .filter(q -> q.getExpiration() == 0 || q.getExpiration() > currentTime)
            .collect(Collectors.toList());
    }

    private byte refreshCacheList() {
        final Collection<EventData> eventList = this.fetchAndParseEventList();

        synchronized(this.eventCache) {
            this.eventCache.clear();
            eventList.forEach(this::updateCacheEntry);
        }

        return logger.info("[Task] Cache updated.");
    }

    private byte saveEventAndUpdateCache(final EventData eventData) {
        this.proceedToSaveEvent(eventData);

        return this.syncEventCache(eventData);
    }

    private byte saveReplaceableAndUpdateCache(final EventData eventData) {
        this.proceedToSaveReplaceable(eventData);        

        return this.syncEventCache(eventData);
    }

    private byte saveParameterizedReplaceableAndUpdateCache(final EventData eventData) {
        this.proceedToSaveParameterizedReplaceable(eventData);

        return this.syncEventCache(eventData);
    }

    private byte removeLinkedEventsAndUpdateCache(final EventData eventDeletion) {
        this.proceedToRemoveLinkedEvents(eventDeletion);

        return this.refreshCacheList();
    }

    private byte syncEventCache(final EventData eventData) {
        synchronized(this.eventCache) {
            return this.updateCacheEntry(eventData);
        }
    }

    private byte updateCacheEntry(final EventData eventData ) {
        if( EventState.REGULAR.equals(eventData.getState()) ) {

            this.eventCache.put(eventData.getId(), eventData);

        } else if( EventState.REPLACEABLE.equals(eventData.getState()) ) {

            final String data = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
            );
            this.eventCache.put(data, eventData);

        } else if( EventState.PARAMETERIZED_REPLACEABLE.equals(eventData.getState()) ) {

            eventData.getInfoNameList().forEach(d -> {
                final String data = Utils.sha256(
                    (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
                );
                this.eventCache.put(data, eventData);
            });

        }

        return 0;
    }

    protected abstract byte proceedToSaveEvent(final EventData eventData);

    protected abstract byte proceedToSaveReplaceable(final EventData eventData);

    protected abstract byte proceedToSaveParameterizedReplaceable(final EventData eventData);

    protected abstract byte proceedToRemoveLinkedEvents(final EventData eventDeletion);

    protected abstract Collection<EventData> proceedToFetchEventList();
    
}
