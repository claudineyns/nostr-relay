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
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventDiskDataService extends AbstractCachedEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final File directory = new File("/var/nostr/data/");

    public synchronized String checkRegistration (final EventData eventData) {
        if( new File(directory, "/registration/" + eventData.getPubkey()).exists() ) return null;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(new File(directory, "/registration/" + refPubkey).exists()) return null;
        }

        return REG_REQUIRED;
    }

    @Override
    protected byte proceedToRemoveLinkedEvents(EventData eventDeletion) {
        final File eventDB = new File(directory, "/events");

        final Gson gson = new GsonBuilder().create();

        final List<EventData> eventsMarkedForDeletion = new ArrayList<>();
        eventDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( !pathname.isDirectory() ) return false;

                final File eventFile = new File(pathname, "/current/data.json");
                if (!eventFile.exists()) return false;

                try (final InputStream in = new FileInputStream(eventFile) ) {
                    final EventData eventData = EventData.gsonEngine(gson, in);

                    final String qAuthorId = eventData.getPubkey();
                    final String qEventId  = eventData.getId();
                    final int qEventKind   = eventData.getKind();

                    if( EventState.REGULAR.equals(eventData.getState()) 
                            && qEventKind != EventKind.DELETION
                            && qAuthorId.equals(eventData.getId())
                            && eventDeletion.getReferencedEventList().contains(qEventId)
                    ) {
                        eventsMarkedForDeletion.add(eventData);
                    }
                } catch(IOException failure) {
                    logger.warning("[Nostr] [Persistence] [Event] Could not load event: {}", failure.getMessage());
                }

                return false;
            }
        });

        eventsMarkedForDeletion.stream().forEach(event -> {
            final String deletionEventId = event.getId();

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

    protected Collection<EventData> proceedToFetchEventList() {
        final List<EventData> cacheEvents = new ArrayList<>();

        this.fetchEvents(cacheEvents);
        this.fetchReplaceables(cacheEvents);
        this.fetchParameters(cacheEvents);

        return cacheEvents;
    }

    public byte fetchEvents(final Collection<EventData> events) {
        return this.fetchCurrent(events, new File(directory, "/event"));
    }

    public byte fetchReplaceables(final Collection<EventData> events) {
        return this.fetchCurrent(events, new File(directory, "/replaceable"));
    }

    public byte fetchParameters(final Collection<EventData> events) {
        return fetchCurrent(events, new File(directory, "/parameter"));
    }

    protected byte proceedToSaveEvent(EventData eventData) {
        final File eventDB = new File(directory, "/event/"+eventData.getId());

        final byte[] eventRaw = eventData.toString().getBytes(StandardCharsets.UTF_8);

        final File eventVersionDB = new File(eventDB, "/version");
        if ( ! eventVersionDB.exists() ) eventVersionDB.mkdirs();
        final File eventVersion = new File(eventVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream eventRecord = new FileOutputStream(eventVersion)) {
            eventRecord.write(eventRaw);
            logger.info("[Nostr] [Persistence] [Event] Version saved");
        } catch(IOException failure) {
            return logger.warning("[Nostr] [Persistence] [Event] Could not save version: {}", failure.getMessage());
        }

        final File eventCurrentDB = new File(eventDB, "/current");
        if( ! eventCurrentDB.exists() ) eventCurrentDB.mkdirs();
        final File eventFile = new File(eventCurrentDB, "data.json");
        try (final OutputStream eventRecord = new FileOutputStream(eventFile)) {
            eventRecord.write(eventRaw);
            logger.info("[Nostr] [Persistence] [Event] data updated");
        } catch(IOException failure) {
            logger.warning("[Nostr] [Persistence] [Event] Could not update data: {}", failure.getMessage());
        }

        return 0;
    }

    protected byte proceedToSaveReplaceable(EventData eventData) {
        final String data = Utils.sha256 (
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

        final File dataDB = new File(directory, "/replaceable/"+data);
        if( ! dataDB.exists() ) dataDB.mkdirs();

        final File dataVersionDB = new File(dataDB, "/version");
        if ( ! dataVersionDB.exists() ) dataVersionDB.mkdirs();

        final byte[] eventRaw = eventData.toString().getBytes(StandardCharsets.UTF_8);

        final File paramVersion = new File(dataVersionDB, "data-" + System.currentTimeMillis() + ".json");
        try (final OutputStream paramRecord = new FileOutputStream(paramVersion)) {
            paramRecord.write(eventRaw);
            logger.info("[Nostr] [Persistence] [Replaceable] Version saved");
        } catch(IOException failure) {
            return logger.warning("[Nostr] [Persistence] [Replaceable] Could not save version: {}", failure.getMessage());
        }

        final File dataCurrentDB = new File(dataDB, "/current");
        if ( ! dataCurrentDB.exists() ) dataCurrentDB.mkdirs();

        final File contentData = new File(dataCurrentDB, "data.json");
        try (final OutputStream paramRecord = new FileOutputStream(contentData)) {
            paramRecord.write(eventRaw);
            logger.info("[Nostr] [Persistence] [Replaceable] data updated");
        } catch(IOException failure) {
            return logger.warning("[Nostr] [Persistence] [Replaceable] Could not update data: {}", failure.getMessage());
        }

        return 0;
    }

    protected byte proceedToSaveParameterizedReplaceable(EventData eventData) {
        for(final String param: eventData.getInfoNameList()) {
            final String data = Utils.sha256 (
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final File dataDB = new File(directory, "/parameter/"+data);
            if( ! dataDB.exists() ) dataDB.mkdirs();

            final File dataVersionDB = new File(dataDB, "/version");
            if ( ! dataVersionDB.exists() ) dataVersionDB.mkdirs();

            final byte[] eventRaw = eventData.toString().getBytes(StandardCharsets.UTF_8);

            final File paramVersion = new File(dataVersionDB, "data-" + System.currentTimeMillis() + ".json");
            try (final OutputStream paramRecord = new FileOutputStream(paramVersion)) {
                paramRecord.write(eventRaw);
                logger.info("[Nostr] [Persistence] [Parameter] Version saved");
            } catch(IOException failure) {
                return logger.warning("[Nostr] [Persistence] [Parameter] Could not save version: {}", failure.getMessage());
            }

            final File dataCurrentDB = new File(dataDB, "/current");
            if ( ! dataCurrentDB.exists() ) dataCurrentDB.mkdirs();

            final File contentData = new File(dataCurrentDB, "data.json");
            try (final OutputStream paramRecord = new FileOutputStream(contentData)) {
                paramRecord.write(eventRaw);
                logger.info("[Nostr] [Persistence] [Parameter] data updated");
            } catch(IOException failure) {
                return logger.warning("[Nostr] [Persistence] [Parameter] Could not update data: {}", failure.getMessage());
            }

        }

        return 0;
    }

    private byte fetchCurrent(final Collection<EventData> events, final File dataDB) {
        final Gson gson = new GsonBuilder().create();

        if(dataDB.exists()) dataDB.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                if( ! pathname.isDirectory()) return false;

                final File current = new File(pathname, "/current/data.json");
                if( !current.exists() ) return false;

                try(final InputStream in = new FileInputStream(current)) {
                    events.add(EventData.gsonEngine(gson, in));
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
