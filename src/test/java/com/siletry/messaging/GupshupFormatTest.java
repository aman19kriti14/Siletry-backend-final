package com.siletry.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.messaging.gateway.MetaCloudApiGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.WebhookBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GupshupFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String INBOUND = """
            {"gs_app_id":"app-123","object":"whatsapp_business_account","entry":[{"id":"waba","changes":[{"field":"messages",
             "value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"918047182200","phone_number_id":"pn-1"},
             "contacts":[{"wa_id":"919845012345","profile":{"name":"Lakshmi"}}],
             "messages":[{"from":"919845012345","id":"wamid.A","timestamp":"1","type":"text","text":{"body":"hi"}}]}}]}]}
            """;

    private static final String STATUS = """
            {"gs_app_id":"app-123","object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages",
             "value":{"metadata":{"phone_number_id":"pn-1"},
             "statuses":[{"id":"wamid.B","gs_id":"8b149927-uuid","status":"delivered"}]}}]}]}
            """;

    @Test
    void parsesInboundMessageInMetaFormat() throws Exception {
        WebhookBatch b = MetaCloudApiGateway.parse(mapper.readTree(INBOUND), true);
        assertEquals(1, b.messages().size());
        var m = b.messages().get(0);
        assertEquals("919845012345", m.fromPhone());
        assertEquals("Lakshmi", m.profileName());
        assertEquals("hi", m.text());
        assertEquals("wamid.A", m.externalId());
        assertEquals("pn-1", m.accountId());
    }

    @Test
    void statusesUseGupshupIdWhenAsked() throws Exception {
        assertEquals("8b149927-uuid", MetaCloudApiGateway.parse(mapper.readTree(STATUS), true).statuses().get(0).externalId());
        assertEquals("wamid.B", MetaCloudApiGateway.parse(mapper.readTree(STATUS), false).statuses().get(0).externalId());
    }

    @Test
    void templateFieldsCarryExampleAndQuickReplies() {
        Map<String, Object> f = GupshupTemplateService.fields(TemplateCatalog.APPOINTMENT_REMINDER);
        assertEquals("appointment_reminder", f.get("elementName"));
        assertEquals("UTILITY", f.get("category"));
        assertEquals("TEXT", f.get("templateType"));
        String example = (String) f.get("example");
        assertFalse(example.contains("{{"), "example must have every variable filled");
        assertTrue(example.contains("Dr. Anitha Rao"));
        assertEquals(3, ((List<?>) f.get("buttons")).size());
    }
}
