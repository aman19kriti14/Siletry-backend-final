package com.siletry.lead;

import com.siletry.messaging.OutboundMessageService;
import com.siletry.messaging.gateway.WhatsAppGateway.SendResult;
import com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full flow against a real Postgres (CI service, or docker-compose locally):
 * website POST -> lead saved -> WhatsApp alert sent after commit from Siletry's number -> visible in the ops API.
 * Only the final WhatsApp call is replaced, so nothing reaches Meta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeadFlowIntegrationTest {

    static final String OPS_KEY = "test-ops-key-0123456789abcdef";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("siletry.seed-demo", () -> "false");
        r.add("siletry.meta.admin-phone-number-id", () -> "PNID");
        r.add("siletry.meta.admin-access-token", () -> "TOKEN");
        r.add("siletry.meta.admin-display-number", () -> "15550001111");
        r.add("siletry.ops.api-key", () -> OPS_KEY);
        r.add("siletry.ops.alert-to", () -> "919000000001");
        r.add("siletry.ops.leads.rate-limit-max", () -> "100");
    }

    @MockBean OutboundMessageService outbound;
    @Autowired MockMvc mvc;
    @Autowired LeadRepository leads;

    @BeforeEach
    void clean() {
        leads.deleteAll();
        reset(outbound);
        when(outbound.sendAdmin(anyString(), anyString(), anyList(), any())).thenReturn(SendResult.ok("wamid.IT"));
    }

    @Test
    void websiteLeadIsSavedAlertedAndListed() throws Exception {
        mvc.perform(post("/api/public/leads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.9")
                        .content("""
                                {"name":"Dr. Anitha Rao","phone":"98765 43210","city":"Indore","clinic":"Sunrise Clinic",
                                 "website":"","page":"/","utmSource":"google"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ok").value(true));

        Lead saved = waitForAlert();
        assertThat(saved.getPhone()).isEqualTo("919876543210");
        assertThat(saved.getUtmSource()).isEqualTo("google");
        assertThat(saved.getNotifyStatus()).isEqualTo(Lead.NotifyStatus.SENT);
        assertThat(saved.getNotifyMessageId()).isEqualTo("wamid.IT");
        assertThat(saved.getNotifyAttempts()).isEqualTo(1);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(outbound, times(1)).sendAdmin(eq("919000000001"), text.capture(), eq(List.of()), any(TemplateMessage.class));
        assertThat(text.getValue())
                .startsWith("💬 New Demo Request")
                .contains("Name: Dr. Anitha Rao", "WhatsApp: +91 98765 43210", "City: Indore", "Clinic: Sunrise Clinic",
                        "https://wa.me/919876543210");

        // Same number again: no second row, no second alert
        mvc.perform(post("/api/public/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anitha\",\"phone\":\"+919876543210\",\"city\":\"Indore\"}"))
                .andExpect(status().isAccepted());
        Thread.sleep(500);
        assertThat(leads.count()).isEqualTo(1);
        assertThat(leads.findAll().get(0).getSubmissionCount()).isEqualTo(2);
        verify(outbound, times(1)).sendAdmin(anyString(), anyString(), anyList(), any());

        // Ops API
        mvc.perform(get("/api/ops/leads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ops/leads").header("X-Ops-Key", OPS_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Dr. Anitha Rao"))
                .andExpect(jsonPath("$.content[0].notifyStatus").value("SENT"));
        mvc.perform(patch("/api/ops/leads/" + saved.getId()).header("X-Ops-Key", OPS_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONTACTED\",\"note\":\"Demo Friday\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTACTED"));
    }

    @Test
    void failedAlertIsRecordedForRetry() throws Exception {
        when(outbound.sendAdmin(anyString(), anyString(), anyList(), any())).thenReturn(SendResult.failed("Meta is down"));
        mvc.perform(post("/api/public/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi Kumar\",\"phone\":\"9123456780\",\"city\":\"Lucknow\"}"))
                .andExpect(status().isAccepted());
        Lead l = waitFor(Lead.NotifyStatus.FAILED);
        assertThat(l.getNotifyAttempts()).isEqualTo(1);
        assertThat(l.getNotifyError()).contains("Meta is down");
        assertThat(l.getNotifyNextAt()).isNotNull();
    }

    @Test
    void invalidInputGetsAFriendlyError() throws Exception {
        mvc.perform(post("/api/public/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi\",\"phone\":\"123\",\"city\":\"Pune\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Please enter a valid 10-digit mobile number."));
        assertThat(leads.count()).isZero();
    }

    @Test
    void honeypotSubmissionIsAcceptedButNotSaved() throws Exception {
        mvc.perform(post("/api/public/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bot\",\"phone\":\"9876543210\",\"city\":\"X\",\"website\":\"spam\"}"))
                .andExpect(status().isAccepted());
        assertThat(leads.count()).isZero();
    }

    private Lead waitForAlert() throws InterruptedException {
        return waitFor(Lead.NotifyStatus.SENT);
    }

    private Lead waitFor(Lead.NotifyStatus status) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<Lead> all = leads.findAll();
            if (!all.isEmpty() && all.get(0).getNotifyStatus() == status) return all.get(0);
            Thread.sleep(100);
        }
        List<Lead> all = leads.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getNotifyStatus()).isEqualTo(status);
        return all.get(0);
    }
}
