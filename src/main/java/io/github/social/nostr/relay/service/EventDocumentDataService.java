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
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;

import io.github.social.nostr.relay.datasource.DocumentService;
import io.github.social.nostr.relay.specs.EventData;
import io.github.social.nostr.relay.specs.EventKind;
import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.utilities.Utils;

public class EventDocumentDataService extends AbstractCachedEventDataService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    private final DocumentService datasource = DocumentService.INSTANCE;

    private final String DB_NAME = "nostr";

    public String checkRegistration(final EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            return validateRegistration(client.getDatabase(DB_NAME), eventData);
        } catch(MongoException e) {
            logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
            return DB_ERROR;
        }
    }

    public byte fetchEvents(final Collection<EventData> events) {
        final String cache = "event";

        try (final MongoClient client = datasource.connect()) {
            return this.fetchList(client.getDatabase(DB_NAME), events, cache);
        } catch(MongoException e) {
            return logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }
    }

    public byte fetchReplaceables(final Collection<EventData> events) {
        try (final MongoClient client = datasource.connect()) {
            return this.fetchList(client.getDatabase(DB_NAME), events, "replaceable");
        } catch(MongoException e) {
            return logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }
    }

    public byte fetchParameters(final Collection<EventData> events) {
        try (final MongoClient client = datasource.connect()) {
            return this.fetchList(client.getDatabase(DB_NAME), events, "parameter");
        } catch(MongoException e) {
            return logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
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
            saveEvent(client.getDatabase(DB_NAME), eventData);
        } catch(MongoException e) {
            logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }
        return 0;
    }

    protected byte proceedToSaveReplaceable(EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            saveReplaceable(client.getDatabase(DB_NAME), eventData);
        } catch(MongoException e) {
            logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }

        return 0;
    }

    protected byte proceedToSaveParameterizedReplaceable(final EventData eventData) {
        try (final MongoClient client = datasource.connect()) {
            return saveParameterizedReplaceable(client.getDatabase(DB_NAME), eventData);
        } catch(MongoException e) {
            return logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }
    }

    protected byte proceedToRemoveLinkedEvents(EventData eventDeletion) {
        try (final MongoClient client = datasource.connect()) {
            return removeEvents(client.getDatabase(DB_NAME), eventDeletion);
        } catch(MongoException e) {
            return logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());
        }
    }

    protected Collection<EventData> proceedToFetchEventList() {
        final Collection<EventData> list = new LinkedHashSet<>();

        try (final MongoClient client = datasource.connect()) {
            final MongoDatabase db = client.getDatabase(DB_NAME);

            fetchList(db, list, "event");
            fetchList(db, list, "replaceable");
            fetchList(db, list, "parameter");
        } catch(MongoException e) {
            logger.warning("[Nostr] [Persistence] [MongoDB] Failure: {}", e.getMessage());

            return Collections.emptyList();
        }

        final Set<String> unique = new HashSet<>();
        final List<EventData> events = new ArrayList<>();
        list
            .stream()
            .forEach(eventData -> {
                if(!unique.contains(eventData.getId())) {
                    unique.add(eventData.getId());
                    events.add(eventData);
                }
            });

        Collections.sort(events);

        return events;
    }

    private String saveEvent(final MongoDatabase db, EventData eventData) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventDoc = Document.parse(eventData.toString());
        eventDoc.put("_id", UUID.randomUUID());

        final UpdateOptions options = new UpdateOptions().upsert(true);        
        final MongoCollection<Document> cacheCurrent = db.getCollection("eventCurrent");
        cacheCurrent.updateOne(new Document("id", eventData.getId()), eventDoc, options);

        final Document eventVersion = new Document(eventDoc);
        eventVersion.put("updated_at", now);

        final MongoCollection<Document> cacheVersion = db.getCollection("eventVersion");
        cacheVersion.insertOne(eventVersion);

        logger.info("[Nostr] [Persistence] [Event]  {} updated.", eventData.getId());
        return null;
    }

    private String saveReplaceable(final MongoDatabase db, EventData eventData) {
        final String data = Utils.sha256(
            (eventData.getPubkey()+"#"+eventData.getKind()).getBytes(StandardCharsets.UTF_8)
        );

        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventDoc = Document.parse(eventData.toString());
        eventDoc.put("_id", UUID.randomUUID());
        eventDoc.put("_pk", data);

        final UpdateOptions options = new UpdateOptions().upsert(true);        
        final MongoCollection<Document> cacheCurrent = db.getCollection("replaceableCurrent");
        cacheCurrent.updateOne(new Document("_pk", data), eventDoc, options);

        final Document eventVersion = new Document(eventDoc);
        eventVersion.put("updated_at", now);

        final MongoCollection<Document> cacheVersion = db.getCollection("replaceableVersion");
        cacheVersion.insertOne(eventVersion);

        logger.info("[Nostr] [Persistence] [Replaceable]  {} consumed.", eventData.getId());

        return null;
    }

    private byte saveParameterizedReplaceable(final MongoDatabase db, EventData eventData) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final Document eventBase = Document.parse(eventData.toString());

        for (final String param : eventData.getInfoNameList()) {
            final String data = Utils.sha256(
                (eventData.getPubkey()+"#"+eventData.getKind()+"#"+param).getBytes(StandardCharsets.UTF_8)
            );

            final UUID _id = UUID.randomUUID();

            final Document eventDoc = new Document(eventBase);
            eventDoc.put("_id", _id);
            eventDoc.put("_pkd", data);

            final UpdateOptions options = new UpdateOptions().upsert(true);        
            final MongoCollection<Document> cacheCurrent = db.getCollection("parameterCurrent");
            cacheCurrent.updateOne(new Document("_pkd", data), eventDoc, options);

            final Document eventVersion = new Document(eventDoc);
            eventVersion.put("updated_at", now);

            final MongoCollection<Document> cacheVersion = db.getCollection("parameterVersion");
            cacheVersion.insertOne(eventVersion);
        }

        logger.info("[Nostr] [Persistence] [Parameter]  {} consumed.", eventData.getId());

        return 0;
    }
    
    private byte removeEvents(final MongoDatabase db, EventData eventDeletion) {
        final int now = (int) (System.currentTimeMillis()/1000L);

        final List<Document> eventsMarkedForDeletion = new ArrayList<>();

        final MongoCollection<Document> cacheCurrent = db.getCollection("eventCurrent");

        final Bson filter = Filters.in("id", eventDeletion.getReferencedEventList());
        try(final MongoCursor<Document> cursor = cacheCurrent.find(filter).cursor()) {
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

        final MongoCollection<Document> cacheVersion = db.getCollection("eventVersion");

        eventsMarkedForDeletion.forEach(eventDoc -> {
            cacheCurrent.deleteOne(new Document("id", eventDoc.get("id")));

            final Document eventVersion = new Document(eventDoc);
            eventVersion.put("_id", UUID.randomUUID());
            eventVersion.put("updated_at", now);

            cacheVersion.insertOne(eventVersion);
        });

        return logger.info("[Nostr] [Persistence] [Event] events related by  {} has been deleted.", eventDeletion.getId());
    }

    private byte fetchList(final MongoDatabase db, final Collection<EventData> events, final String cache) {
        final Gson gson = new GsonBuilder().create();

        final MongoCollection<Document> cacheCurrent = db.getCollection(cache+"Current");

        final Collection<EventData> eventList = new ArrayList<>();

        try(final MongoCursor<Document> cursor = cacheCurrent.find().cursor()) {
            cursor.forEachRemaining(doc -> {
                doc.remove("_id");
                doc.remove("_pk");
                doc.remove("_pkd");
                doc.remove("updated_at");

                final EventData eventData = EventData.gsonEngine(gson, gson.toJson(doc));
                eventList.add(eventData);
            });
        }

        events.addAll(eventList);

        return 0;
    }

    public byte close() {
        return datasource.close();
    }

}
