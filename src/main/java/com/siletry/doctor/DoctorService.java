package com.siletry.doctor;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.appointment.SlotService;
import com.siletry.auth.CurrentUser;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.doctor.DoctorDtos.*;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DoctorService {

    private final DoctorRepository doctors;
    private final DoctorSessionRepository sessions;
    private final DoctorLeaveRepository leaves;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final SlotService slots;

    public DoctorService(DoctorRepository doctors, DoctorSessionRepository sessions, DoctorLeaveRepository leaves,
                         AppointmentRepository appointments, PatientRepository patients, SlotService slots) {
        this.doctors = doctors;
        this.sessions = sessions;
        this.leaves = leaves;
        this.appointments = appointments;
        this.patients = patients;
        this.slots = slots;
    }

    public Doctor get(Long id) {
        return doctors.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("Doctor"));
    }

    @Transactional(readOnly = true)
    public List<DoctorView> list() {
        return doctors.findByClinicIdOrderByNameAsc(CurrentUser.clinicId()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DoctorView one(Long id) {
        return view(get(id));
    }

    @Transactional
    public DoctorView create(CreateDoctorRequest req) {
        Doctor d = new Doctor();
        d.setClinicId(CurrentUser.clinicId());
        d.setName(req.name().trim());
        d.setSpeciality(req.speciality());
        d.setRegistrationNo(req.registrationNo());
        d.setFee(req.fee());
        d.setAlertMobile(Phone.normalize(req.alertMobile()));
        d.setSlotMinutes(req.slotMinutes() == null ? 15 : req.slotMinutes());
        d.setLanguages(joinLanguages(req.languages()));
        return view(doctors.save(d));
    }

    @Transactional
    public DoctorView update(Long id, UpdateDoctorRequest req) {
        Doctor d = get(id);
        if (req.name() != null) d.setName(req.name().trim());
        if (req.speciality() != null) d.setSpeciality(req.speciality());
        if (req.registrationNo() != null) d.setRegistrationNo(req.registrationNo());
        if (req.fee() != null) d.setFee(req.fee());
        if (req.alertMobile() != null) d.setAlertMobile(Phone.normalize(req.alertMobile()));
        if (req.slotMinutes() != null) {
            if (req.slotMinutes() < 5 || req.slotMinutes() > 120) throw ApiException.badRequest("Slot length must be 5 to 120 minutes");
            d.setSlotMinutes(req.slotMinutes());
        }
        if (req.languages() != null) d.setLanguages(joinLanguages(req.languages()));
        if (req.active() != null) d.setActive(req.active());
        if (req.bookingRules() != null) {
            BookingRules r = req.bookingRules();
            d.setMinNoticeMinutes(Math.max(0, r.minNoticeMinutes()));
            d.setMaxAdvanceDays(Math.max(1, r.maxAdvanceDays()));
            d.setMaxBotBookingsPerDay(r.maxBotBookingsPerDay());
            d.setBookableOnWhatsapp(r.bookableOnWhatsapp());
        }
        return view(d);
    }

    /**
     * Replaces the weekly hours. Returns future appointments that now fall outside the hours,
     * for staff to move. Siletry never cancels them on its own.
     */
    @Transactional
    public HoursUpdateResult updateHours(Long id, HoursUpdate req) {
        Doctor d = get(id);
        for (SessionDto s : req.sessions()) {
            if (!s.end().isAfter(s.start())) {
                throw ApiException.badRequest(s.day() + ": end time must be after start time");
            }
        }
        validateNoOverlap(req.sessions());

        sessions.deleteByDoctorId(d.getId());
        sessions.flush();
        for (SessionDto s : req.sessions()) {
            DoctorSession ds = new DoctorSession();
            ds.setClinicId(d.getClinicId());
            ds.setDoctorId(d.getId());
            ds.setDayOfWeek(s.day());
            ds.setStartTime(s.start());
            ds.setEndTime(s.end());
            sessions.save(ds);
        }
        sessions.flush();

        LocalDateTime now = LocalDateTime.now();
        List<Appointment> future = appointments.findForDoctorInRange(d.getId(), now, now.plusDays(365)).stream()
                .filter(a -> a.getStatus() == AppointmentStatus.CONFIRMED)
                .filter(a -> !slots.isWithinHours(d, a.getStartAt(), a.getEndAt()))
                .toList();
        Map<Long, Patient> pmap = patients.findAllById(future.stream().map(Appointment::getPatientId).toList())
                .stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        List<AffectedAppointment> affected = future.stream().map(a -> {
            Patient p = pmap.get(a.getPatientId());
            return new AffectedAppointment(a.getId(), a.getStartAt(),
                    p == null ? "" : p.getName(), p == null ? "" : Phone.pretty(p.getPhone()));
        }).toList();
        return new HoursUpdateResult(view(d), affected);
    }

    @Transactional(readOnly = true)
    public List<LeaveDto> listLeave(Long doctorId) {
        Doctor d = get(doctorId);
        return leaves.findUpcoming(d.getId(), LocalDateTime.now().minusDays(1)).stream()
                .map(l -> new LeaveDto(l.getId(), l.getStartAt(), l.getEndAt(), l.getReason())).toList();
    }

    @Transactional
    public LeaveDto addLeave(Long doctorId, LeaveDto req) {
        Doctor d = get(doctorId);
        if (!req.endAt().isAfter(req.startAt())) throw ApiException.badRequest("Leave must end after it starts");
        DoctorLeave l = new DoctorLeave();
        l.setClinicId(d.getClinicId());
        l.setDoctorId(d.getId());
        l.setStartAt(req.startAt());
        l.setEndAt(req.endAt());
        l.setReason(req.reason());
        l = leaves.save(l);
        return new LeaveDto(l.getId(), l.getStartAt(), l.getEndAt(), l.getReason());
    }

    @Transactional
    public void deleteLeave(Long doctorId, Long leaveId) {
        get(doctorId);
        DoctorLeave l = leaves.findByIdAndClinicId(leaveId, CurrentUser.clinicId())
                .filter(x -> x.getDoctorId().equals(doctorId))
                .orElseThrow(() -> ApiException.notFound("Leave"));
        leaves.delete(l);
    }

    public DoctorView view(Doctor d) {
        List<SessionDto> ss = sessions.findByDoctorIdOrderByDayOfWeekAscStartTimeAsc(d.getId()).stream()
                .map(s -> new SessionDto(s.getDayOfWeek(), s.getStartTime(), s.getEndTime())).toList();
        return new DoctorView(
                d.getId(), d.getName(), d.shortName(), d.initials(), d.getSpeciality(), d.getRegistrationNo(),
                d.getFee(), Phone.pretty(d.getAlertMobile()), d.getSlotMinutes(), d.languageList(), d.isActive(),
                todayStatus(d, ss), slots.slotsThisWeek(d),
                leaves.findUpcoming(d.getId(), LocalDateTime.now()).size(),
                new BookingRules(d.getMinNoticeMinutes(), d.getMaxAdvanceDays(), d.getMaxBotBookingsPerDay(), d.isBookableOnWhatsapp()),
                ss);
    }

    private TodayStatus todayStatus(Doctor d, List<SessionDto> ss) {
        LocalDate today = LocalDate.now();
        boolean worksToday = ss.stream().anyMatch(s -> s.day() == today.getDayOfWeek());
        if (!worksToday) return TodayStatus.OFF_TODAY;
        LocalDateTime ds = today.atStartOfDay();
        List<DoctorLeave> todayLeaves = leaves.findOverlapping(d.getId(), ds, ds.plusDays(1));
        // On leave for the whole working day?
        boolean fullDay = todayLeaves.stream().anyMatch(l ->
                ss.stream().filter(s -> s.day() == today.getDayOfWeek()).allMatch(s ->
                        !l.getStartAt().isAfter(today.atTime(s.start())) && !l.getEndAt().isBefore(today.atTime(s.end()))));
        return fullDay ? TodayStatus.ON_LEAVE : TodayStatus.IN_CLINIC;
    }

    private void validateNoOverlap(List<SessionDto> list) {
        Map<java.time.DayOfWeek, List<SessionDto>> byDay = list.stream().collect(Collectors.groupingBy(SessionDto::day));
        for (var e : byDay.entrySet()) {
            List<SessionDto> day = new ArrayList<>(e.getValue());
            day.sort(Comparator.comparing(SessionDto::start));
            for (int i = 1; i < day.size(); i++) {
                if (day.get(i).start().isBefore(day.get(i - 1).end())) {
                    throw ApiException.badRequest(e.getKey() + ": sessions overlap");
                }
            }
        }
    }

    private String joinLanguages(List<String> langs) {
        if (langs == null || langs.isEmpty()) return "en";
        return langs.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().collect(Collectors.joining(","));
    }
}
