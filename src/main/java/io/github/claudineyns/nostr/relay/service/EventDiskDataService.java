package io.github.claudineyns.nostr.relay.service;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.claudineyns.nostr.relay.def.IEventService;
import io.github.claudineyns.nostr.relay.specs.EventKind;
import io.github.claudineyns.nostr.relay.specs.EventState;
import io.github.claudineyns.nostr.relay.utilities.LogService;
import io.github.claudineyns.nostr.relay.utilities.Utils;

public class EventDiskDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File directory = new File("/var/nostr/data/");

    public synchronized String persistEvent(
        final int kind,
        final String eventId,
        final String authorId,
        final EventState state,
        final String eventJson
    ) {
        final File eventDB = new File(directory, "/events/"+eventId);
        if( EventState.REGULAR.equals(state) && eventDB.exists() ) {
            return "duplicate: event has already been registered.";
        }

        final File eventVersionDB = new File(eventDB, "/version");
        if ( ! eventVersionDB.exists() ) eventVersionDB.mkdirs();
        final File eventVersion = new File(eventVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream eventRecord = new FileOutputStream(eventVersion)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Event] Version saved");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not save version: {}", failure.getMessage());
            return "error: Could not update database.";
        }

        final File eventCurrentDB = new File(eventDB, "/current");
        if( ! eventCurrentDB.exists() ) eventCurrentDB.mkdirs();
        final File eventData = new File(eventCurrentDB, "data.json");
        try (final OutputStream eventRecord = new FileOutputStream(eventData)) {
            eventRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Event] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not update data: {}", failure.getMessage());
        }

        return null;
    }

    public synchronized String persistProfile(final String authorId, final String eventJson) {
        final File profileDB = new File(directory, "/profile/"+authorId);

        if( ! profileDB.exists() ) profileDB.mkdirs();

        final File profileVersionDB = new File(profileDB, "/version");
        if ( ! profileVersionDB.exists() ) profileVersionDB.mkdirs();
        final File profileVersion = new File(profileVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream profileRecord = new FileOutputStream(profileVersion)) {
            profileRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Profile] Version saved");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Profile] Could not save version: {}", failure.getMessage());
            return "error: Could not update database.";
        }

        final File profileCurrentDB = new File(profileDB, "/current");
        if( ! profileCurrentDB.exists() ) profileCurrentDB.mkdirs();
        final File profileData = new File(profileCurrentDB, "data.json");
        try (final OutputStream profileRecord = new FileOutputStream(profileData)) {
            profileRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Profile] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Profile] Could not update data: {}", failure.getMessage());
            return "error: Could not update database.";
        }

        return null;
    }

    public synchronized String persistContactList(final String authorId, final String eventJson) {
        final File contactDB = new File(directory, "/contact/"+authorId);

        if( ! contactDB.exists() ) contactDB.mkdirs();

        final File contactVersionDB = new File(contactDB, "/version");
        if ( ! contactVersionDB.exists() ) contactVersionDB.mkdirs();
        final File contactVersion = new File(contactVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream contactRecord = new FileOutputStream(contactVersion)) {
            contactRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Contact] Version saved");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Contact] Could not save version: {}", failure.getMessage());
            return "error: Could not update database.";
        }

        final File contactCurrentDB = new File(contactDB, "/current");
        if( ! contactCurrentDB.exists() ) contactCurrentDB.mkdirs();
        final File profileData = new File(contactCurrentDB, "data.json");
        try (final OutputStream profileRecord = new FileOutputStream(profileData)) {
            profileRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
            logger.info("[Nostr] [Persistence] [Contact] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Contact] Could not update data: {}", failure.getMessage());
            return "error: Could not update database.";
        }

        return null;
    }

    public synchronized String persistParameterizedReplaceable(
        final int kind,
        final String eventId,
        final String authorId,
        final JsonObject eventData,
        final String eventJson
    ) {
        final List<String> dTagList = new ArrayList<>();
        Optional.ofNullable(eventData.get("tags"))
        .ifPresent(tagEL -> tagEL.getAsJsonArray().forEach(tagEntry -> {
            final JsonArray tagArray = tagEntry.getAsJsonArray();
            if(tagArray.size() < 2) return;

            final String tagName = tagArray.get(0).getAsString();
            if( !"d".equals(tagName)) return;

            dTagList.add(tagArray.get(1).getAsString());
        }));

        if( dTagList.isEmpty() ) {
            return "blocked: event must contain 'd' tag entry";
        }

        for(final String param: dTagList) {
            final String data = Utils.sha256 ((authorId+"#"+kind+"#"+param).getBytes(StandardCharsets.UTF_8) );

            final File dataDB = new File(directory, "/parameter/"+data);
            if( ! dataDB.exists() ) dataDB.mkdirs();

            final File dataVersionDB = new File(dataDB, "/version");
            if ( ! dataVersionDB.exists() ) dataVersionDB.mkdirs();

            final File paramVersion = new File(dataVersionDB, "data-" + System.currentTimeMillis() + ".json");
            try (final OutputStream paramRecord = new FileOutputStream(paramVersion)) {
                paramRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
                logger.info("[Nostr] [Persistence] [Parameter] Version saved");
            } catch(IOException failure) {
                logger.warning("[Nostr] [Persistence] [Parameter] Could not save version: {}", failure.getMessage());
                return "error: Could not update database.";
            }

            final File dataCurrentDB = new File(dataDB, "/current");
            if ( ! dataCurrentDB.exists() ) dataCurrentDB.mkdirs();

            final File contentData = new File(dataCurrentDB, "data.json");
            try (final OutputStream paramRecord = new FileOutputStream(contentData)) {
                paramRecord.write(eventJson.getBytes(StandardCharsets.UTF_8));
                logger.info("[Nostr] [Persistence] [Parameter] data updated");
            } catch(IOException failure) {
                logger.warning("[Nostr] [Persistence] [Parameter] Could not update data: {}", failure.getMessage());
                return "error: Could not update database.";
            }

        }

        return null;
    }

    public byte removeEventsByDeletionEvent(
        final String eventId,
        final String authorId,
        final JsonObject deletionEvent
    ) {
        final Gson gson = new GsonBuilder().create();
        final List<String> linkedEventId = new ArrayList<>();
        Optional
            .ofNullable(deletionEvent.get("tags"))
            .ifPresent(element -> element
                .getAsJsonArray()
                .forEach(entry -> {
                    final JsonArray subItem = entry.getAsJsonArray();
                    final String tagName = subItem.get(0).getAsString();
                    if( ! "e".equals(tagName) ) return;

                    linkedEventId.add(subItem.get(1).getAsString());
                })
        );

        final File eventDB = new File(directory, "/events");

        final List<JsonObject> eventsMarkedForDeletion = new ArrayList<>();
        eventDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( !pathname.isDirectory() ) return false;

                final File eventFile = new File(pathname, "/current/data.json");
                if (!eventFile.exists()) return false;

                try (final InputStream in = new FileInputStream(eventFile) ) {
                    final JsonObject data = gson.fromJson(new InputStreamReader(in), JsonObject.class);

                    final String qAuthorId = data.get("pubkey").getAsString();
                    final String qEventId  = data.get("id").getAsString();
                    final int qEventKind   = data.get("kind").getAsInt();
                    final EventState state = EventState.byKind(qEventKind);

                    if( EventState.REGULAR.equals(state) 
                            && qEventKind != EventKind.DELETION
                            && qAuthorId.equals(authorId)
                            && linkedEventId.contains(qEventId)
                    ) {
                        eventsMarkedForDeletion.add(data);
                    }
                } catch(IOException failure) {
                    logger.warning("[Nostr] [Persistence] [Event] Could not load event: {}", failure.getMessage());
                }

                return false;
            }
        });

        eventsMarkedForDeletion.stream().forEach(event -> {
            final String deletionEventId = event.get("id").getAsString();

            final File eventVersionDB = new File(directory, "/events/" + deletionEventId + "/version");
            if( !eventVersionDB.exists() ) eventVersionDB.mkdirs();
            final File eventVersionFile = new File(eventVersionDB, "data-" + System.currentTimeMillis() + "-deleted.json");
            if( ! eventVersionFile.exists() ) {
                try {
                     eventVersionFile.createNewFile();
                } catch(IOException failure) {
                    logger.warning("[Nostr] [Persistence] [Event] Could not delete event {}: {}", deletionEventId, failure.getMessage());
                    return;
                }
            }

            final File eventFile = new File(directory, "/events/" + deletionEventId + "/current/data.json");
            if(eventFile.exists()) eventFile.delete();

            logger.info("[Nostr] [Persistence] [Event] event {} deleted.", deletionEventId);
        });

        return 0;
    }

    public byte fetchEvents(final List<JsonObject> events) {
        return this.fetchCurrent(events, new File(directory, "events"));
    }

    public byte fetchProfile(final List<JsonObject> events) {
        return this.fetchCurrent(events, new File(directory, "profile"));
    }

    public byte fetchContactList(final List<JsonObject> events) {
        return this.fetchCurrent(events, new File(directory, "contact"));
    }

    public byte fetchParameters(final List<JsonObject> events) {
        return fetchCurrent(events, new File(directory, "/parameter"));
    }

    private byte fetchCurrent(final List<JsonObject> events, final File dataDB) {
        final Gson gson = new GsonBuilder().create();

        if(dataDB.exists()) dataDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( ! pathname.isDirectory()) return false;

                final File current = new File(pathname, "/current/data.json");
                if( !current.exists() ) return false;

                try(final InputStream in = new FileInputStream(current)) {
                    final JsonObject data = gson.fromJson(new InputStreamReader(in), JsonObject.class);
                    events.add(data);
                } catch(IOException failure) { /***/ }

                return false;
            }
        });

        return 0;
    }

    public byte close() {
        return 0;
    }
    
}
