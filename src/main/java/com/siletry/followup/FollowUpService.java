package com.siletry.followup;

import com.siletry.auth.CurrentUser;
import com.siletry.common.ApiException;
import com.siletry.patient.PatientRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class FollowUpService {

    public record FollowUpView(Long id, Long patientId, Long doctorId, String title, LocalDate dueDate,
                               FollowUp.Status status, Long appointmentId) {
        static FollowUpView of(FollowUp f) {
            return new FollowUpView(f.getId(), f.getPatientId(), f.getDoctorId(), f.getTitle(), f.getDueDate(),
                    f.getStatus(), f.getAppointmentId());
        }
    }

    public record CreateFollowUp(@NotNull Long patientId, Long doctorId, @NotBlank String title, @NotNull LocalDate dueDate) {}

    public record UpdateFollowUp(String title, LocalDate dueDate, FollowUp.Status status, Long doctorId) {}

    private final FollowUpRepository followUps;
    private final PatientRepository patients;

    public FollowUpService(FollowUpRepository followUps, PatientRepository patients) {
        this.followUps = followUps;
        this.patients = patients;
    }

    @Transactional(readOnly = true)
    public List<FollowUpView> forPatient(Long patientId) {
        return followUps.findByClinicIdAndPatientIdOrderByDueDateAsc(CurrentUser.clinicId(), patientId)
                .stream().map(FollowUpView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<FollowUpView> dueSoon(int days) {
        return followUps.findOpenDueBy(CurrentUser.clinicId(), LocalDate.now().plusDays(days))
                .stream().map(FollowUpView::of).toList();
    }

    @Transactional
    public FollowUpView create(CreateFollowUp req) {
        Long clinicId = CurrentUser.clinicId();
        patients.findByIdAndClinicId(req.patientId(), clinicId).orElseThrow(() -> ApiException.notFound("Patient"));
        FollowUp f = new FollowUp();
        f.setClinicId(clinicId);
        f.setPatientId(req.patientId());
        f.setDoctorId(req.doctorId());
        f.setTitle(req.title().trim());
        f.setDueDate(req.dueDate());
        return FollowUpView.of(followUps.save(f));
    }

    @Transactional
    public FollowUpView update(Long id, UpdateFollowUp req) {
        FollowUp f = followUps.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Follow-up"));
        if (req.title() != null) f.setTitle(req.title().trim());
        if (req.dueDate() != null) {
            f.setDueDate(req.dueDate());
            f.setNotifiedAt(null);
            if (f.getStatus() == FollowUp.Status.OFFERED) f.setStatus(FollowUp.Status.DUE);
        }
        if (req.status() != null) f.setStatus(req.status());
        if (req.doctorId() != null) f.setDoctorId(req.doctorId());
        return FollowUpView.of(f);
    }
}
