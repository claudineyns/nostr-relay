package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.gson.GsonBuilder;

import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.utilities.Utils;

public abstract class AbstractEventDataService implements IEventService {
    
    protected final GsonBuilder gsonBuilder = new GsonBuilder();

    protected final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    private final Map<String, EventData> cached = new HashMap<>();

    private byte putCache(final EventData eventData) {
        synchronized(cached) {
            eventData.storableIds().forEach(id -> cached.put(id, eventData));
        }

        return 0;
    }

    public final byte persistEvent(final EventData eventData) {
        this.putCache(eventData);

        final Thread task = new Thread(() -> storeEvent(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public String persistParameterizedReplaceable(final EventData eventData) {

        this.putCache(eventData);

        final Thread task = new Thread(() -> storeParameterizedReplaceable(eventData, eventData.storableIds()));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public String persistParameterizedReplaceable(final EventData eventData, final Set<String> paramIdList) {
        if(paramIdList.isEmpty()) {
            return "invalid: event is outdated";
        }

        synchronized(cached) {
            paramIdList.stream().forEach(id -> cached.put(id, eventData));
        }

        final Thread task = new Thread(() -> storeParameterizedReplaceable(eventData, paramIdList));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public byte removeEvents(final Collection<EventData> events) {
        synchronized(cached) {
            events.stream().map(event -> event.getId()).forEach(cached::remove);
        }

        final Thread task = new Thread(() -> removeStoredEvents(events, events));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public byte fetchActiveEvents(final Collection<EventData> events) {
        synchronized(cached) {
            if(cached.isEmpty()) {
                fetchEventsFromDatasource()
                    .stream()
                    .filter(event -> EventKind.DELETION != event.getKind())
                    .forEach(event -> event.storableIds().forEach(id -> cached.put(id, event)));
            }
        }

        events.addAll( cached.values().stream().sorted().collect(Collectors.toList()) );

        return 0;
    }

    public EventData getRegular(final String eventId) {
        return this.acquireEventFromStorageById(eventId);
    }

    public EventData getReplaceable(final String pubkey, final int kind) {
        final String replaceable = Utils.sha256((pubkey+"#"+kind).getBytes(StandardCharsets.UTF_8));

        return this.acquireEventFromStorageById(replaceable);
    }

    public Collection<EventData> getParameterizedReplaceable(
            final String pubkey,
            final int kind,
            final Set<String> param
        ) {

        final Set<String> set = param
            .stream()
            .map(item -> idOf(pubkey, kind, item))
            .collect(Collectors.toSet());

        return this.acquireEventsFromStorageByIdSet(set);
    }

    public boolean hasEvent(final EventData eventData) {
        return this.getRegular(eventData.getId()) != null;
    }

    public boolean checkRequestForRemoval(final EventData eventData) {
        return this.fetchDeletionEvents()
            .stream()
            .filter(event -> EventKind.DELETION == event.getKind())
            .filter(event -> event.getReferencedEventList().contains(eventData.getId()))
            .count() > 0;
    }

    private Collection<EventData> fetchEventsFromDatasource() {
        final Collection<EventData> list = new LinkedHashSet<>();

        list.addAll(acquireListFromStorage());

        final int now = (int) (System.currentTimeMillis()/1000L);

        final Set<String> unique = new HashSet<>();
        final List<EventData> events = new ArrayList<>();
        list
            .stream()
            .filter(eventData -> eventData.getExpiration() == 0 || eventData.getExpiration() > now)
            .forEach(eventData -> {
                if( ! unique.contains(eventData.getId()) ) {
                    unique.add(eventData.getId());
                    events.add(eventData);
                }
            });

        return events;
    }

    private Collection<EventData> fetchDeletionEvents() {
        return this.acquireListFromStorage()
            .stream()
            .filter(eventData -> EventKind.DELETION == eventData.getKind())
            .collect(Collectors.toList());
    }

    abstract byte storeEvent(EventData eventData);

    abstract byte storeReplaceable(EventData eventData, String storageId);

    abstract byte storeParameterizedReplaceable(EventData eventData, Set<String> idList);

    abstract byte removeStoredEvents(Collection<EventData> events);

    abstract Collection<EventData> acquireListFromStorage();

    abstract EventData acquireEventFromStorageById(String id);

    abstract Collection<EventData> acquireEventsFromStorageByIdSet(Set<String> id);

}
