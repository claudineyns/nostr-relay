package io.github.claudineyns.nostr.relay.specs;

import java.util.List;

import com.google.gson.annotations.SerializedName;

public class ReqData {

    @SerializedName("ids")
    private List<String> events;

    @SerializedName("kinds")
    private List<Integer> kinds;

    @SerializedName("authors")
    private List<String> authors;

    @SerializedName("#p")
    private List<String> taggedPublicKeys;

    @SerializedName("#e")
    private List<String> taggedEvents;

    @SerializedName("since")
    private Integer since;

    @SerializedName("until")
    private Integer until;

    @SerializedName("limit")
    private int limit;
    
}
