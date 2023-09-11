package io.github.social.nostr.relay.specs;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.social.nostr.relay.utilities.Utils;

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
    private final List<String> referencedDataList = new ArrayList<>();
    private final List<ReplaceableMetadata> replaceableMetadataList = new ArrayList<>();

    private final EventGroup state;

    private final String payload;

    private final JsonObject json;

    private EventData(final JsonObject json) {
        this.id = json.get("id").getAsString();
        this.kind = json.get("kind").getAsInt();
        this.pubkey = json.get("pubkey").getAsString();
        this.content = json.get("content").getAsString();
        this.created_at = json.get("created_at").getAsInt();
        this.sig = json.get("sig").getAsString();

        this.state = EventGroup.byKind(kind);

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

    public EventGroup getState() {
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

    public List<String> getTagValuesByName(final String tagName) {
        return this.getTags()
            .stream()
            .filter(tagList -> tagList.size() > 1)
            .filter(tagList -> tagName.equals(tagList.get(0)))
            .map(tagList -> tagList.get(1))
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

    public Collection<ReplaceableMetadata> getReplaceableMetadataList() {
        return Collections.unmodifiableCollection(replaceableMetadataList);
    }

    public Collection<String> getReferencedDataList() {
        return Collections.unmodifiableCollection(referencedDataList);
    }

    public Set<String> getReferencedDataAsSet() {
        return this.getReferencedDataList().stream().collect(Collectors.toSet());
    }

    public Set<String> storableIds() {
        final Set<String> ids = new HashSet<>();

        switch(this.getState()) {

            case REGULAR:
                ids.add(this.getId());
                break;

            case REPLACEABLE:

                final String rId = Utils.sha256(
                    (this.getPubkey()+"#"+this.getKind()).getBytes(StandardCharsets.US_ASCII)
                );
                ids.add(rId);
                break;

            case PARAMETERIZED_REPLACEABLE:

                this.getReferencedDataList().forEach(data -> {
                    final String pId = Utils.sha256(
                        (this.getPubkey()+"#"+this.getKind()+"#"+data).getBytes(StandardCharsets.US_ASCII)
                    );
                    ids.add(pId);
                });
                break;

            default:
                break;
        }

        return ids;
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
                case "d": this.referencedDataList.add(v); break;
                case "a": this.replaceableMetadataList.add(ReplaceableMetadata.of(v)); break;
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
