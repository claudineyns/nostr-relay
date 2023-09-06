package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventDocumentDataService extends AbstractEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final DocumentDS datasource = DocumentDS.INSTANCE;

    static final String DB_NAME = DocumentDS.DB_NAME;

    public String checkRegistration(final EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            return validateRegistration(client.getDatabase(DB_NAME), eventData);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    private String validateRegistration(final MongoDatabase db, final EventData eventData) {
        final Set<String> registration = new LinkedHashSet<>();

        final MongoCollection<Document> registrationDB = db.getCollection("registration");
        try(final MongoCursor<Document> cursor = registrationDB.find().cursor()) {
            cursor.forEachRemaining(document -> registration.add(document.get("pubkey").toString()));
        }

        if(registration.contains(eventData.getPubkey())) return null;

        for(final String refPubkey: eventData.getReferencedPubkeyList()) {
            if(registration.contains(refPubkey)) return null;
        }

        return REG_REQUIRED;
    }

    byte storeEvent(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            storeEvent(client.getDatabase(DB_NAME), eventData);
        } catch(Throwable e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
        return 0;
    }

    byte storeReplaceable(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            storeReplaceable(client.getDatabase(DB_NAME), eventData);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }

        return 0;
    }

    byte storeParameterizedReplaceable(final EventData eventData, final Set<String> idList) {
        try (final MongoClient client = datasource.connect()) {
            return storeParameterizedReplaceable(client.getDatabase(DB_NAME), eventData, idList);
        } catch(Exception e) {
            return logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
    }

    byte removeLinkedEvents(EventData eventDeletion) {
        try (final MongoClient client = datasource.connect()) {
            return removeEventsFromStorage(client.getDatabase(DB_NAME), eventDeletion);
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

    private String storeEvent(final MongoDatabase db, EventData eventData) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventDoc = Document.parse(eventData.toString());
        eventDoc.put("_id", eventData.getId());

        final MongoCollection<Document> current = db.getCollection("current");
        final UpdateResult result = current.replaceOne(Filters.eq("_id", eventData.getId()), eventDoc);
        if(result.getModifiedCount() == 0) {
            current.insertOne(eventDoc);
        }

        final Document eventVersion = new Document(eventDoc);
        eventVersion.put("_id", UUID.randomUUID().toString());
        eventVersion.put("_kid", eventData.getId());
        eventVersion.put("_updated_at", now);
        eventVersion.put("_status", "inserted");

        final MongoCollection<Document> cacheVersion = db.getCollection("version");
        cacheVersion.insertOne(eventVersion);

        logger.info("[MongoDB] [Event] event {} saved.", eventData.getId());

        return null;
    }

    private String storeReplaceable(final MongoDatabase db, EventData eventData) {
        final String data = Utils.sha256(
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventDoc = Document.parse(eventData.toString());
        eventDoc.put("_id", data);

        final MongoCollection<Document> cacheCurrent = db.getCollection("current");
        final UpdateResult result = cacheCurrent.replaceOne(Filters.eq("_id", data), eventDoc);
        if(result.getModifiedCount() == 0) {
            cacheCurrent.insertOne(eventDoc);
        }

        final Document eventVersion = new Document(eventDoc);
        eventVersion.put("_id", UUID.randomUUID().toString());
        eventVersion.put("_kid", data);
        eventVersion.put("_updated_at", now);
        eventVersion.put("_status", "inserted");

        final MongoCollection<Document> cacheVersion = db.getCollection("version");
        cacheVersion.insertOne(eventVersion);

        logger.info("[MongoDB] [Replaceable] event {} consumed.", eventData.getId());

        return null;
    }

    private byte storeParameterizedReplaceable(
            final MongoDatabase db,
            final EventData eventData,
            final Set<String> idList
    ) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventBase = Document.parse(eventData.toString());

        idList.forEach(paramId -> {
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
    
    private byte removeEventsFromStorage(final MongoDatabase db, EventData eventDeletion) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final List<Document> eventsMarkedForDeletion = new ArrayList<>();

        final MongoCollection<Document> cacheCurrent = db.getCollection("current");

        final Bson removalListFilter = Filters.in("id", eventDeletion.getReferencedEventList());
        try(final MongoCursor<Document> cursor = cacheCurrent.find(removalListFilter).cursor()) {
            cursor.forEachRemaining(eventDoc -> {
                final String qAuthorId = eventDoc.get("pubkey").toString();
                final int qEventKind   = Integer.parseInt(eventDoc.get("kind").toString());
                final EventState state = EventState.byKind(qEventKind);

                if( EventState.REGULAR.equals(state) 
                        && qEventKind != EventKind.DELETION
                        && qAuthorId.equals(eventDeletion.getPubkey())
                ) {
                    eventsMarkedForDeletion.add(eventDoc);
                }
            });
        }

        final MongoCollection<Document> cacheVersion = db.getCollection("version");

        eventsMarkedForDeletion.forEach(eventDoc -> {
            final Bson removedItemFilter = Filters.eq("id", eventDoc.get("id"));
            final DeleteResult result = cacheCurrent.deleteOne(removedItemFilter);

            final Document eventVersion = new Document(eventDoc);
            eventVersion.put("_id", UUID.randomUUID().toString());
            eventVersion.put("_status", "deleted");
            eventVersion.put("_updated_at", now);

            if(result.getDeletedCount() > 0) {
                cacheVersion.insertOne(eventVersion);
            }
        });

        return logger.info("[Event] events related by event {} has been deleted.", eventDeletion.getId());
    }

    EventData acquireEventFromStorageById(final String id) {
        try (final MongoClient client = datasource.connect()) {
            return acquireEventFromStorageById(client.getDatabase(DB_NAME), id);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return null;
        }
    }

    Collection<EventData> acquireEventsFromStorageByIdSet(Set<String> set) {
        try (final MongoClient client = datasource.connect()) {
            return set
                .stream()
                .map(id -> acquireEventFromStorageById(client.getDatabase(DB_NAME), id))
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

        final Document eventDoc = current.find(Filters.eq("id", eventId)).first();

        if(eventDoc != null) {
            eventDoc.remove("_id");
            return EventData.gsonEngine(gson, gson.toJson(eventDoc));
        }

        return null;
    }

    public byte close() {
        return datasource.close();
    }

}
