package com.siletry.messaging.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tiny client for Meta's Graph API (WhatsApp Cloud API lives there). */
@Component
public class MetaGraphClient {

    public static class MetaApiException extends RuntimeException {
        private final int code;
        public MetaApiException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int getCode() { return code; }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String base;

    public MetaGraphClient(ObjectMapper mapper, @Value("${siletry.meta.graph-version}") String version) {
        this.mapper = mapper;
        this.base = "https://graph.facebook.com/" + version + "/";
    }

    public JsonNode post(String path, String token, Object body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return handle(http.send(req, HttpResponse.BodyHandlers.ofString()));
        } catch (MetaApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaApiException(-1, "Couldn't reach WhatsApp: " + e.getMessage());
        }
    }

    public JsonNode get(String pathAndQuery, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            return handle(http.send(req, HttpResponse.BodyHandlers.ofString()));
        } catch (MetaApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaApiException(-1, "Couldn't reach WhatsApp: " + e.getMessage());
        }
    }

    private JsonNode handle(HttpResponse<String> res) throws Exception {
        JsonNode json = res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
        if (res.statusCode() >= 300 || json.has("error")) {
            JsonNode err = json.path("error");
            String msg = err.path("error_user_msg").asText(null);
            if (msg == null || msg.isBlank()) msg = err.path("message").asText("WhatsApp returned an error (" + res.statusCode() + ")");
            String details = err.path("error_data").path("details").asText("");
            throw new MetaApiException(err.path("code").asInt(res.statusCode()), details.isBlank() ? msg : msg + " " + details);
        }
        return json;
    }
}
