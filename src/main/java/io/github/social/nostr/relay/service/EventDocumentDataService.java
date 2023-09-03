package io.github.social.nostr.relay.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bson.Document;
import org.bson.conversions.Bson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import io.github.social.nostr.relay.datasource.DocumentService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventDocumentDataService extends AbstractCachedEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final DocumentService datasource = DocumentService.INSTANCE;

    static final String DB_NAME = DocumentService.DB_NAME;

    private final GsonBuilder gsonBuilder = new GsonBuilder();

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

    protected byte proceedToSaveEvent(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            storeEvent(client.getDatabase(DB_NAME), eventData);
        } catch(Throwable e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
        return 0;
    }

    protected byte proceedToSaveReplaceable(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            storeReplaceable(client.getDatabase(DB_NAME), eventData);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }

        return 0;
    }

    protected byte proceedToSaveParameterizedReplaceable(final EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            return storeParameterizedReplaceable(client.getDatabase(DB_NAME), eventData);
        } catch(Exception e) {
            return logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
    }

    protected byte proceedToRemoveLinkedEvents(EventData eventDeletion) {
        try (final MongoClient client = datasource.connect()) {
            return removeEvents(client.getDatabase(DB_NAME), eventDeletion);
        } catch(Exception e) {
            return logger.warning("[MongoDB] Failure: {}", e.getMessage());
        }
    }

    protected Collection<EventData> proceedToFetchEventList() {
        final Collection<EventData> list = new LinkedHashSet<>();

        try (final MongoClient client = datasource.connect()) {
            fetchList(client.getDatabase(DB_NAME), list);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());

            return Collections.emptyList();
        }

        final Set<String> unique = new HashSet<>();
        final List<EventData> events = new ArrayList<>();
        list
            .stream()
            .forEach(eventData -> {
                if( ! unique.contains(eventData.getId()) ) {
                    unique.add(eventData.getId());
                    events.add(eventData);
                }
            });

        Collections.sort(events);

        return events;
    }

    protected EventData proceedToFindEvent(String eventId) {
        try (final MongoClient client = datasource.connect()) {
            return searchEvent(client.getDatabase(DB_NAME), eventId);
        } catch(Exception e) {
            logger.warning("[MongoDB] Failure: {}", e.getMessage());
            return null;
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

    private byte storeParameterizedReplaceable(final MongoDatabase db, EventData eventData) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventBase = Document.parse(eventData.toString());

        for (final String param : eventData.getInfoNameList()) {
            final String data = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final Document eventDoc = new Document(eventBase);
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
        }

        logger.info("[MongoDB] [Parameter] event {} consumed.", eventData.getId());

        return 0;
    }
    
    private byte removeEvents(final MongoDatabase db, EventData eventDeletion) {
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

    private byte fetchList(final MongoDatabase db, final Collection<EventData> events) {
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

        events.addAll(eventList);

        return 0;
    }

    private EventData searchEvent(final MongoDatabase db, final String eventId) {
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
