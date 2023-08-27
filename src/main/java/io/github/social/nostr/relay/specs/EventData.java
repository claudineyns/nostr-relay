package io.github.social.nostr.relay.specs;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class EventData {
    private final String id;
    private final String pubkey;
    private final String content;
    private final int kind;
    private final int created_at;
    private final String sig;
    private int expiration;

    private final List<List<String>> tags = new ArrayList<>();

    private final EventState state;

    private final String payload;

    private EventData(final JsonObject json) {
        this.id = json.get("id").getAsString();
        this.kind = json.get("kind").getAsInt();
        this.pubkey = json.get("pubkey").getAsString();
        this.content = json.get("content").getAsString();
        this.created_at = json.get("created_at").getAsInt();
        this.sig = json.get("sig").getAsString();

        this.state = EventState.byKind(kind);

        Optional.ofNullable(json.get("tags")).ifPresent(this::fetchTags);

        this.payload = json.toString();
    }

    public static EventData gsonEngine(final Gson gson, final InputStream in) {
        final JsonObject data = gson.fromJson(new InputStreamReader(in), JsonObject.class);

        return new EventData(data);
    }

    public static EventData gsonEngine(final Gson gson, final String json) {
        final JsonObject data = gson.fromJson(json, JsonObject.class);

        return new EventData(data);
    }

    public static EventData of(final JsonObject json) {
        return new EventData(json);
    }

    public String getId() {
        return id;
    }

    public int getKind() {
        return kind;
    }

    public EventState getState() {
        return state;
    }

    public String getPubkey() {
        return pubkey;
    }

    public String getContent() {
        return content;
    }

    public int getCreatedAt() {
        return created_at;
    }

    public List<List<String>> getTags() {
        return Collections.unmodifiableList(tags);
    }

    public String getSig() {
        return sig;
    }

    public int getExpiration() {
        return expiration;
    }

    public String toString() {
        return payload;
    }

    private void fetchTags(JsonElement tagEL) {
        this.tags.clear();

        tagEL.getAsJsonArray().forEach(tagEntryEL -> {
            final List<String> tagList = new ArrayList<>();
            tagEntryEL.getAsJsonArray().forEach(tagValue -> tagList.add(tagValue.getAsString()));
            tags.add(tagList);
        });

        this.readTags();
    }

    private void readTags() {
        this.tags.forEach(tagList -> {
            if(tagList.isEmpty()) return;

            final String tagname = tagList.get(0);

            switch(tagname) {
                case "expiration": this.expiration = Integer.parseInt(tagList.get(1)); break;
                default: break;
            }
        });
    }
}
