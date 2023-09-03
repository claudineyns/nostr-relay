package io.github.social.nostr.relay.datasource;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;

public class DocumentService {
    public static final DocumentService INSTANCE = new DocumentService();

    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    public static final String DB_NAME = "nostr";

    private boolean closed = false;

    final MongoClientSettings settings;
    private DocumentService() {
        final String host = AppProperties.getMongoDbHost();
        final int port = AppProperties.getMongoDbPort();

        // Replace the placeholder with your Atlas connection string
        final String uri = "mongodb://"+host+":"+port+"/"+DB_NAME+"?maxPoolSize=20&w=majority";
        logger.info("[MongoDB] Connecting to {}", uri);

        // Construct a ServerApi instance using the ServerApi.builder() method
        final ServerApi serverApi = ServerApi.builder().version(ServerApiVersion.V1).build();

        settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .serverApi(serverApi)
                .build();
    }

    public MongoClient connect() {
        return MongoClients.create(this.settings);
    }

    public synchronized byte close() {
        if(this.closed) return 0;
        this.closed = true;

        return 0;
    }

}
