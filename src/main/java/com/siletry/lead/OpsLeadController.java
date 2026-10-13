package com.siletry.lead;

import com.siletry.common.ApiException;
import com.siletry.ops.OpsProperties;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * For the Siletry team, not clinics. Protected by the OPS_API_KEY sent in the X-Ops-Key header.
 * Disabled (503) until a key of at least 24 characters is configured.
 *
 *   GET   /api/ops/leads?status=NEW&page=0&size=50
 *   PATCH /api/ops/leads/{id}          {"status":"CONTACTED","note":"Called, demo on Friday"}
 *   POST  /api/ops/leads/{id}/notify   re-send the WhatsApp alert
 */
@RestController
@RequestMapping("/api/ops/leads")
public class OpsLeadController {

    private final LeadService leads;
    private final LeadRepository repo;
    private final LeadNotificationService notifications;
    private final OpsProperties props;

    public OpsLeadController(LeadService leads, LeadRepository repo, LeadNotificationService notifications, OpsProperties props) {
        this.leads = leads;
        this.repo = repo;
        this.notifications = notifications;
        this.props = props;
    }

    @GetMapping
    public Page<LeadDtos.LeadView> list(@RequestHeader(value = "X-Ops-Key", required = false) String key,
                                        @RequestParam(required = false) Lead.Status status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        authorize(key);
        return leads.list(status, page, size);
    }

    @PatchMapping("/{id}")
    public LeadDtos.LeadView update(@RequestHeader(value = "X-Ops-Key", required = false) String key,
                                    @PathVariable Long id, @RequestBody LeadDtos.UpdateLeadRequest body) {
        authorize(key);
        return leads.update(id, body);
    }

    @PostMapping("/{id}/notify")
    public Map<String, Object> resend(@RequestHeader(value = "X-Ops-Key", required = false) String key, @PathVariable Long id) {
        authorize(key);
        Lead l = repo.findById(id).orElseThrow(() -> ApiException.notFound("Lead"));
        // Allow an explicit re-send even after success or skip
        l.setNotifyStatus(Lead.NotifyStatus.FAILED);
        l.setNotifyAttempts(0);
        repo.save(l);
        notifications.deliver(id);
        Lead after = repo.findById(id).orElseThrow();
        return Map.of("notifyStatus", after.getNotifyStatus(),
                "error", after.getNotifyError() == null ? "" : after.getNotifyError());
    }

    private void authorize(String key) {
        if (!props.apiEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Ops API is disabled. Set OPS_API_KEY.");
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.apiKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid ops key");
        }
    }
}
