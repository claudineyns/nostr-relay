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
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventLocalStorageDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File BASE_DIR = new File("/var/nostr/data/");

    static final String DB_NAME = DocumentDS.DB_NAME;

    public String checkRegistration(final EventData eventData) {
        return DB_ERROR;
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
        final long now = System.currentTimeMillis();

        final String info = Utils.sha256(
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

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

        final File data = new File(currentDB, info);
        try(final OutputStream out = new FileOutputStream(data)) {
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

    byte removeLinkedEvents(EventData eventDeletion) {
        final Gson gson = gsonBuilder.create();

        final long now = System.currentTimeMillis();

        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) currentDB.mkdirs();

        final Collection<EventData> eventsMarkedForDeletion = new ArrayList<>();

        currentDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if(!pathname.isFile()) return false;

                try(final InputStream in = new FileInputStream(pathname)) {
                    final EventData eventData = EventData.gsonEngine(gson, in);

                    final String qAuthorId = eventData.getPubkey();
                    final int qEventKind   = eventData.getKind();
                    final EventState state = EventState.byKind(qEventKind);

                    if( EventState.REGULAR.equals(state) 
                            && qEventKind != EventKind.DELETION
                            && qAuthorId.equals(eventDeletion.getPubkey())
                    ) {
                        eventsMarkedForDeletion.add(eventData);
                    }

                } catch(IOException failure) { /***/ }

                return false;
            }
        });

        final File versionDB = new File(BASE_DIR, "/version");
        if(!versionDB.exists()) versionDB.mkdirs();

        final int[] counter = new int[] {0};
        eventsMarkedForDeletion.forEach(eventData -> {
            counter[0]++;
            final File versionData = new File(versionDB, "data-"+now+"-"+counter[0]+"-deleted");
            try(final OutputStream out = new FileOutputStream(versionData)) {
                out.write(eventData.toString().getBytes(StandardCharsets.UTF_8));
            } catch(IOException failure) {
                logger.warning("[Storage] Could not save deleted event: {}", failure.getMessage());
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
        return set.stream().map(id -> acquireEventFromStorageById(id)).collect(Collectors.toList());
    }

    public byte close() {
        return 0;
    }

}
