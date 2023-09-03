package io.github.social.nostr.relay.def;

import java.util.Collection;
import io.github.social.nostr.relay.service.EventDocumentDataService;
import io.github.social.nostr.relay.specs.EventData;

public interface IEventService {
    static final String DB_ERROR     = "error: Could not connect to database.";
    static final String REG_REQUIRED = "blocked: pubkey must be registered.";

    // public static final IEventService INSTANCE = new EventDiskDataService();
    // public static final IEventService INSTANCE = new EventCacheDataService();
    public static final IEventService INSTANCE = new EventDocumentDataService();

    String checkRegistration(final EventData eventData);

    String persistEvent(final EventData eventData);

    byte persistReplaceable(final EventData eventData);

    String persistParameterizedReplaceable(final EventData eventData);

    byte deletionRequestEvent(final EventData eventData);

    EventData findEvent(final String eventId);

    byte fetchActiveEvents(final Collection<EventData> events);

    byte close();
}
