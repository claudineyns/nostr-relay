package io.github.social.nostr.relay.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;

import io.github.social.nostr.relay.dto.EventValidation;
import io.github.social.nostr.relay.utilities.AppProperties;
import io.github.social.nostr.relay.utilities.LogService;

@SuppressWarnings("unused")
public class EventValidationService {
    private final LogService logger = LogService.getInstance(getClass().getCanonicalName());

    final String scriptPath = AppProperties.getNostrEventScriptpath();
    final GsonBuilder gsonBuilder = new GsonBuilder();

    public EventValidation validate(final String eventJson) {
        try {
            return scriptValidation(eventJson);
        } catch(IOException f) {
            final EventValidation v = new EventValidation();
            v.setStatus(Boolean.FALSE);
            v.setMessage("Could not validate event");
            return v;
        }

    }

    private EventValidation scriptValidation(final String eventJson) throws IOException {
        final Gson gson = gsonBuilder.create();
        final String base64 = Base64
            .getEncoder()
            .encodeToString(eventJson.getBytes(StandardCharsets.UTF_8))
            .replace("\n","")
            .replace("+","-")
            .replace("/","_")
            .replace("=","");

        System.out.printf("[Nostr] [Special] validate event: %n%s%n", base64);

        try(final InputStream in = Runtime.getRuntime().exec(new String[] {scriptPath, base64}).getInputStream()) {
            return gson.fromJson(new InputStreamReader(in), EventValidation.class);
        } catch(JsonParseException failure) {
            throw new IOException(failure.getMessage());
        }
    }

    private final String validationHost = AppProperties.getEventValidationHost();
    private final int validationPort = AppProperties.getEventValidationPort();

    private EventValidation remoteValidation(final String eventJson) throws IOException {
        final Gson gson = gsonBuilder.create();

        final URL url = new URL("http://"+validationHost+":"+validationPort+"/event");
        final HttpURLConnection http = (HttpURLConnection) url.openConnection();

        http.setRequestMethod("POST");
        http.setDoInput(true);
        http.setDoOutput(true);
        http.setInstanceFollowRedirects(false);

        final byte[] raw = eventJson.getBytes(StandardCharsets.UTF_8);

        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("Content-Length", String.valueOf(raw.length));
        http.setRequestProperty("Connection", "close");

        final OutputStream out = http.getOutputStream();
        out.write(raw);
        out.flush();

        final InputStream in = http.getInputStream();
        final EventValidation validation = gson.fromJson(
            new InputStreamReader(in),
            EventValidation.class);
      
        http.disconnect();

        return validation;
    }
    
}
