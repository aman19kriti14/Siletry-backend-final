package com.siletry.seed;

import com.siletry.auth.AppUser;
import com.siletry.auth.AppUserRepository;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.ops.ProductionSafetyCheck;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DemoLockdownTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void locksDemoLoginsAndDisconnectsDemoClinic() {
        AppUserRepository users = mock(AppUserRepository.class);
        ClinicRepository clinics = mock(ClinicRepository.class);
        ProductionSafetyCheck env = mock(ProductionSafetyCheck.class);
        when(env.isProduction()).thenReturn(true);

        AppUser demo = new AppUser();
        demo.setClinicId(7L);
        demo.setPasswordHash(encoder.encode("demo1234"));
        AppUser desk = new AppUser();
        desk.setClinicId(7L);
        desk.setPasswordHash(encoder.encode("a-changed-password"));
        Clinic c = new Clinic();
        c.setGupshupAppId("app");
        c.setWaAccessToken("enc");
        c.setWhatsappConnected(true);
        c.setWhatsappNumber("918228968530");

        when(users.findByEmailIgnoreCase("demo@siletry.com")).thenReturn(Optional.of(demo));
        when(users.findByEmailIgnoreCase("desk@siletry.com")).thenReturn(Optional.of(desk));
        when(clinics.findById(7L)).thenReturn(Optional.of(c));

        new DemoLockdown(env, users, clinics, encoder, null).lockDown();

        assertThat(demo.isActive()).as("published password -> locked").isFalse();
        assertThat(desk.isActive()).as("password already changed -> left alone").isTrue();
        assertThat(c.hasWhatsAppApi()).isFalse();
        assertThat(c.getWhatsappNumber()).isNull();
        assertThat(c.isBotEnabled()).isFalse();
    }
}
