package com.siletry.messaging.gateway;

import com.siletry.clinic.Clinic;
import com.siletry.common.CredentialCipher;
import com.siletry.common.Phone;
import com.siletry.messaging.gateway.WhatsAppGateway.WaAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Chooses how each message goes out:
 * - clinic connected through a Gupshup app -> Gupshup (real)
 * - clinic connected to the Cloud API directly -> Meta (real)
 * - clinic not connected, or anything inside the simulator -> mock
 * - Siletry's own number (doctor alerts, login codes): Gupshup when GUPSHUP_ADMIN_* is set,
 *   else Meta when META_ADMIN_* is set, else mock
 */
@Component
public class GatewayRouter {

    private static final ThreadLocal<Boolean> SIMULATING = ThreadLocal.withInitial(() -> false);

    private final MockWhatsAppGateway mock;
    private final MetaCloudApiGateway meta;
    private final GupshupGateway gupshup;
    private final CredentialCipher cipher;
    private final WaAccount admin;
    private final WhatsAppGateway adminReal;
    /** Gupshup app ID of Siletry's own number, when it runs on Gupshup. */
    private final String adminGupshupAppId;

    public GatewayRouter(MockWhatsAppGateway mock, MetaCloudApiGateway meta, GupshupGateway gupshup, CredentialCipher cipher,
                         @Value("${siletry.meta.admin-phone-number-id}") String adminId,
                         @Value("${siletry.meta.admin-access-token}") String adminToken,
                         @Value("${siletry.meta.admin-display-number}") String adminDisplay,
                         @Value("${siletry.gupshup.admin-app-id}") String gsAdminAppId,
                         @Value("${siletry.gupshup.admin-app-token}") String gsAdminToken,
                         @Value("${siletry.gupshup.admin-display-number}") String gsAdminDisplay) {
        this.mock = mock;
        this.meta = meta;
        this.gupshup = gupshup;
        this.cipher = cipher;
        if (!gsAdminAppId.isBlank() && !gsAdminToken.isBlank()) {
            this.admin = new WaAccount(gsAdminAppId.trim(), gsAdminToken.trim(), Phone.normalize(gsAdminDisplay));
            this.adminReal = gupshup;
            this.adminGupshupAppId = gsAdminAppId.trim();
        } else if (!adminId.isBlank() && !adminToken.isBlank()) {
            this.admin = new WaAccount(adminId, adminToken, Phone.normalize(adminDisplay));
            this.adminReal = meta;
            this.adminGupshupAppId = null;
        } else {
            this.admin = null;
            this.adminReal = null;
            this.adminGupshupAppId = null;
        }
    }

    /** Runs work where every message is mocked, even for connected clinics (the simulator). */
    public static <T> T simulate(Supplier<T> work) {
        boolean before = SIMULATING.get();
        SIMULATING.set(true);
        try {
            return work.get();
        } finally {
            SIMULATING.set(before);
        }
    }

    public boolean isSimulating() {
        return SIMULATING.get();
    }

    public WhatsAppGateway forClinic(Clinic c) {
        if (SIMULATING.get() || !c.hasWhatsAppApi()) return mock;
        return c.isGupshup() ? gupshup : meta;
    }

    /** For Gupshup clinics phoneNumberId carries the Gupshup app ID (that's what its send API addresses). */
    public WaAccount accountFor(Clinic c) {
        if (!c.hasWhatsAppApi()) return new WaAccount(null, null, c.getWhatsappNumber());
        String id = c.isGupshup() ? c.getGupshupAppId() : c.getWaPhoneNumberId();
        return new WaAccount(id, cipher.decrypt(c.getWaAccessToken()), c.getWhatsappNumber());
    }

    public boolean isRealForClinic(Clinic c) {
        return forClinic(c) != mock;
    }

    public WhatsAppGateway adminGateway() {
        return !SIMULATING.get() && admin != null ? adminReal : mock;
    }

    /** Gupshup app ID of Siletry's own number, or null when it isn't on Gupshup. */
    public String adminGupshupAppId() {
        return adminGupshupAppId;
    }

    public WaAccount adminAccount() {
        return admin;
    }

    /** True when Siletry's own number really sends (login codes arrive on WhatsApp). */
    public boolean adminIsReal() {
        return admin != null;
    }

    /** Meta phone number ID of Siletry's own number (only when it's on Meta directly). */
    public String adminPhoneNumberId() {
        return admin == null || adminGupshupAppId != null ? null : admin.phoneNumberId();
    }
}
