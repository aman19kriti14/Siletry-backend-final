package com.siletry.seed;

import com.siletry.auth.AppUser;
import com.siletry.auth.AppUserRepository;
import com.siletry.clinic.ClinicRepository;
import com.siletry.ops.ProductionSafetyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * In production, the demo clinic's logins (published in DemoDataSeeder) must not work, and the demo
 * clinic must not send real WhatsApp messages to its made-up patients. On every boot in production:
 * demo users still on the published password are deactivated, and the demo clinic's WhatsApp is
 * disconnected and its bot turned off. Nothing is deleted.
 */
@Component
@Order(100) // the seeder only fills an empty database, and SEED_DEMO is off in production
public class DemoLockdown implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoLockdown.class);
    static final List<String> DEMO_EMAILS = List.of("demo@siletry.com", "desk@siletry.com");
    static final String DEMO_PASSWORD = "demo1234";

    private final ProductionSafetyCheck env;
    private final AppUserRepository users;
    private final ClinicRepository clinics;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public DemoLockdown(ProductionSafetyCheck env, AppUserRepository users, ClinicRepository clinics,
                        PasswordEncoder encoder, TransactionTemplate tx) {
        this.env = env;
        this.users = users;
        this.clinics = clinics;
        this.encoder = encoder;
        this.tx = tx;
    }

    @Override
    public void run(String... args) {
        if (!env.isProduction()) return;
        try {
            tx.executeWithoutResult(s -> lockDown());
        } catch (Exception e) {
            log.error("Demo lockdown failed", e);
        }
    }

    void lockDown() {
        for (String email : DEMO_EMAILS) {
            users.findByEmailIgnoreCase(email).ifPresent(u -> {
                if (u.isActive() && usesDemoPassword(u)) {
                    u.setActive(false);
                    log.warn("Deactivated demo login {} (published password) in production", email);
                }
                clinics.findById(u.getClinicId()).ifPresent(c -> {
                    if (c.isWhatsappConnected() || c.hasWhatsAppApi() || c.isBotEnabled()) {
                        c.setWaPhoneNumberId(null);
                        c.setWaBusinessAccountId(null);
                        c.setWaAccessToken(null);
                        c.setWaAppSecret(null);
                        c.setWaVerifiedName(null);
                        c.setWaConnectedAt(null);
                        c.setGupshupAppId(null);
                        c.setWhatsappConnected(false);
                        c.setWhatsappNumber(null); // frees the number for a real clinic
                        c.setBotEnabled(false);
                        c.setRemindersEnabled(false);
                        c.setFollowupsEnabled(false);
                        log.warn("Disconnected WhatsApp and paused the demo clinic {} in production", c.getId());
                    }
                });
            });
        }
    }

    private boolean usesDemoPassword(AppUser u) {
        try {
            return u.getPasswordHash() != null && encoder.matches(DEMO_PASSWORD, u.getPasswordHash());
        } catch (Exception e) {
            return false;
        }
    }
}
