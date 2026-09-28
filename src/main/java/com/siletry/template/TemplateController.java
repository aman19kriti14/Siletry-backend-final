package com.siletry.template;

import com.siletry.auth.CurrentUser;
import com.siletry.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    public record TemplateView(Long id, String name, MessageTemplate.Category category, String language, String body,
                               MessageTemplate.Status status, String rejectionReason) {
        static TemplateView of(MessageTemplate t) {
            return new TemplateView(t.getId(), t.getName(), t.getCategory(), t.getLanguage(), t.getBody(),
                    t.getStatus(), t.getRejectionReason());
        }
    }

    public record SaveTemplate(@NotBlank String name, MessageTemplate.Category category, String language, @NotBlank String body) {}

    private final MessageTemplateRepository templates;
    private final com.siletry.messaging.MetaTemplateService meta;
    private final com.siletry.clinic.ClinicRepository clinics;

    public TemplateController(MessageTemplateRepository templates, com.siletry.messaging.MetaTemplateService meta,
                              com.siletry.clinic.ClinicRepository clinics) {
        this.templates = templates;
        this.meta = meta;
        this.clinics = clinics;
    }

    /** Submits Siletry's standard templates to the clinic's WhatsApp account and refreshes statuses. */
    @PostMapping("/submit-all")
    public com.siletry.messaging.MetaTemplateService.SyncResult submitAll() {
        CurrentUser.requireOwner();
        return meta.submitAll(clinics.findById(CurrentUser.clinicId()).orElseThrow());
    }

    /** Pulls approval status from Meta. */
    @PostMapping("/sync")
    public com.siletry.messaging.MetaTemplateService.SyncResult sync() {
        return meta.sync(clinics.findById(CurrentUser.clinicId()).orElseThrow());
    }

    @GetMapping
    @Transactional
    public List<TemplateView> list() {
        meta.seed(CurrentUser.clinicId());
        return templates.findByClinicIdOrderByNameAsc(CurrentUser.clinicId()).stream().map(TemplateView::of).toList();
    }

    @PostMapping
    @Transactional
    public TemplateView create(@Valid @RequestBody SaveTemplate req) {
        MessageTemplate t = new MessageTemplate();
        t.setClinicId(CurrentUser.clinicId());
        apply(t, req);
        return TemplateView.of(templates.save(t));
    }

    @PutMapping("/{id}")
    @Transactional
    public TemplateView update(@PathVariable Long id, @Valid @RequestBody SaveTemplate req) {
        MessageTemplate t = templates.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Template"));
        apply(t, req);
        t.setStatus(MessageTemplate.Status.DRAFT); // edited templates need re-approval
        return TemplateView.of(t);
    }

    /** Marks as submitted. Real submission to the provider comes with the Gupshup adapter. */
    @PostMapping("/{id}/submit")
    @Transactional
    public TemplateView submit(@PathVariable Long id) {
        MessageTemplate t = templates.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Template"));
        t.setStatus(MessageTemplate.Status.SUBMITTED);
        return TemplateView.of(t);
    }

    private void apply(MessageTemplate t, SaveTemplate req) {
        t.setName(req.name().trim().toLowerCase().replaceAll("[^a-z0-9_]", "_"));
        if (req.category() != null) t.setCategory(req.category());
        if (req.language() != null) t.setLanguage(req.language());
        t.setBody(req.body());
    }
}
