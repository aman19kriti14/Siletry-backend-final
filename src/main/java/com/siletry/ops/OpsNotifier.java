package com.siletry.ops;

import com.siletry.common.Phone;
import com.siletry.lead.Lead;
import com.siletry.messaging.OutboundMessageService;
import com.siletry.messaging.TemplateCatalog;
import com.siletry.messaging.gateway.GatewayRouter;
import com.siletry.messaging.gateway.WhatsAppGateway.SendResult;
import com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sends team alerts (new demo leads) on WhatsApp from Siletry's own number.
 *
 * Text mode (default) sends the full alert as a normal message: it only arrives if the recipient
 * messaged Siletry's number in the last 24 hours. Template mode (OPS_ALERT_MODE=template) sends the
 * approved siletry_staff_alert template, which arrives any time.
 */
@Component
public class OpsNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsNotifier.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);
    static final String ALERT_SUBJECT = "siletry.com";

    public enum Outcome { SENT, FAILED, NOT_CONFIGURED }

    public record Result(Outcome outcome, String messageId, String error) {}

    private final OutboundMessageService outbound;
    private final GatewayRouter router;
    private final List<String> recipients;
    private final boolean template;

    public OpsNotifier(OutboundMessageService outbound, GatewayRouter router, OpsProperties props) {
        this.outbound = outbound;
        this.router = router;
        this.recipients = props.alertRecipients();
        this.template = props.useTemplate();
        if (recipients.isEmpty()) {
            log.warn("Lead alerts are OFF: set OPS_ALERT_TO to the WhatsApp number(s) that should get them");
        } else if (!router.adminIsReal()) {
            log.warn("Lead alerts are OFF: Siletry's own number is not set (META_ADMIN_PHONE_NUMBER_ID / META_ADMIN_ACCESS_TOKEN)");
        } else {
            log.info("Lead alerts ON for {} recipient(s), {} mode", recipients.size(), template ? "template" : "text");
        }
    }

    public boolean enabled() {
        return !recipients.isEmpty() && router.adminIsReal();
    }

    /** Succeeds if at least one recipient got the alert. */
    public Result newLead(Lead lead) {
        if (recipients.isEmpty()) return new Result(Outcome.NOT_CONFIGURED, null, "OPS_ALERT_TO is not set");
        if (!router.adminIsReal()) return new Result(Outcome.NOT_CONFIGURED, null, "Siletry's WhatsApp number is not set");

        String text = text(lead);
        TemplateMessage fallback = TemplateMessage.of(TemplateCatalog.STAFF_ALERT.name(), ALERT_SUBJECT, summary(lead));
        List<String> errors = new ArrayList<>();
        String firstId = null;
        for (String to : recipients) {
            SendResult r = template
                    ? router.adminGateway().sendTemplate(router.adminAccount(), to, fallback)
                    : outbound.sendAdmin(to, text, List.of(), fallback);
            if (r.ok()) {
                if (firstId == null) firstId = r.externalId();
            } else {
                errors.add(mask(to) + ": " + r.error());
            }
        }
        if (firstId != null) {
            if (!errors.isEmpty()) log.warn("Lead {} alert partly failed: {}", lead.getId(), errors);
            return new Result(Outcome.SENT, firstId, errors.isEmpty() ? null : String.join("; ", errors));
        }
        return new Result(Outcome.FAILED, null, String.join("; ", errors));
    }

    /** Full alert, sent as a normal message. */
    static String text(Lead lead) {
        StringBuilder sb = new StringBuilder();
        sb.append("💬 New Demo Request\n\n");
        sb.append("Name: ").append(lead.getName()).append('\n');
        sb.append("WhatsApp: ").append(Phone.pretty(lead.getPhone())).append('\n');
        sb.append("City: ").append(lead.getCity()).append('\n');
        if (lead.getClinicName() != null) sb.append("Clinic: ").append(lead.getClinicName()).append('\n');
        sb.append("Time: ").append(lead.getCreatedAt().format(WHEN)).append('\n');
        if (lead.getSubmissionCount() > 1) sb.append("Note: submitted ").append(lead.getSubmissionCount()).append(" times\n");
        sb.append("\nContact them within a few hours to book their demo.\n");
        sb.append("https://wa.me/").append(lead.getPhone());
        return sb.toString();
    }

    /**
     * One line for the siletry_staff_alert template ("Siletry update for {{1}}: {{2}} Reply to this message to respond.").
     * Template parameters can't contain line breaks.
     */
    static String summary(Lead lead) {
        String clinic = lead.getClinicName() == null ? "" : " (" + lead.getClinicName() + ")";
        return "New demo request from " + lead.getName() + clinic + ", " + lead.getCity() + ", WhatsApp "
                + Phone.pretty(lead.getPhone()) + ". Contact them within a few hours.";
    }

    private static String mask(String number) {
        return number.length() <= 4 ? "****" : "****" + number.substring(number.length() - 4);
    }
}
