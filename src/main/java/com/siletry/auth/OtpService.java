package com.siletry.auth;

import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.messaging.OutboundMessageService;
import com.siletry.messaging.gateway.GatewayRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 6-digit codes on WhatsApp from the central Siletry number.
 * With WHATSAPP_PROVIDER=mock the code is also returned to the client (devCode) so you can test without WhatsApp.
 */
@Service
public class OtpService {

    public static final int EXPIRY_MINUTES = 10;
    public static final int RESEND_SECONDS = 45;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PER_HOUR = 6;

    public record OtpTicket(String ticket, String maskedPhone, int expiresInSeconds, int resendInSeconds, String devCode) {}

    private final OtpCodeRepository codes;
    private final OutboundMessageService outbound;
    private final GatewayRouter router;
    private final boolean devTools;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpCodeRepository codes, OutboundMessageService outbound, GatewayRouter router,
                      @Value("${siletry.dev-tools}") boolean devTools) {
        this.codes = codes;
        this.outbound = outbound;
        this.router = router;
        this.devTools = devTools;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public OtpTicket send(String rawPhone, OtpCode.Purpose purpose, String payload) {
        String phone = Phone.normalize(rawPhone);
        if (phone == null || phone.length() < 11) throw ApiException.badRequest("Enter a valid mobile number");
        LocalDateTime now = LocalDateTime.now();

        codes.findFirstByPhoneOrderByCreatedAtDesc(phone).ifPresent(last -> {
            long secs = Duration.between(last.getCreatedAt(), now).getSeconds();
            if (secs < RESEND_SECONDS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Please wait " + (RESEND_SECONDS - secs) + " seconds before asking for another code");
            }
        });
        if (codes.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1)) >= MAX_PER_HOUR) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested. Try again in an hour or use your password.");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode o = new OtpCode();
        o.setTicket(UUID.randomUUID().toString());
        o.setPhone(phone);
        o.setPurpose(purpose);
        o.setCodeHash(hash(o.getTicket(), code));
        o.setExpiresAt(now.plusMinutes(EXPIRY_MINUTES));
        o.setPayload(payload);
        codes.save(o);

        var sent = outbound.sendLoginCode(phone, code,
                code + " is your Siletry code. It expires in " + EXPIRY_MINUTES + " minutes. Don't share it with anyone.");

        // Show the code on screen only in test setups where WhatsApp can't deliver it.
        // Never in production: anyone could sign in as anyone.
        boolean showCode = devTools && !router.adminIsReal();
        if (!sent.ok() && !showCode) {
            throw ApiException.badRequest("We couldn't send the code on WhatsApp. Sign in with your password, or try again in a minute.");
        }
        return new OtpTicket(o.getTicket(), mask(phone), EXPIRY_MINUTES * 60, RESEND_SECONDS, showCode ? code : null);
    }

    /** Sends a fresh code for the same purpose and payload. */
    @Transactional(noRollbackFor = ApiException.class)
    public OtpTicket resend(String ticket) {
        OtpCode old = codes.findByTicket(ticket).orElseThrow(() -> ApiException.badRequest("That code request has expired. Start again."));
        if (old.getConsumedAt() != null) throw ApiException.badRequest("This code was already used");
        return send(old.getPhone(), old.getPurpose(), old.getPayload());
    }

    /** Checks the code. Returns the stored request on success. */
    @Transactional(noRollbackFor = ApiException.class)
    public OtpCode verify(String ticket, String code, OtpCode.Purpose purpose) {
        OtpCode o = codes.findByTicket(ticket).orElseThrow(() -> ApiException.badRequest("That code request has expired. Ask for a new code."));
        if (o.getPurpose() != purpose || o.getConsumedAt() != null) throw ApiException.badRequest("Ask for a new code");
        if (o.getExpiresAt().isBefore(LocalDateTime.now())) throw ApiException.badRequest("This code has expired. Ask for a new one.");
        if (o.getAttempts() >= MAX_ATTEMPTS) throw ApiException.badRequest("Too many wrong tries. Ask for a new code.");
        String clean = code == null ? "" : code.replaceAll("\\D", "");
        if (!MessageDigest.isEqual(hash(ticket, clean).getBytes(StandardCharsets.UTF_8), o.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            o.setAttempts(o.getAttempts() + 1);
            int left = MAX_ATTEMPTS - o.getAttempts();
            throw ApiException.badRequest(left > 0 ? "That code isn't right. " + left + " tries left." : "Too many wrong tries. Ask for a new code.");
        }
        o.setConsumedAt(LocalDateTime.now());
        return o;
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 919845012345 -> +91 98450 ••• 45 */
    public static String mask(String phone) {
        String p = Phone.pretty(phone);
        if (p == null || p.length() < 8) return p;
        return p.substring(0, p.length() - 6) + " ••• " + p.substring(p.length() - 2);
    }
}
