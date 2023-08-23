package io.github.claudineyns.nostr.relay.specs;

import java.util.List;

import com.google.gson.annotations.SerializedName;

public class ReqData {

    @SerializedName("kinds")
    private List<Integer> kinds;

    @SerializedName("authors")
    private List<String> authors;

    @SerializedName("limit")
    private int limit;

    @SerializedName("#p")
    private List<String> publicKeys;

    @SerializedName("#e")
    private List<String> events;

    
}
