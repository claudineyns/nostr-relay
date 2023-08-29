package io.github.social.nostr.relay.server;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import io.github.social.nostr.relay.specs.EventState;
import io.github.social.nostr.relay.utilities.LogService;
import io.github.social.nostr.relay.websocket.BinaryMessage;
import io.github.social.nostr.relay.websocket.TextMessage;
import io.github.social.nostr.relay.websocket.Websocket;
import io.github.social.nostr.relay.websocket.WebsocketException;

@SuppressWarnings("unused")
public class WebsocketHandler implements Websocket {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());
    private final NostrService nostr = new NostrService();

    @Override
    public byte onOpen(final WebsocketContext context) {
        return logger.info("[WS] Server ready to accept data.");
    }

    @Override
    public byte onClose(final WebsocketContext context) {
        return logger.info("[WS] Client gone. Bye.");
    }

    @Override
    public byte onMessage(final WebsocketContext context, final TextMessage message) {
        //logger.info("[WS] Server received message of type {}", message.getType());
        return nostr.consume(context, message);
    }

    @Override
    public byte onMessage(final WebsocketContext context, final BinaryMessage message) {
        //return logger.info("[WS] Server received message of type {}.", message.getType());
        return 0;
    }

    @Override
    public byte onError(WebsocketException exception) {
        return logger.info("[WS] Server got error: {}", exception.getMessage());
    }

    @Override
    public byte onServerShutdown() {
        return nostr.close();
    }
    

}
