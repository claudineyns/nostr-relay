package io.github.social.nostr.relay.datasource;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.github.social.nostr.relay.utilities.AppProperties;

public class DocumentDS {
    public static final DocumentDS INSTANCE = new DocumentDS();

    public static final String DB_NAME = "nostr";

    private boolean closed = false;

    final MongoClientSettings settings;
    final ConnectionString connection;
    private DocumentDS() {
        final String host = AppProperties.getMongoDbHost();
        final int port = AppProperties.getMongoDbPort();

        final String uri = "mongodb://"+host+":"+port+"/"+DB_NAME+"?maxPoolSize=10";
        this.connection = new ConnectionString(uri);

        settings = MongoClientSettings.builder()
                .applyConnectionString(this.connection)
                .build();
    }

    public MongoClient connect() {
        return MongoClients.create(this.settings);
        //return MongoClients.create(this.connection);
    }

    public synchronized byte close() {
        if(this.closed) return 0;
        this.closed = true;

        return 0;
    }

}
