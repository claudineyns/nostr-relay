package io.github.claudineyns.nostr.relay.def;

import java.util.List;

import com.google.gson.JsonObject;

import io.github.claudineyns.nostr.relay.service.EventDiskDataService;
import io.github.claudineyns.nostr.relay.specs.EventState;

public interface IEventService {
    public static final IEventService INSTANCE = new EventDiskDataService();

    String persistEvent(
            final int kind,
            final String eventId,
            final String authorId,
            final EventState state,
            final String eventJson);

    String persistProfile(final String authorId, final String eventJson);

    String persistParameterizedReplaceable(
        final int kind,
        final String eventId,
        final String authorId,
        final JsonObject eventData,
        final String eventJson
    );

    byte removeEventsByDeletionEvent(
        final String eventId,
        final String authorId,
        final JsonObject deletionEvent
    );

    byte fetchEvents(final List<JsonObject> events);

    byte fetchProfile(final List<JsonObject> events);

    byte fetchParameters(final List<JsonObject> events);

    byte close();
}
