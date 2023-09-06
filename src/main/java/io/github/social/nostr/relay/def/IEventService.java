package io.github.social.nostr.relay.def;

import java.util.Collection;
import java.util.Set;

import io.github.social.nostr.relay.service.EventDocumentDataService;
import io.github.social.nostr.relay.specs.EventData;

public interface IEventService {
    static final String DB_ERROR     = "error: Could not connect to database.";
    static final String REG_REQUIRED = "blocked: pubkey must be registered.";

    public static final IEventService INSTANCE = new EventDocumentDataService();

    String checkRegistration(final EventData eventData);

    byte persistEvent(final EventData eventData);

    byte persistReplaceable(final EventData eventData);

    String persistParameterizedReplaceable(final EventData eventData);

    String persistParameterizedReplaceable(final EventData eventData, final Set<String> paramIdList);

    byte deletionRequestEvent(final EventData eventData);

    byte fetchActiveEvents(final Collection<EventData> events);

    boolean hasEvent(final EventData eventData);

    boolean checkRequestForRemoval(final EventData eventData);

    EventData getRegular(final String eventId);

    EventData getReplaceable(final String pubkey, final int kind);

    Collection<EventData> getParameterizedReplaceable(final String pubkey, final int kind, final Set<String> param);

    byte close();
}
