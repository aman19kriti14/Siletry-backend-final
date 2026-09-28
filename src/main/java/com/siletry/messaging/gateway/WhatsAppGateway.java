package com.siletry.messaging.gateway;

import com.siletry.messaging.Message;

import java.util.List;

/**
 * Sends WhatsApp messages. Two implementations: MockWhatsAppGateway (logs only) and
 * MetaCloudApiGateway (real). GatewayRouter picks one per clinic.
 */
public interface WhatsAppGateway {

    String name();

    /** Free-form text. Only delivered inside the 24-hour window. */
    SendResult sendText(WaAccount from, String to, String body);

    /** Up to 3 reply buttons; more become a list. Window only. */
    SendResult sendButtons(WaAccount from, String to, String body, List<String> buttons);

    /** Approved template. Works outside the 24-hour window. */
    SendResult sendTemplate(WaAccount from, String to, TemplateMessage template);

    /** The WhatsApp number we send from: Meta phone number ID + access token. */
    record WaAccount(String phoneNumberId, String accessToken, String displayNumber) {}

    /**
     * A template send. bodyParams fill {{1}}, {{2}}...
     * codeParam is only for AUTHENTICATION templates (fills the copy-code button).
     */
    record TemplateMessage(String name, String language, List<String> bodyParams, String codeParam) {
        public static TemplateMessage of(String name, String... params) {
            return new TemplateMessage(name, "en", List.of(params), null);
        }
    }

    record SendResult(boolean ok, String externalId, String error, Integer errorCode) {
        public static SendResult ok(String id) { return new SendResult(true, id, null, null); }
        public static SendResult failed(String error) { return new SendResult(false, null, error, null); }
        public static SendResult failed(String error, Integer code) { return new SendResult(false, null, error, code); }
    }

    /** accountId = Meta phone_number_id the message was sent to (null for the dev webhook). */
    record InboundMessage(String toNumber, String fromPhone, String profileName, String text, String externalId, String accountId) {
        public InboundMessage(String toNumber, String fromPhone, String profileName, String text, String externalId) {
            this(toNumber, fromPhone, profileName, text, externalId, null);
        }
    }

    record StatusUpdate(String externalId, Message.Status status, String error) {}

    record WebhookBatch(List<InboundMessage> messages, List<StatusUpdate> statuses) {}
}
