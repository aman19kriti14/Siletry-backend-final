package com.siletry.lead;

import com.siletry.common.ApiException;
import com.siletry.ops.OpsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LeadServiceTest {

    private LeadRepository repo;
    private ApplicationEventPublisher events;
    private LeadService service;

    @BeforeEach
    void setUp() {
        repo = mock(LeadRepository.class);
        events = mock(ApplicationEventPublisher.class);
        OpsProperties props = new OpsProperties(null, null, null,
                new OpsProperties.Leads("salt", 3, Duration.ofMinutes(10), Duration.ofHours(24), 6, Duration.ofMinutes(1)));
        LeadRateLimiter limiter = new LeadRateLimiter(props);
        service = new LeadService(repo, limiter, events, props);
        when(repo.findFirstByPhoneAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any())).thenReturn(Optional.empty());
        when(repo.save(any(Lead.class))).thenAnswer(inv -> {
            Lead l = inv.getArgument(0);
            l.setId(42L);
            return l;
        });
    }

    private static LeadDtos.CreateLeadRequest req(String name, String phone, String city, String clinic) {
        return new LeadDtos.CreateLeadRequest(name, phone, city, clinic, null, null, "/", "google", "cpc", "launch");
    }

    @Test
    void savesValidLeadAndRaisesEvent() {
        LeadService.Outcome out = service.submit(req("  Dr. Anitha   Rao ", "+91 98765 43210", "Indore", "Sunrise Clinic"), "1.2.3.4", "UA");

        assertThat(out).isEqualTo(LeadService.Outcome.CREATED);
        ArgumentCaptor<Lead> saved = ArgumentCaptor.forClass(Lead.class);
        verify(repo).save(saved.capture());
        Lead l = saved.getValue();
        assertThat(l.getName()).isEqualTo("Dr. Anitha Rao");
        assertThat(l.getPhone()).isEqualTo("919876543210");
        assertThat(l.getCity()).isEqualTo("Indore");
        assertThat(l.getClinicName()).isEqualTo("Sunrise Clinic");
        assertThat(l.getSource()).isEqualTo("website");
        assertThat(l.getUtmSource()).isEqualTo("google");
        assertThat(l.getIpHash()).hasSize(64).doesNotContain("1.2.3.4");
        assertThat(l.getNotifyStatus()).isEqualTo(Lead.NotifyStatus.PENDING);
        assertThat(l.getNotifyNextAt()).isAfter(LocalDateTime.now());
        verify(events).publishEvent(new LeadService.LeadCreatedEvent(42L));
    }

    @Test
    void acceptsTenDigitAndZeroPrefixedNumbers() {
        service.submit(req("Ravi", "9876543210", "Pune", null), "1.1.1.1", null);
        service.submit(req("Ravi K", "09876543211", "Pune", null), "1.1.1.2", null);
        ArgumentCaptor<Lead> saved = ArgumentCaptor.forClass(Lead.class);
        verify(repo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Lead::getPhone).containsExactly("919876543210", "919876543211");
    }

    @Test
    void honeypotIsSilentlyIgnored() {
        var bot = new LeadDtos.CreateLeadRequest("Bot", "9876543210", "X", null, "http://spam", null, null, null, null, null);
        assertThat(service.submit(bot, "1.2.3.4", null)).isEqualTo(LeadService.Outcome.IGNORED);
        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void rejectsBadInputWithReadableMessages() {
        assertBadRequest(req("", "9876543210", "Pune", null), "Please enter your name.");
        assertBadRequest(req("A", "9876543210", "Pune", null), "Please enter your name.");
        assertBadRequest(req("<script>", "9876543210", "Pune", null), "Please enter your name.");
        assertBadRequest(req("Ravi", "12345", "Pune", null), "Please enter a valid 10-digit mobile number.");
        assertBadRequest(req("Ravi", "5876543210", "Pune", null), "Please enter a valid 10-digit mobile number.");
        assertBadRequest(req("Ravi", "+1 415 555 0100", "Pune", null), "Please enter a valid 10-digit mobile number.");
        assertBadRequest(req("Ravi", "9876543210", "", null), "Please enter your city.");
        assertBadRequest(req("Ravi", "9876543210", "123", null), "Please enter your city.");
        verify(repo, never()).save(any());
    }

    @Test
    void acceptsNamesInIndianScripts() {
        assertThat(service.submit(req("डॉ. अनीता", "9876543210", "इंदौर", null), "9.9.9.9", null))
                .isEqualTo(LeadService.Outcome.CREATED);
        assertThat(service.submit(req("ಅನಿತಾ ರಾವ್", "9876543211", "ಬೆಂಗಳೂರು", null), "9.9.9.8", null))
                .isEqualTo(LeadService.Outcome.CREATED);
    }

    @Test
    void duplicateWithinWindowBumpsCountWithoutNewAlert() {
        Lead existing = new Lead();
        existing.setId(7L);
        existing.setPhone("919876543210");
        when(repo.findFirstByPhoneAndCreatedAtAfterOrderByCreatedAtDesc(eq("919876543210"), any())).thenReturn(Optional.of(existing));

        LeadService.Outcome out = service.submit(req("Ravi", "9876543210", "Pune", "Ravi Clinic"), "1.2.3.4", null);

        assertThat(out).isEqualTo(LeadService.Outcome.DUPLICATE);
        assertThat(existing.getSubmissionCount()).isEqualTo(2);
        assertThat(existing.getClinicName()).isEqualTo("Ravi Clinic");
        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void rateLimitsValidSubmissionsPerClientButNotTypos() {
        // Typos never count towards the limit
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> service.submit(req("Ravi", "123", "Pune", null), "5.5.5.5", null)).isInstanceOf(ApiException.class);
        }
        service.submit(req("Ravi", "9876543210", "Pune", null), "5.5.5.5", null);
        service.submit(req("Ravi", "9876543211", "Pune", null), "5.5.5.5", null);
        service.submit(req("Ravi", "9876543212", "Pune", null), "5.5.5.5", null);
        assertThatThrownBy(() -> service.submit(req("Ravi", "9876543213", "Pune", null), "5.5.5.5", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        // Another client is unaffected
        assertThat(service.submit(req("Ravi", "9876543214", "Pune", null), "6.6.6.6", null)).isEqualTo(LeadService.Outcome.CREATED);
    }

    @Test
    void cleanStripsControlAndInvisibleCharacters() {
        assertThat(LeadService.clean("  a\u0000b​c\n\nd  ", 50)).isEqualTo("a b c d");
        assertThat(LeadService.clean("   ", 50)).isNull();
        assertThat(LeadService.clean("abcdef", 3)).isEqualTo("abc");
    }

    private void assertBadRequest(LeadDtos.CreateLeadRequest r, String message) {
        assertThatThrownBy(() -> service.submit(r, "1.2.3.4", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }
}
