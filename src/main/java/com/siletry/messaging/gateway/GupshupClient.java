package com.siletry.messaging.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Tiny client for Gupshup's Partner API (https://partner.gupshup.io).
 *
 * Two kinds of token:
 *  - partner token: from email + client secret (GUPSHUP_PARTNER_EMAIL / GUPSHUP_PARTNER_SECRET), valid 24 hours,
 *    used to manage apps (get an app's token)
 *  - app token: one per app (= one per WhatsApp number), used to send messages and manage templates
 *
 * Gupshup takes form-encoded bodies; nested objects (template, interactive, text) are sent as JSON strings.
 * Errors are thrown as MetaApiException so the rest of the app handles both providers the same way.
 */
@Component
public class GupshupClient {

    private static final Logger log = LoggerFactory.getLogger(GupshupClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final String base;
    private final String partnerEmail;
    private final String partnerSecret;

    private volatile String partnerToken;
    private volatile Instant partnerTokenExpires = Instant.EPOCH;

    public GupshupClient(ObjectMapper mapper,
                         @Value("${siletry.gupshup.base-url}") String base,
                         @Value("${siletry.gupshup.partner-email}") String partnerEmail,
                         @Value("${siletry.gupshup.partner-secret}") String partnerSecret) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.partnerEmail = partnerEmail;
        this.partnerSecret = partnerSecret;
    }

    public boolean partnerConfigured() {
        return !partnerEmail.isBlank() && !partnerSecret.isBlank();
    }

    // ------------------------------------------------------------------
    // Messages (app token)
    // ------------------------------------------------------------------

    /** Sends a Meta-format message through Gupshup's passthrough. Returns Gupshup's message id. */
    public String sendMessage(String appId, String appToken, Map<String, Object> metaMessage) {
        JsonNode res = form("POST", "/partner/app/" + enc(appId) + "/v3/message", appToken, metaMessage);
        String id = res.path("messages").path(0).path("id").asText(null);
        if (id == null) id = res.path("messageId").asText(null);
        return id;
    }

    // ------------------------------------------------------------------
    // Apps and templates
    // ------------------------------------------------------------------

    /** The app's access token (Gupshup returns the existing one if there is one). Needs the partner login. */
    public String appToken(String appId) {
        JsonNode res = request("GET", "/partner/app/" + enc(appId) + "/token", partnerToken(), null, null);
        String token = res.path("token").path("token").asText(null);
        if (token == null || token.isBlank()) throw new MetaApiException(-1, "Gupshup didn't return a token for app " + appId);
        return token;
    }

    /** Points the app's incoming messages and delivery updates at Siletry, in Meta's format (v3). */
    public JsonNode subscribe(String appId, String appToken, String url) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("modes", "MESSAGE,SENT,DELIVERED,READ,FAILED");
        f.put("tag", "siletry");
        f.put("showOnUI", "false");
        f.put("version", "3");
        f.put("url", url);
        return form("POST", "/partner/app/" + enc(appId) + "/subscription", appToken, f);
    }

    public JsonNode createTemplate(String appId, String appToken, Map<String, Object> fields) {
        return form("POST", "/partner/app/" + enc(appId) + "/templates", appToken, fields);
    }

    public JsonNode templates(String appId, String appToken) {
        return request("GET", "/partner/app/" + enc(appId) + "/templates", appToken, null, null);
    }

    // ------------------------------------------------------------------

    private synchronized String partnerToken() {
        if (!partnerConfigured()) {
            throw new MetaApiException(-1, "Gupshup partner login isn't set (GUPSHUP_PARTNER_EMAIL / GUPSHUP_PARTNER_SECRET)");
        }
        if (partnerToken != null && Instant.now().isBefore(partnerTokenExpires)) return partnerToken;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("email", partnerEmail);
        f.put("secret", partnerSecret);
        JsonNode res = request("POST", "/partner/account/login", null, "application/x-www-form-urlencoded", formBody(f));
        String t = res.path("token").asText(null);
        if (t == null || t.isBlank()) throw new MetaApiException(-1, "Gupshup partner login failed");
        partnerToken = t;
        partnerTokenExpires = Instant.now().plus(Duration.ofHours(23)); // valid 24h
        return t;
    }

    private JsonNode form(String method, String path, String token, Map<String, Object> fields) {
        return request(method, path, token, "application/x-www-form-urlencoded", formBody(fields));
    }

    private String formBody(Map<String, Object> fields) {
        StringJoiner sj = new StringJoiner("&");
        for (var e : fields.entrySet()) {
            if (e.getValue() == null) continue;
            String v;
            if (e.getValue() instanceof String s) v = s;
            else if (e.getValue() instanceof Number || e.getValue() instanceof Boolean) v = String.valueOf(e.getValue());
            else {
                try {
                    v = mapper.writeValueAsString(e.getValue());
                } catch (Exception ex) {
                    throw new MetaApiException(-1, "Couldn't encode " + e.getKey());
                }
            }
            sj.add(enc(e.getKey()) + "=" + enc(v));
        }
        return sj.toString();
    }

    private JsonNode request(String method, String path, String token, String contentType, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json");
            if (token != null) b.header("Authorization", token);
            if (contentType != null) b.header("Content-Type", contentType);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return handle(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
        } catch (MetaApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaApiException(-1, "Couldn't reach Gupshup: " + e.getMessage());
        }
    }

    private JsonNode handle(HttpResponse<String> res) throws Exception {
        String raw = res.body() == null ? "" : res.body().trim();
        JsonNode json;
        try {
            json = raw.isEmpty() ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (Exception e) {
            json = mapper.createObjectNode().put("message", raw.length() > 300 ? raw.substring(0, 300) : raw);
        }
        boolean metaError = json.has("error") && json.path("error").isObject();
        boolean gsError = "error".equalsIgnoreCase(json.path("status").asText(""));
        if (res.statusCode() >= 300 || metaError || gsError) {
            int code = res.statusCode();
            String msg;
            if (metaError) {
                JsonNode err = json.path("error");
                code = err.path("code").asInt(code);
                msg = err.path("error_user_msg").asText(err.path("message").asText("WhatsApp returned an error"));
                String details = err.path("error_data").path("details").asText("");
                if (!details.isBlank()) msg += " " + details;
            } else {
                msg = json.path("message").asText(json.path("error").asText("Gupshup returned an error (" + res.statusCode() + ")"));
            }
            log.debug("Gupshup error {}: {}", code, raw);
            throw new MetaApiException(code, msg);
        }
        return json;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
