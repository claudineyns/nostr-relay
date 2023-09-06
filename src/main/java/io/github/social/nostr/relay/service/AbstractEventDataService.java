package io.github.social.nostr.relay.service;

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

    public byte removeEvents(final Collection<EventData> events) {
        synchronized(cached) {
            events.stream().map(event -> event.getId()).forEach(cached::remove);
        }

        final Thread task = new Thread(() -> removeStoredEvents(events));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    private boolean preloaded = false;
    public byte fetchActiveEvents(final Collection<EventData> events) {
        synchronized(cached) {
            if( !preloaded ) {
                fetchEventsFromDatasource()
                    .stream()
                    .filter(event -> EventKind.DELETION != event.getKind())
                    .forEach(event -> event.storableIds().forEach(id -> cached.put(id, event)));

                preloaded = true;
            }
        }

        events.addAll( cached.values().stream().sorted().collect(Collectors.toList()) );

        return 0;
    }

    public EventData getEvent(final String storedId) {
        return this.acquireEventFromStorageById(storedId);
    }

    public Collection<EventData> getEvents(final Set<String> storedIds) {
        return this.acquireEventsFromStorageByIds(storedIds);
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

    abstract byte removeStoredEvents(Collection<EventData> events);

    abstract Collection<EventData> acquireListFromStorage();

    abstract EventData acquireEventFromStorageById(String id);

    abstract Collection<EventData> acquireEventsFromStorageByIds(Set<String> id);

}
