package com.siletry.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.siletry.auth.CurrentUser;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QrService {

    /** Matches the code anywhere in the message, even if the patient edited the prefilled text. */
    public static final Pattern CODE_PATTERN = Pattern.compile("\\b((?:BK|CHECKIN)-[A-Z0-9]{1,30})\\b", Pattern.CASE_INSENSITIVE);

    public record QrView(Long id, String code, QrCode.Type type, String label, Long doctorId, long scans,
                         boolean active, String prefilledText, String waLink) {}

    public record CreateQr(@NotNull QrCode.Type type, @NotBlank String label, String code, Long doctorId) {}

    private final QrCodeRepository qrs;
    private final ClinicRepository clinics;

    public QrService(QrCodeRepository qrs, ClinicRepository clinics) {
        this.qrs = qrs;
        this.clinics = clinics;
    }

    @Transactional(readOnly = true)
    public List<QrView> list() {
        Clinic c = clinics.findById(CurrentUser.clinicId()).orElseThrow();
        return qrs.findByClinicIdOrderByTypeAscCodeAsc(c.getId()).stream().map(q -> view(c, q)).toList();
    }

    @Transactional
    public QrView create(CreateQr req) {
        Clinic c = clinics.findById(CurrentUser.clinicId()).orElseThrow();
        String prefix = req.type() == QrCode.Type.BOOKING ? "BK-" : "CHECKIN-";
        String suffix = req.code() == null || req.code().isBlank()
                ? req.label().toUpperCase().replaceAll("[^A-Z0-9]", "")
                : req.code().toUpperCase().replaceFirst("^(BK-|CHECKIN-)", "").replaceAll("[^A-Z0-9]", "");
        if (suffix.isEmpty()) suffix = "QR";
        if (suffix.length() > 20) suffix = suffix.substring(0, 20);
        String code = prefix + suffix;
        if (qrs.findByClinicIdAndCode(c.getId(), code).isPresent()) throw ApiException.conflict("Code " + code + " already exists");
        return view(c, qrs.save(build(c.getId(), code, req.type(), req.label(), req.doctorId())));
    }

    @Transactional
    public QrView setActive(Long id, boolean active) {
        Clinic c = clinics.findById(CurrentUser.clinicId()).orElseThrow();
        QrCode q = qrs.findByIdAndClinicId(id, c.getId()).orElseThrow(() -> ApiException.notFound("QR code"));
        q.setActive(active);
        return view(c, q);
    }

    @Transactional(readOnly = true)
    public byte[] png(Long id, int size) {
        Clinic c = clinics.findById(CurrentUser.clinicId()).orElseThrow();
        QrCode q = qrs.findByIdAndClinicId(id, c.getId()).orElseThrow(() -> ApiException.notFound("QR code"));
        String link = waLink(c, q);
        if (link == null) throw ApiException.badRequest("Connect the clinic's WhatsApp number first");
        try {
            BitMatrix m = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create QR image", e);
        }
    }

    /** Called when an inbound message contains a code. Returns the QR if it's one of ours. */
    @Transactional
    public QrCode recordScan(Long clinicId, String code) {
        return qrs.findByClinicIdAndCode(clinicId, code.toUpperCase()).map(q -> {
            q.setScans(q.getScans() + 1);
            return q;
        }).orElse(null);
    }

    public static String extractCode(String text) {
        if (text == null) return null;
        Matcher m = CODE_PATTERN.matcher(text);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** Default codes every clinic starts with. */
    @Transactional
    public void createDefaults(Long clinicId) {
        qrs.save(build(clinicId, "BK-POSTER", QrCode.Type.BOOKING, "Poster outside the clinic", null));
        qrs.save(build(clinicId, "BK-RX", QrCode.Type.BOOKING, "Back of prescription slips", null));
        qrs.save(build(clinicId, "BK-CARD", QrCode.Type.BOOKING, "Visiting cards and pamphlets", null));
        qrs.save(build(clinicId, "BK-MAPS", QrCode.Type.BOOKING, "Google Maps listing link", null));
        qrs.save(build(clinicId, "CHECKIN-DESK", QrCode.Type.CHECKIN, "Reception desk", null));
    }

    private QrCode build(Long clinicId, String code, QrCode.Type type, String label, Long doctorId) {
        QrCode q = new QrCode();
        q.setClinicId(clinicId);
        q.setCode(code);
        q.setType(type);
        q.setLabel(label);
        q.setDoctorId(doctorId);
        return q;
    }

    public static String prefilledText(Clinic c, QrCode q) {
        return q.getType() == QrCode.Type.BOOKING
                ? "Hi " + c.getName() + ", I'd like to book an appointment [" + q.getCode() + "]"
                : "Check in at " + c.getName() + " [" + q.getCode() + "]";
    }

    public static String waLink(Clinic c, QrCode q) {
        if (c.getWhatsappNumber() == null) return null;
        return "https://wa.me/" + c.getWhatsappNumber() + "?text="
                + URLEncoder.encode(prefilledText(c, q), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private QrView view(Clinic c, QrCode q) {
        return new QrView(q.getId(), q.getCode(), q.getType(), q.getLabel(), q.getDoctorId(), q.getScans(),
                q.isActive(), prefilledText(c, q), waLink(c, q));
    }
}
