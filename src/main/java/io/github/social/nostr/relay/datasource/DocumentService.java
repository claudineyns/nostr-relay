package io.github.social.nostr.relay.datasource;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.github.social.nostr.relay.utilities.AppProperties;

public class DocumentService {
    public static final DocumentService INSTANCE = new DocumentService();

    public static final String DB_NAME = "nostr";

    private boolean closed = false;

    final MongoClientSettings settings;
    final ConnectionString connection;
    private DocumentService() {
        final String host = AppProperties.getMongoDbHost();
        final int port = AppProperties.getMongoDbPort();

        // Replace the placeholder with your Atlas connection string
        final String uri = "mongodb://"+host+":"+port+"/"+DB_NAME+"?maxPoolSize=10";
        this.connection = new ConnectionString(uri);

        // Construct a ServerApi instance using the ServerApi.builder() method
        final ServerApi serverApi = ServerApi.builder().version(ServerApiVersion.V1).build();

        settings = MongoClientSettings.builder()
                .applyConnectionString(this.connection)
                .serverApi(serverApi)
                .build();
    }

    public MongoClient connect() {
        //return MongoClients.create(this.settings);
        return MongoClients.create(this.connection);
    }

    public synchronized byte close() {
        if(this.closed) return 0;
        this.closed = true;

        return 0;
    }

}
