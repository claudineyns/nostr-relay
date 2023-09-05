package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import io.github.social.nostr.relay.utilities.Utils;

public abstract class AbstractEventDataService implements IEventService {
    
    protected final GsonBuilder gsonBuilder = new GsonBuilder();

    protected final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    public final byte persistEvent(EventData eventData) {
        final Thread task = new Thread(() -> storeEvent(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return 0;
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

    public EventData getRegular(final String eventId) {
        return this.acquireEventFromStorageById(eventId);
    }

    public EventData getReplaceable(final String pubkey, final int kind) {
        final String replaceable = Utils.sha256((pubkey+"#"+kind).getBytes(StandardCharsets.UTF_8));

        return this.acquireEventFromStorageById(replaceable);
    }

    public Collection<EventData> getParameterizedReplaceable(
            final String pubkey, final int kind, final String... param
        ) {

        final Set<String> set = Arrays.asList(param)
            .stream()
            .map(item -> Utils.sha256((pubkey+"#"+kind+"#"+item).getBytes(StandardCharsets.UTF_8)))
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

    abstract byte storeEvent(final EventData eventData);

    abstract byte storeReplaceable(EventData eventData);

    abstract byte storeParameterizedReplaceable(final EventData eventData);

    abstract byte removeLinkedEvents(EventData eventDeletion);

    abstract Collection<EventData> acquireListFromStorage();

    abstract EventData acquireEventFromStorageById(final String id);

    abstract Collection<EventData> acquireEventsFromStorageByIdSet(final Set<String> id);

}
