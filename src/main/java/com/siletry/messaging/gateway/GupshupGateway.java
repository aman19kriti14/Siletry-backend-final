package com.siletry.messaging.gateway;

import com.siletry.messaging.gateway.MetaGraphClient.MetaApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Real WhatsApp through Gupshup's passthrough API. Same message bodies as Meta's Cloud API.
 * For this gateway WaAccount.phoneNumberId holds the Gupshup app ID and accessToken the app token.
 */
@Component
public class GupshupGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(GupshupGateway.class);

    private final GupshupClient client;

    public GupshupGateway(GupshupClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "gupshup";
    }

    @Override
    public SendResult sendText(WaAccount from, String to, String body) {
        return send(from, MetaCloudApiGateway.textMessage(to, body));
    }

    @Override
    public SendResult sendButtons(WaAccount from, String to, String body, List<String> buttons) {
        return send(from, MetaCloudApiGateway.buttonsMessage(to, body, buttons));
    }

    @Override
    public SendResult sendTemplate(WaAccount from, String to, TemplateMessage t) {
        return send(from, MetaCloudApiGateway.templateMessage(to, t));
    }

    private SendResult send(WaAccount from, Map<String, Object> msg) {
        if (from == null || from.phoneNumberId() == null || from.accessToken() == null) {
            return SendResult.failed("WhatsApp isn't connected for this number");
        }
        try {
            return SendResult.ok(client.sendMessage(from.phoneNumberId(), from.accessToken(), msg));
        } catch (MetaApiException e) {
            log.warn("Gupshup send failed ({}): {}", e.getCode(), e.getMessage());
            String friendly = e.getCode() == 401 || e.getCode() == 403
                    ? "Gupshup rejected the app token. Reconnect this clinic's Gupshup app."
                    : MetaCloudApiGateway.friendly(e);
            return SendResult.failed(friendly, e.getCode());
        }
    }
}
