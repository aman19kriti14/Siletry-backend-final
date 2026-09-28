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
 * - clinic connected to the Cloud API -> Meta (real)
 * - clinic not connected, or anything inside the simulator -> mock
 * - Siletry's own number (doctor alerts, login codes) -> Meta when META_ADMIN_* is set, else mock
 */
@Component
public class GatewayRouter {

    private static final ThreadLocal<Boolean> SIMULATING = ThreadLocal.withInitial(() -> false);

    private final MockWhatsAppGateway mock;
    private final MetaCloudApiGateway meta;
    private final CredentialCipher cipher;
    private final WaAccount admin;

    public GatewayRouter(MockWhatsAppGateway mock, MetaCloudApiGateway meta, CredentialCipher cipher,
                         @Value("${siletry.meta.admin-phone-number-id}") String adminId,
                         @Value("${siletry.meta.admin-access-token}") String adminToken,
                         @Value("${siletry.meta.admin-display-number}") String adminDisplay) {
        this.mock = mock;
        this.meta = meta;
        this.cipher = cipher;
        this.admin = adminId.isBlank() || adminToken.isBlank() ? null : new WaAccount(adminId, adminToken, Phone.normalize(adminDisplay));
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
        return !SIMULATING.get() && c.hasWhatsAppApi() ? meta : mock;
    }

    public WaAccount accountFor(Clinic c) {
        if (!c.hasWhatsAppApi()) return new WaAccount(null, null, c.getWhatsappNumber());
        return new WaAccount(c.getWaPhoneNumberId(), cipher.decrypt(c.getWaAccessToken()), c.getWhatsappNumber());
    }

    public boolean isRealForClinic(Clinic c) {
        return forClinic(c) == meta;
    }

    public WhatsAppGateway adminGateway() {
        return !SIMULATING.get() && admin != null ? meta : mock;
    }

    public WaAccount adminAccount() {
        return admin;
    }

    /** True when Siletry's own number really sends (login codes arrive on WhatsApp). */
    public boolean adminIsReal() {
        return admin != null;
    }

    public String adminPhoneNumberId() {
        return admin == null ? null : admin.phoneNumberId();
    }
}
