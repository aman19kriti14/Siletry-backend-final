package com.siletry.lead;

import java.time.LocalDateTime;

public final class LeadDtos {

    private LeadDtos() {}

    /**
     * Body of POST /api/public/leads. Validated in LeadService so every error message is
     * one a visitor can read, not "field: must match pattern".
     * {@code website} is a honeypot: humans never see or fill it.
     */
    public record CreateLeadRequest(
            String name,
            String phone,
            String city,
            String clinic,
            String website,
            String source,
            String page,
            String utmSource,
            String utmMedium,
            String utmCampaign
    ) {}

    /** The public endpoint never echoes data back; it only confirms receipt. */
    public record CreateLeadResponse(boolean ok) {}

    public record UpdateLeadRequest(Lead.Status status, String note) {}

    public record LeadView(
            Long id,
            String name,
            String phone,
            String city,
            String clinicName,
            Lead.Status status,
            String statusNote,
            String source,
            String page,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            int submissionCount,
            Lead.NotifyStatus notifyStatus,
            int notifyAttempts,
            String notifyError,
            LocalDateTime notifiedAt,
            LocalDateTime createdAt,
            LocalDateTime lastSubmittedAt
    ) {
        static LeadView of(Lead l) {
            return new LeadView(l.getId(), l.getName(), l.getPhone(), l.getCity(), l.getClinicName(), l.getStatus(),
                    l.getStatusNote(), l.getSource(), l.getPage(), l.getUtmSource(), l.getUtmMedium(), l.getUtmCampaign(),
                    l.getSubmissionCount(), l.getNotifyStatus(), l.getNotifyAttempts(), l.getNotifyError(),
                    l.getNotifiedAt(), l.getCreatedAt(), l.getLastSubmittedAt());
        }
    }
}
