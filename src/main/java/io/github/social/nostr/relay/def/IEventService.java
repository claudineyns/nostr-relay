package io.github.social.nostr.relay.def;

import java.util.List;

import com.google.gson.JsonObject;

import io.github.social.nostr.relay.service.EventCacheDataService;
import io.github.social.nostr.relay.specs.EventData;

public interface IEventService {
    static final String DB_ERROR     = "error: Could not connect to database.";
    static final String REG_REQUIRED = "blocked: pubkey must be registered.";

    // public static final IEventService INSTANCE = new EventDiskDataService();
    public static final IEventService INSTANCE = new EventCacheDataService();

    String checkRegistration(final String pubkey);

    String persistEvent(final EventData eventData);

    String persistProfile(final String authorId, final String eventJson);

    String persistContactList(final String authorId, final String eventJson);

    String persistParameterizedReplaceable(final EventData eventData);

    byte deletionRequestEvent(final EventData eventData);

    byte fetchEvents(final List<JsonObject> events);

    byte fetchProfile(final List<JsonObject> events);

    byte fetchContactList(final List<JsonObject> events);

    byte fetchParameters(final List<JsonObject> events);

    byte close();
}
