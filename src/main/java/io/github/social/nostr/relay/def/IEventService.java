package io.github.social.nostr.relay.def;

import java.util.Collection;
import java.util.Set;

import io.github.social.nostr.relay.service.EventDocumentDataService;
import io.github.social.nostr.relay.specs.EventData;

public interface IEventService {
    static final String DB_ERROR     = "error: Could not connect to database.";
    static final String REG_REQUIRED = "blocked: pubkey must be registered.";

    public static final IEventService INSTANCE = new EventDocumentDataService();

    byte start();

    boolean isRegistered(final EventData eventData);

    byte persistEvent(final EventData eventData);

    EventData getEvent(final String storedId);

    Collection<EventData> getEvents(Set<String> storedIds);

    byte removeEvents(final Collection<EventData> events);

    byte fetchActiveEvents(final Collection<EventData> events);

    boolean checkRequestForRemoval(final EventData eventData);

    byte close();
}
