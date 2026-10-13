package com.siletry.lead;

import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.ops.OpsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    /** Indian mobile numbers, normalized: 91 + 10 digits starting 6-9. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^91[6-9]\\d{9}$");
    /** Letters (any script), spaces and a few name punctuation marks. */
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'\\-]{1,79}$");
    private static final Pattern CITY = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'\\-]{1,59}$");

    /** Raised after the lead is committed; the alert is sent from a listener. */
    public record LeadCreatedEvent(Long leadId) {}

    private final LeadRepository leads;
    private final LeadRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final OpsProperties.Leads config;

    public LeadService(LeadRepository leads, LeadRateLimiter rateLimiter, ApplicationEventPublisher events, OpsProperties props) {
        this.leads = leads;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.config = props.leads();
    }

    public enum Outcome { CREATED, DUPLICATE, IGNORED }

    @Transactional
    public Outcome submit(LeadDtos.CreateLeadRequest req, String clientIp, String userAgent) {
        if (req == null) throw ApiException.badRequest("Please fill in the form.");
        String ipHash = hashIp(clientIp);

        // Honeypot filled in: a bot. Pretend it worked so it learns nothing.
        if (notBlank(req.website())) {
            log.info("Lead ignored: honeypot filled (ip {})", shortHash(ipHash));
            return Outcome.IGNORED;
        }

        String name = clean(req.name(), 80);
        String city = clean(req.city(), 60);
        String clinic = clean(req.clinic(), 100);
        String phone = Phone.normalize(req.phone());

        if (name == null || name.length() < 2 || !NAME.matcher(name).matches()) {
            throw ApiException.badRequest("Please enter your name.");
        }
        if (phone == null || !INDIAN_MOBILE.matcher(phone).matches()) {
            throw ApiException.badRequest("Please enter a valid 10-digit mobile number.");
        }
        if (city == null || city.length() < 2 || !CITY.matcher(city).matches()) {
            throw ApiException.badRequest("Please enter your city.");
        }

        // Counted only for well-formed submissions, so fixing a typo never locks anyone out
        if (!rateLimiter.tryAcquire(ipHash)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again in a few minutes.");
        }

        LocalDateTime now = LocalDateTime.now();
        var existing = leads.findFirstByPhoneAndCreatedAtAfterOrderByCreatedAtDesc(phone, now.minus(config.duplicateWindow()));
        if (existing.isPresent()) {
            Lead l = existing.get();
            l.setSubmissionCount(l.getSubmissionCount() + 1);
            l.setLastSubmittedAt(now);
            if (clinic != null && l.getClinicName() == null) l.setClinicName(clinic);
            log.info("Lead {} submitted again ({} times); no new alert", l.getId(), l.getSubmissionCount());
            return Outcome.DUPLICATE;
        }

        Lead l = new Lead();
        l.setName(name);
        l.setPhone(phone);
        l.setCity(city);
        l.setClinicName(clinic);
        l.setSource(clean(req.source(), 40) == null ? "website" : clean(req.source(), 40));
        l.setPage(clean(req.page(), 200));
        l.setUtmSource(clean(req.utmSource(), 100));
        l.setUtmMedium(clean(req.utmMedium(), 100));
        l.setUtmCampaign(clean(req.utmCampaign(), 100));
        l.setIpHash(ipHash);
        l.setUserAgent(clean(userAgent, 300));
        // The listener sends right after commit; the retry job only steps in if that fails
        l.setNotifyNextAt(now.plus(config.retryBaseDelay()));
        leads.save(l);
        log.info("Lead {} created (city {}, source {})", l.getId(), city, l.getSource());

        events.publishEvent(new LeadCreatedEvent(l.getId()));
        return Outcome.CREATED;
    }

    @Transactional(readOnly = true)
    public Page<LeadDtos.LeadView> list(Lead.Status status, int page, int size) {
        PageRequest p = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<Lead> result = status == null ? leads.findAllByOrderByCreatedAtDesc(p) : leads.findByStatusOrderByCreatedAtDesc(status, p);
        return result.map(LeadDtos.LeadView::of);
    }

    @Transactional
    public LeadDtos.LeadView update(Long id, LeadDtos.UpdateLeadRequest req) {
        Lead l = leads.findById(id).orElseThrow(() -> ApiException.notFound("Lead"));
        if (req.status() != null) l.setStatus(req.status());
        if (req.note() != null) l.setStatusNote(clean(req.note(), 500));
        return LeadDtos.LeadView.of(l);
    }

    // ---- helpers ----

    /** Trims, collapses whitespace, strips control characters, caps length; blank becomes null. */
    static String clean(String v, int max) {
        if (v == null) return null;
        String s = v.replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u2028-\\u202E\\u2066-\\u2069]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    String hashIp(String ip) {
        String value = config.ipHashSalt() + "|" + (ip == null || ip.isBlank() ? "unknown" : ip.trim());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shortHash(String h) {
        return h == null ? "-" : h.substring(0, 8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
