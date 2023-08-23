package io.github.claudineyns.nostr.relay.specs;

import java.util.List;

import com.google.gson.annotations.SerializedName;

public class EventData {

    @SerializedName("id")
    private String eventId;

    @SerializedName("pubkey")
    private String publicKey;

    @SerializedName("created_at")
    private Integer createdAt;

    @SerializedName("kind")
    private Integer kind;

    @SerializedName("tags")
    private List<List<String>> tags;

    @SerializedName("content")
    private String content;

    @SerializedName("sig")
    private String signature;

    @SerializedName("ots")
    private String ots;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public Integer getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Integer createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getKind() {
        return kind;
    }

    public void setKind(Integer kind) {
        this.kind = kind;
    }

    public List<List<String>> getTags() {
        return tags;
    }

    public void setTags(List<List<String>> tags) {
        this.tags = tags;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

}
