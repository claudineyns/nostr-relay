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

@SuppressWarnings("unused")
public abstract class AbstractEventDataService implements IEventService {
    
    protected final GsonBuilder gsonBuilder = new GsonBuilder();

    protected final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    private final Map<String, EventData> cached = new HashMap<>();
    private final Map<String, EventData> deletion = new HashMap<>();

    public byte start() {
        synchronized(cached) {
            fetchEventsFromDatasource()
                .stream()
                .forEach(event ->  {
                    if( EventKind.DELETION == event.getKind() )  {
                        deletion.put(event.getId(), event);
                    } else {
                        event.storableIds().forEach(id -> cached.put(id, event));
                    }
                });
        }

        return 0;
    }

    private byte putCache(final EventData eventData) {
        synchronized(cached) {
            if(eventData.getKind() == EventKind.DELETION) {
                deletion.putIfAbsent(eventData.getId(), eventData);
            } else {
                eventData.storableIds().forEach(id -> cached.put(id, eventData));
            }
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

    public byte fetchActiveEvents(final Collection<EventData> events) {
        events.addAll( cached.values().stream().sorted().collect(Collectors.toList()) );

        return 0;
    }

    public EventData getEvent(final String storedId) {
        // return this.acquireEventFromStorageById(storedId);

        synchronized(cached) {
            return cached.get(storedId);
        }
    }

    public Collection<EventData> getEvents(final Set<String> storedIds) {
        return this.acquireEventsFromStorageByIds(storedIds);
    }

    public boolean checkRequestForRemoval(final EventData eventData) {
        // return this.fetchDeletionEvents()
        //     .stream()
        //     .filter(event -> EventKind.DELETION == event.getKind())
        //     .filter(event -> event.getReferencedEventList().contains(eventData.getId()))
        //     .count() > 0;

        synchronized(cached) {
            return this.deletion.values()
                .stream()
                .filter(event -> event.getReferencedEventList().contains(eventData.getId()))
                .count() > 0;
        }
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
