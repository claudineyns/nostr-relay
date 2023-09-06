package io.github.social.nostr.relay.specs;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class EventData implements Comparable<EventData> {
    private final String id;
    private final String pubkey;
    private final String content;
    private final int kind;
    private final int created_at;
    private final String sig;

    private int expiration;

    private final List<List<String>> tags = new ArrayList<>();
    private final List<String> referencedPubkeyList = new ArrayList<>();
    private final List<String> referencedEventList = new ArrayList<>();
    private final List<String> infoNameList = new ArrayList<>();
    private final List<ParameterMetadata> coordinatedParameterList = new ArrayList<>();

    private final EventState state;

    private final String payload;

    private final JsonObject json;

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
        this.json = json;
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

    public List<List<String>> getTagsByName(final String tagName) {
        return this.getTags()
            .stream()
            .filter(tagList -> tagList.size() > 0)
            .filter(tagList -> tagName.equals(tagList.get(0)))
            .collect(Collectors.toList());
    }

    public Collection<List<String>> getTags() {
        return Collections.unmodifiableCollection(tags);
    }

    public Collection<String> getReferencedPubkeyList() {
        return Collections.unmodifiableCollection(referencedPubkeyList);
    }

    public Collection<String> getReferencedEventList() {
        return Collections.unmodifiableCollection(referencedEventList);
    }

    public Collection<ParameterMetadata> getCoordinatedParameterList() {
        return Collections.unmodifiableCollection(coordinatedParameterList);
    }

    public Collection<String> getInfoNameList() {
        return Collections.unmodifiableCollection(infoNameList);
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

    public JsonObject toJson() {
        return this.json;
    }

    public int compareTo(EventData o) {
        return o.getCreatedAt() - this.getCreatedAt();
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

            final String n = tagList.get(0);
            final String v = tagList.get(1);

            switch(n) {
                case "e": this.referencedEventList.add(v); break;
                case "p": this.referencedPubkeyList.add(v); break;
                case "d": this.infoNameList.add(v); break;
                case "a": this.coordinatedParameterList.add(ParameterMetadata.of(v)); break;
                case "expiration": this.expiration = Integer.parseInt(v); break;
                default: break;
            }
        });
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final EventData other = (EventData) obj;
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }

        return true;
    }

    
}
