package com.siletry.ops;

import com.siletry.lead.Lead;
import com.siletry.messaging.OutboundMessageService;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.WhatsAppGateway;
import com.siletry.messaging.gateway.WhatsAppGateway.SendResult;
import com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage;
import com.siletry.messaging.gateway.WhatsAppGateway.WaAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OpsNotifierTest {

    private OutboundMessageService outbound;
    private GatewayRouter router;

    @BeforeEach
    void setUp() {
        outbound = mock(OutboundMessageService.class);
        router = mock(GatewayRouter.class);
        when(router.adminIsReal()).thenReturn(true);
    }

    private static OpsProperties props(String to, String mode) {
        return new OpsProperties(null, to, mode, null);
    }

    private static Lead lead() {
        Lead l = new Lead();
        l.setId(1L);
        l.setName("Dr Rajesh Ranjan");
        l.setPhone("918228968530");
        l.setCity("Patna");
        l.setCreatedAt(LocalDateTime.of(2026, 10, 13, 23, 7));
        return l;
    }

    @Test
    void textMatchesTheTeamAlertFormat() {
        assertThat(OpsNotifier.text(lead())).isEqualTo("""
                💬 New Demo Request

                Name: Dr Rajesh Ranjan
                WhatsApp: +91 82289 68530
                City: Patna
                Time: 13 Oct 2026, 11:07 PM

                Contact them within a few hours to book their demo.
                https://wa.me/918228968530""");
    }

    @Test
    void templateSummaryIsOneLine() {
        Lead l = lead();
        l.setClinicName("Ranjan Clinic");
        assertThat(OpsNotifier.summary(l))
                .isEqualTo("New demo request from Dr Rajesh Ranjan (Ranjan Clinic), Patna, WhatsApp +91 82289 68530. Contact them within a few hours.")
                .doesNotContain("\n");
    }

    @Test
    void textModeSendsFromSiletryNumberToEveryRecipient() {
        when(outbound.sendAdmin(anyString(), anyString(), anyList(), any())).thenReturn(SendResult.ok("wamid.1"));
        OpsNotifier n = new OpsNotifier(outbound, router, props("+91 82289 68530, 9000000001", null));

        OpsNotifier.Result r = n.newLead(lead());

        assertThat(r.outcome()).isEqualTo(OpsNotifier.Outcome.SENT);
        assertThat(r.messageId()).isEqualTo("wamid.1");
        ArgumentCaptor<TemplateMessage> fallback = ArgumentCaptor.forClass(TemplateMessage.class);
        verify(outbound).sendAdmin(eq("918228968530"), startsWith("💬 New Demo Request"), eq(List.of()), fallback.capture());
        verify(outbound).sendAdmin(eq("919000000001"), anyString(), eq(List.of()), any());
        assertThat(fallback.getValue().name()).isEqualTo("siletry_staff_alert");
        assertThat(fallback.getValue().bodyParams()).containsExactly("siletry.com", OpsNotifier.summary(lead()));
    }

    @Test
    void templateModeSendsTheApprovedTemplate() {
        WhatsAppGateway gateway = mock(WhatsAppGateway.class);
        WaAccount account = new WaAccount("PNID", "TOKEN", "15550001111");
        when(router.adminGateway()).thenReturn(gateway);
        when(router.adminAccount()).thenReturn(account);
        when(gateway.sendTemplate(any(), anyString(), any())).thenReturn(SendResult.ok("wamid.T"));
        OpsNotifier n = new OpsNotifier(outbound, router, props("918228968530", "template"));

        assertThat(n.newLead(lead()).outcome()).isEqualTo(OpsNotifier.Outcome.SENT);
        ArgumentCaptor<TemplateMessage> t = ArgumentCaptor.forClass(TemplateMessage.class);
        verify(gateway).sendTemplate(eq(account), eq("918228968530"), t.capture());
        assertThat(t.getValue().name()).isEqualTo("siletry_staff_alert");
        verifyNoInteractions(outbound);
    }

    @Test
    void reportsFailureWhenNobodyGotIt() {
        when(outbound.sendAdmin(anyString(), anyString(), anyList(), any())).thenReturn(SendResult.failed("Invalid token", 190));
        OpsNotifier.Result r = new OpsNotifier(outbound, router, props("918228968530", null)).newLead(lead());
        assertThat(r.outcome()).isEqualTo(OpsNotifier.Outcome.FAILED);
        assertThat(r.error()).contains("****8530").contains("Invalid token");
    }

    @Test
    void notConfiguredWithoutRecipientsOrSiletryNumber() {
        assertThat(new OpsNotifier(outbound, router, props(null, null)).newLead(lead()).outcome())
                .isEqualTo(OpsNotifier.Outcome.NOT_CONFIGURED);
        when(router.adminIsReal()).thenReturn(false);
        assertThat(new OpsNotifier(outbound, router, props("918228968530", null)).newLead(lead()).outcome())
                .isEqualTo(OpsNotifier.Outcome.NOT_CONFIGURED);
        verifyNoInteractions(outbound);
    }

    @Test
    void recipientsAreNormalizedAndDeduped() {
        assertThat(props("+91 82289 68530, 918228968530 ,9000000001,12", null).alertRecipients())
                .containsExactly("918228968530", "919000000001");
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.startsWith(prefix);
    }
}
