package io.github.social.nostr.relay.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.gson.GsonBuilder;

import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;

public abstract class AbstractEventDataService implements IEventService {
    
    protected final GsonBuilder gsonBuilder = new GsonBuilder();

    protected final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    public final String persistEvent(EventData eventData) {
        if (EventState.REGULAR.equals(eventData.getState())) {
            if ( this.hasEvent(eventData)) {
                return "duplicate: event has already been stored.";
            }
            if( this.checkRequestForRemoval(eventData) ) {
                return "invalid: this event has already been requested to be removed from this relay.";
            }
        }

        final Thread task = new Thread(() -> storeEvent(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public byte persistReplaceable(EventData eventData) {
        final Thread task = new Thread(() -> storeReplaceable(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public String persistParameterizedReplaceable(EventData eventData) {
        if(eventData.getInfoNameList().isEmpty()) {
            return "invalid: event must contain tag 'd'";
        }

        final Thread task = new Thread(() -> storeParameterizedReplaceable(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
    }

    public byte deletionRequestEvent(EventData eventData) {
        final Thread task = new Thread(() -> removeLinkedEvents(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
    }

    public byte fetchActiveEvents(final Collection<EventData> events) {
        events.addAll(
            fetchEventsFromDatasource()
                .stream()
                .filter(event -> EventKind.DELETION != event.getKind())
                .sorted()
                .collect(Collectors.toList())
        );

        return 0;
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

        Collections.sort(events);

        return events;
    }

    private boolean hasEvent(final EventData eventData) {
        return this.findEvent(eventData.getId()) != null;
    }

    private EventData findEvent(final String eventId) {
        return this.acquireEventFromStorage(eventId);
    }

    private boolean checkRequestForRemoval(final EventData eventData) {
        return this.fetchDeletionEvents()
            .stream()
            .filter(event -> EventKind.DELETION == event.getKind())
            .filter(event -> event.getReferencedEventList().contains(eventData.getId()))
            .count() > 0;
    }

    private Collection<EventData> fetchDeletionEvents() {
        return this.acquireListFromStorage()
            .stream()
            .filter(eventData -> EventKind.DELETION == eventData.getKind())
            .collect(Collectors.toList());
    }

    abstract byte storeEvent(final EventData eventData);

    abstract byte storeReplaceable(EventData eventData);

    abstract byte storeParameterizedReplaceable(final EventData eventData);

    abstract byte removeLinkedEvents(EventData eventDeletion);

    abstract Collection<EventData> acquireListFromStorage();

    abstract EventData acquireEventFromStorage(final String eventId);

}
