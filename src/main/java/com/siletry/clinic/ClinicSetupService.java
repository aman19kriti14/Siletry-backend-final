package com.siletry.clinic;

import com.siletry.messaging.MetaTemplateService;
import com.siletry.qr.QrService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything a new clinic gets on signup. */
@Service
public class ClinicSetupService {

    private final QrService qr;
    private final MetaTemplateService templates;
    private final int defaultCap;

    public ClinicSetupService(QrService qr, MetaTemplateService templates,
                              @Value("${siletry.default-message-cap}") int defaultCap) {
        this.qr = qr;
        this.templates = templates;
        this.defaultCap = defaultCap;
    }

    @Transactional
    public void createDefaults(Clinic clinic) {
        clinic.setMessageCap(defaultCap);
        qr.createDefaults(clinic.getId());
        templates.seed(clinic.getId());
    }
}
