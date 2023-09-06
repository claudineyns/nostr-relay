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

    public boolean isRegistered(final EventData eventData) {
        return false;
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
            return logger.warning("[Storage] Could not store event: {}", failure.getMessage());
        }

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) currentDB.mkdirs();

        final File data = new File(currentDB, eventData.getId());
        try(final OutputStream out = new FileOutputStream(data)) {
            out.write(raw);
        } catch(IOException failure) {
            return logger.warning("[Storage] Could not store event: {}", failure.getMessage());
        }

        logger.info("[Storage] event {} saved.", eventData.getId());

        return 0;
    }

    byte storeReplaceable(EventData eventData) {
        final String data = idOf(eventData.getPubkey(), eventData.getKind());

        final long now = System.currentTimeMillis();

        final byte[] raw = eventData.toString().getBytes(StandardCharsets.UTF_8);

        final File versionDB = new File(BASE_DIR, "/version");
        if(!versionDB.exists()) versionDB.mkdirs();

        final File versionData = new File(versionDB, "data-"+now+"-inserted");
        try(final OutputStream out = new FileOutputStream(versionData)) {
            out.write(raw);
        } catch(IOException failure) {
            return logger.warning("[Storage] Could not store replaceable event: {}", failure.getMessage());
        }

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) currentDB.mkdirs();

        final File content = new File(currentDB, data);
        try(final OutputStream out = new FileOutputStream(content)) {
            out.write(raw);
        } catch(IOException failure) {
            return logger.warning("[Storage] Could not store replaceable event: {}", failure.getMessage());
        }

        logger.info("[Storage] replaceable event {} saved.", eventData.getId());

        return 0;
    }

    byte storeParameterizedReplaceable(final EventData eventData, final Set<String> idList) {
        final long now = System.currentTimeMillis();

        final byte[] raw = eventData.toString().getBytes(StandardCharsets.UTF_8);

        final File versionDB = new File(BASE_DIR, "/version");
        if(!versionDB.exists()) versionDB.mkdirs();

        final File versionData = new File(versionDB, "data-"+now+"-inserted");
        try(final OutputStream out = new FileOutputStream(versionData)) {
            out.write(raw);
        } catch(IOException failure) {
            return logger.warning("[Storage] Could not store parameterized replaceable event: {}", failure.getMessage());
        }

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) currentDB.mkdirs();

        idList.forEach(paramId -> {
            final File data = new File(currentDB, paramId);
            try(final OutputStream out = new FileOutputStream(data)) {
                out.write(raw);
            } catch(IOException failure) { /***/ }
        });

        logger.info("[Storage] parameterized replaceable event {} saved.", eventData.getId());

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

    Collection<EventData> acquireListFromStorage() {
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

    Collection<EventData> acquireEventsFromStorageByIdSet(Set<String> set) {
        return set
            .stream()
            .map(id -> acquireEventFromStorageById(id))
            .filter(event -> event != null)
            .collect(Collectors.toList());
    }

    public byte close() {
        return 0;
    }

}
