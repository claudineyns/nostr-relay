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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.social.nostr.relay.datasource.DocumentService;
import io.github.social.nostr.relay.def.IEventService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventLocalStorageDataService implements IEventService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File BASE_DIR = new File("/var/nostr/data/");

    static final String DB_NAME = DocumentService.DB_NAME;

    private final GsonBuilder gsonBuilder = new GsonBuilder();

    private final ExecutorService cacheTask = Executors.newSingleThreadExecutor();

    public String checkRegistration(final EventData eventData) {
        return DB_ERROR;
    }

    public final String persistEvent(EventData eventData) {
        if (EventState.REGULAR.equals(eventData.getState())) {
            if ( this.hasEvent(eventData)) {
                return "duplicate: event has already been stored.";
            }
            if( this.checkRequestForRemoval(eventData) ) {
                return "invalid: this event has already been requested to be removed from this relay.";
            }
        }

        final Thread task = new Thread(() -> storeEvent(eventData));
        task.setDaemon(true);
        this.cacheTask.submit(task);

        return null;
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

    private boolean hasEvent(final EventData eventData) {
        return this.findEvent(eventData.getId()) != null;
    }

    private EventData findEvent(final String eventId) {
        return this.acquireEventFromStorage(eventId);
    }

    private boolean checkRequestForRemoval(final EventData eventData) {
        return this.fetchDeletionEvents()
            .stream()
            .filter(event -> EventKind.DELETION == event.getKind())
            .filter(event -> event.getReferencedEventList().contains(eventData.getId()))
            .count() > 0;
    }

    private Collection<EventData> fetchDeletionEvents() {
        return this.acquireListFromStorage()
            .stream()
            .filter(eventData -> EventKind.DELETION == eventData.getKind())
            .collect(Collectors.toList());
    }

    private byte storeEvent(EventData eventData) {
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

    private byte storeReplaceable(EventData eventData) {
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

    private byte storeParameterizedReplaceable(final EventData eventData) {
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

        eventData.getInfoNameList().forEach(param -> {
            final String info = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final File data = new File(currentDB, info);
            try(final OutputStream out = new FileOutputStream(data)) {
                out.write(raw);
            } catch(IOException failure) { /***/ }
        });

        logger.info("[Storage] parameterized replaceable event {} saved.", eventData.getId());

        return 0;
    }

    private byte removeLinkedEvents(EventData eventDeletion) {
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

    protected Collection<EventData> fetchEventsFromDatasource() {
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

        Collections.sort(events);

        return events;
    }

    private Collection<EventData> acquireListFromStorage() {
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

    private EventData acquireEventFromStorage(final String eventId) {
        final File currentDB = new File(BASE_DIR, "/current");
        if(!currentDB.exists()) return null;

        final File eventData = new File(currentDB, eventId);
        if(!eventData.exists()) return null;

        final Gson gson = gsonBuilder.create();

        try(final InputStream in = new FileInputStream(eventData)) {
            return EventData.gsonEngine(gson, in);
        } catch(IOException failure) {
            return null;
        }
    }

    public byte close() {
        return 0;
    }

}
