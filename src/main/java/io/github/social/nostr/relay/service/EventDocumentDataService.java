package io.github.social.nostr.relay.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import io.github.social.nostr.relay.datasource.DocumentDS;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.utilities.LogService;

public class EventDocumentDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final DocumentDS datasource = DocumentDS.INSTANCE;

    static final String DB_NAME = DocumentDS.DB_NAME;

    boolean validateRegistration(final EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            return validateRegistration(client.getDatabase(DB_NAME), eventData);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return false;
        }
    }

    Set<String> acquireRegistrationFromStorage() {
        try (final MongoClient client = datasource.connect()) {
            return acquireRegistrationFromStorage(client.getDatabase(DB_NAME));
        } catch(Exception e) {
            logger.warning("[MongoDB] Could not fetch registrations: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    boolean validateRegistration(final MongoDatabase db, final EventData eventData) {
        final Set<String> registration = new LinkedHashSet<>();

        final MongoCollection<Document> registrationDB = db.getCollection("registration");
        try(final MongoCursor<Document> cursor = registrationDB.find().cursor()) {
            cursor.forEachRemaining(document -> registration.add(document.get("pubkey").toString()));
        }

        if(registration.contains(eventData.getPubkey())) return true;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(registration.contains(refPubkey)) return true;
        }

        return false;
    }

    Set<String> acquireRegistrationFromStorage(final MongoDatabase db) {
        final Set<String> registration = new LinkedHashSet<>();

        final MongoCollection<Document> registrationDB = db.getCollection("registration");
        try(final MongoCursor<Document> cursor = registrationDB.find().cursor()) {
            cursor.forEachRemaining(document -> registration.add(document.get("pubkey").toString()));
        }

        return registration;
    }

    byte storeEvent(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            storeEvent(client.getDatabase(DB_NAME), eventData);
        } catch(Throwable e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
        return 0;
    }

    byte removeStoredEvents(final Collection<EventData> events) {
        try (final MongoClient client = datasource.connect()) {
            return removeStoredEvents(client.getDatabase(DB_NAME), events);
        } catch(Exception e) {
            return logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
    }

    Collection<EventData> acquireListFromStorage() {
        try (final MongoClient client = datasource.connect()) {
            return acquireListFromStorage(client.getDatabase(DB_NAME));
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private byte storeEvent(final MongoDatabase db, final EventData eventData) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventBase = Document.parse(eventData.toString());

        eventData.storableIds().forEach(paramId -> {
            final Document eventDoc = new Document(eventBase);
            eventDoc.put("_id", paramId);

            final MongoCollection<Document> cacheCurrent = db.getCollection("current");
            final UpdateResult result = cacheCurrent.replaceOne(Filters.eq("_id", paramId), eventDoc);
            if(result.getModifiedCount() == 0) {
                cacheCurrent.insertOne(eventDoc);
            }

            final Document eventVersion = new Document(eventDoc);
            eventVersion.put("_id", UUID.randomUUID().toString());
            eventVersion.put("_kid", paramId);
            eventVersion.put("_updated_at", now);
            eventVersion.put("_status", "inserted");

            final MongoCollection<Document> cacheVersion = db.getCollection("version");
            cacheVersion.insertOne(eventVersion);
        });

        return logger.info("[MongoDB] [Parameter] event {} consumed.", eventData.getId());
    }
    
    private byte removeStoredEvents(final MongoDatabase db, final Collection<EventData> events) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final MongoCollection<Document> cacheCurrent = db.getCollection("current");

        final MongoCollection<Document> cacheVersion = db.getCollection("version");

        events
            .stream()
            .map(event -> cacheCurrent.find(Filters.eq("id", event.getId())).first() )
            .filter(eventDoc -> eventDoc != null)
            .forEach(eventDoc -> {
                final String eventId = eventDoc.get("id").toString();
                final Bson removedItemFilter = Filters.eq("id", eventId);
                final DeleteResult result = cacheCurrent.deleteOne(removedItemFilter);

                final Document eventVersion = new Document(eventDoc);
                eventVersion.put("_id", UUID.randomUUID().toString());
                eventVersion.put("_status", "deleted");
                eventVersion.put("_updated_at", now);

                if(result.getDeletedCount() > 0) {
                    cacheVersion.insertOne(eventVersion);
                    logger.info("[MongoDB] event {} has been removed", eventId);
                }
            });

        return 0;
    }

    EventData acquireEventFromStorageById(final String id) {
        try (final MongoClient client = datasource.connect()) {
            return acquireEventFromStorageById(client.getDatabase(DB_NAME), id);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return null;
        }
    }

    Collection<EventData> acquireEventsFromStorageByIds(Set<String> set) {
        try (final MongoClient client = datasource.connect()) {
            return set
                .stream()
                .map(id -> acquireEventFromStorageById(client.getDatabase(DB_NAME), id))
                .filter(event -> event != null)
                .collect(Collectors.toList());
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return null;
        }
    }

    private Collection<EventData> acquireListFromStorage(final MongoDatabase db) {
        final Gson gson = gsonBuilder.create();

        final MongoCollection<Document> current = db.getCollection("current");

        final Collection<EventData> eventList = new ArrayList<>();

        try(final MongoCursor<Document> cursor = current.find().cursor()) {
            cursor.forEachRemaining(doc -> {
                doc.remove("_id");

                final EventData eventData = EventData.gsonEngine(gson, gson.toJson(doc));
                eventList.add(eventData);
            });
        }

        return eventList;
    }

    private EventData acquireEventFromStorageById(final MongoDatabase db, final String eventId) {
        final Gson gson = gsonBuilder.create();

        final MongoCollection<Document> current = db.getCollection("current");

        final Document eventDoc = current.find(Filters.eq("_id", eventId)).first();

        if(eventDoc != null) {
            eventDoc.remove("_id");
            return EventData.gsonEngine(gson, gson.toJson(eventDoc));
        }

        return null;
    }

    public byte close() {
        this.beforeClosing();
        return datasource.close();
    }

}
