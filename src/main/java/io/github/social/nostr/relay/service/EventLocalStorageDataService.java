package io.github.social.nostr.relay.service;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import io.github.social.nostr.relay.datasource.DocumentDS;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.utilities.LogService;

public class EventLocalStorageDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File BASE_DIR = new File("/var/nostr/data/");

    static final String DB_NAME = DocumentDS.DB_NAME;

    boolean validateRegistration(final EventData eventData) {
        return false;
    }

    Set<String> acquireRegistrationFromStorage() {
        return Collections.emptySet();
    }

    byte storeEvent(final EventData eventData) {
        final long now = System.currentTimeMillis();

        final byte[] raw = eventData.toString().getBytes(StandardCharsets.UTF_8);

        final File versionDB = new File(BASE_DIR, "/version");
        if(!versionDB.exists()) versionDB.mkdirs();

        final File versionData = new File(versionDB, "data-"+now+"-inserted");
        try(final OutputStream out = new FileOutputStream(versionData)) {
            out.write(raw);
        } catch(IOException failure) {
            return logger.warning(
                "[Storage] Could not store event {}: {}",
                eventData.getId(), failure.getMessage());
        }

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) currentDB.mkdirs();

        eventData.storableIds().forEach(storageId -> {
            final File data = new File(currentDB, storageId);
            try(final OutputStream out = new FileOutputStream(data)) {
                out.write(raw);
            } catch(IOException failure) { /***/ }
        });

        logger.info("[Storage] Event {} stored.", eventData.getId());

        return 0;
    }

    byte removeStoredEvents(final Collection<EventData> events) {
        final long now = System.currentTimeMillis();

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) return 0;

        final File versionDB = new File(BASE_DIR, "/version");
        if(!versionDB.exists()) versionDB.mkdirs();

        final int[] counter = new int[] {0};
        events.forEach(eventData -> {
            if( !new File(currentDB, eventData.getId()).delete() ) return;

            counter[0]++;
            final File versionData = new File(versionDB, "data-"+now+"-"+counter[0]+"-deleted");
            try(final OutputStream out = new FileOutputStream(versionData)) {
                out.write(eventData.toString().getBytes(StandardCharsets.UTF_8));
            } catch(IOException failure) {
                logger.warning(
                    "[Storage] Could not save version for deleted event {}: {}",
                    eventData.getId(), failure.getMessage());
            }
        });

        return 0;
    }

    Collection<EventData> acquireEventsFromStorage() {
        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) return Collections.emptyList();

        final Gson gson = gsonBuilder.create();

        final Collection<EventData> events = new ArrayList<>();

        currentDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if(!pathname.isFile()) return false;

                try(final InputStream in = new FileInputStream(pathname)) {
                    final EventData eventData = EventData.gsonEngine(gson, in);
                    events.add(eventData);
                } catch(IOException failure) { /***/ }

                return false;
            }
        });

        return events;
    }

    EventData acquireEventFromStorageById(final String id) {
        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) return null;

        final File eventData = new File(currentDB, id);
        if(!eventData.exists()) return null;

        final Gson gson = gsonBuilder.create();

        try(final InputStream in = new FileInputStream(eventData)) {
            return EventData.gsonEngine(gson, in);
        } catch(IOException failure) {
            return null;
        }
    }

    Collection<EventData> acquireEventsFromStorageByIds(Set<String> set) {
        return set
            .stream()
            .map(id -> acquireEventFromStorageById(id))
            .filter(event -> event != null)
            .collect(Collectors.toList());
    }

    public byte close() {
        this.beforeClosing();
        return 0;
    }

}
