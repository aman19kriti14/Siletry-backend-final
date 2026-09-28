package com.siletry.patient;

import com.siletry.appointment.Appointment;
import com.siletry.appointment.AppointmentRepository;
import com.siletry.appointment.AppointmentStatus;
import com.siletry.appointment.BookedBy;
import com.siletry.auth.CurrentUser;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.followup.FollowUp;
import com.siletry.followup.FollowUpRepository;
import com.siletry.messaging.ConversationRepository;
import com.siletry.patient.PatientDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PatientService {

	private final PatientRepository patients;
	private final ClinicRepository clinics;
	private final AppointmentRepository appointments;
	private final DoctorRepository doctors;
	private final FollowUpRepository followUps;
	private final ConversationRepository conversations;

	public PatientService(PatientRepository patients, ClinicRepository clinics, AppointmentRepository appointments,
			DoctorRepository doctors, FollowUpRepository followUps, ConversationRepository conversations) {
		this.patients = patients;
		this.clinics = clinics;
		this.appointments = appointments;
		this.doctors = doctors;
		this.followUps = followUps;
		this.conversations = conversations;
	}

	public Patient get(Long id) {
		return patients.findByIdAndClinicId(id, CurrentUser.clinicId())
				.orElseThrow(() -> ApiException.notFound("Patient"));
	}

	/**
	 * Used by WhatsApp inbound and walk-ins: find by phone, or create with the next
	 * patient number.
	 */
	@Transactional
	public Patient findOrCreate(Long clinicId, String rawPhone, String name, boolean optIn) {
		String phone = Phone.normalize(rawPhone);
		if (phone == null)
			throw ApiException.badRequest("Phone number is required");
		Optional<Patient> existing = patients.findByClinicIdAndPhone(clinicId, phone);
		if (existing.isPresent()) {
			Patient p = existing.get();
			if (optIn && !p.isWhatsappOptIn()) {
				p.setWhatsappOptIn(true);
				p.setOptInAt(LocalDateTime.now());
			}
			if ((p.getName() == null || p.getName().isBlank() || p.getName().equals(Phone.pretty(phone)))
					&& name != null && !name.isBlank()) {
				p.setName(name.trim());
			}
			return p;
		}
		Clinic clinic = clinics.findByIdForUpdate(clinicId).orElseThrow(() -> ApiException.notFound("Clinic"));
		Patient p = new Patient();
		p.setClinicId(clinicId);
		p.setPhone(phone);
		p.setName(name == null || name.isBlank() ? Phone.pretty(phone) : name.trim());
		p.setPatientNo(clinic.getNextPatientNo());
		clinic.setNextPatientNo(clinic.getNextPatientNo() + 1);
		if (optIn) {
			p.setWhatsappOptIn(true);
			p.setOptInAt(LocalDateTime.now());
		}
		return patients.save(p);
	}

	@Transactional
	public PatientProfile create(CreatePatientRequest req) {
		Long clinicId = CurrentUser.clinicId();
		String phone = Phone.normalize(req.phone());
		if (patients.findByClinicIdAndPhone(clinicId, phone).isPresent()) {
			throw ApiException.conflict("A patient with this phone number already exists");
		}
		Patient p = findOrCreate(clinicId, phone, req.name(), Boolean.TRUE.equals(req.whatsappOptIn()));
		p.setAge(req.age());
		p.setGender(req.gender());
		p.setPreferredLanguage(req.preferredLanguage());
		p.setPreferredDoctorId(req.preferredDoctorId());
		p.setEmergencyContactName(req.emergencyContactName());
		p.setEmergencyContactPhone(Phone.normalize(req.emergencyContactPhone()));
		p.setNotes(req.notes());
		return profile(p.getId());
	}

	@Transactional
	public PatientProfile update(Long id, UpdatePatientRequest req) {
		Patient p = get(id);
		if (req.name() != null)
			p.setName(req.name().trim());
		if (req.phone() != null) {
			String phone = Phone.normalize(req.phone());
			patients.findByClinicIdAndPhone(p.getClinicId(), phone).filter(other -> !other.getId().equals(p.getId()))
					.ifPresent(other -> {
						throw ApiException.conflict("Another patient has this phone number");
					});
			p.setPhone(phone);
		}
		if (req.age() != null)
			p.setAge(req.age());
		if (req.gender() != null)
			p.setGender(req.gender());
		if (req.preferredLanguage() != null)
			p.setPreferredLanguage(req.preferredLanguage());
		if (req.preferredDoctorId() != null)
			p.setPreferredDoctorId(req.preferredDoctorId());
		if (req.emergencyContactName() != null)
			p.setEmergencyContactName(req.emergencyContactName());
		if (req.emergencyContactPhone() != null)
			p.setEmergencyContactPhone(Phone.normalize(req.emergencyContactPhone()));
		if (req.notes() != null)
			p.setNotes(req.notes());
		if (req.whatsappOptIn() != null) {
			p.setWhatsappOptIn(req.whatsappOptIn());
			if (req.whatsappOptIn() && p.getOptInAt() == null)
				p.setOptInAt(LocalDateTime.now());
			if (req.whatsappOptIn())
				p.setOptedOut(false);
		}
		return profile(p.getId());
	}

	@Transactional(readOnly = true)
	public PageResult<PatientRow> list(String q, int page, int size) {
		Long clinicId = CurrentUser.clinicId();
		String query = q == null ? "" : q.trim();
		String digits = query.replaceAll("[^0-9]", "");
		if (!digits.isEmpty() && digits.length() == query.replaceAll("[\\s+\\-]", "").length()) {
			query = digits; // phone search: ignore spaces and +
		}
		Page<Patient> result = patients.search(clinicId, query,
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("name")));
		List<Long> ids = result.getContent().stream().map(Patient::getId).toList();

		Map<Long, List<Appointment>> apptsByPatient = ids.isEmpty() ? Map.of()
				: appointments.findForPatients(clinicId, ids).stream()
						.collect(Collectors.groupingBy(Appointment::getPatientId));
		Map<Long, List<FollowUp>> fuByPatient = ids.isEmpty() ? Map.of()
				: followUps.findOpenForPatients(clinicId, ids).stream()
						.collect(Collectors.groupingBy(FollowUp::getPatientId));

		LocalDateTime now = LocalDateTime.now();
		List<PatientRow> rows = result.getContent().stream().map(p -> {
			List<Appointment> list = apptsByPatient.getOrDefault(p.getId(), List.of());
			LocalDateTime last = list.stream().filter(a -> a.getStatus() == AppointmentStatus.SEEN)
					.map(Appointment::getStartAt).max(Comparator.naturalOrder()).orElse(null);
			LocalDateTime next = list.stream()
					.filter(a -> a.getStatus() == AppointmentStatus.CONFIRMED && a.getStartAt().isAfter(now))
					.map(Appointment::getStartAt).min(Comparator.naturalOrder()).orElse(null);
			int visits = (int) list.stream().filter(a -> a.getStatus() != AppointmentStatus.NO_SHOW).count();
			String due = fuByPatient.getOrDefault(p.getId(), List.of()).stream().findFirst().map(FollowUp::getTitle)
					.orElse(null);
			return new PatientRow(p.getId(), p.getName(), p.initials(), Phone.pretty(p.getPhone()), p.getPatientNo(),
					p.getAge(), p.getGender(), p.getPreferredLanguage(), last, next, visits, due);
		}).toList();
		return new PageResult<>(rows, result.getNumber(), result.getSize(), result.getTotalElements());
	}

	@Transactional(readOnly = true)
	public PatientProfile profile(Long id) {
		Patient p = get(id);
		Long clinicId = p.getClinicId();
		List<Appointment> all = appointments.findByClinicIdAndPatientIdOrderByStartAtDesc(clinicId, p.getId());
		Map<Long, Doctor> docs = doctors
				.findByIdIn(all.stream().map(Appointment::getDoctorId).collect(Collectors.toSet())).stream()
				.collect(Collectors.toMap(Doctor::getId, Function.identity()));
		if (p.getPreferredDoctorId() != null && !docs.containsKey(p.getPreferredDoctorId())) {
			doctors.findById(p.getPreferredDoctorId()).ifPresent(d -> docs.put(d.getId(), d));
		}

		LocalDateTime now = LocalDateTime.now();
		Appointment next = all.stream()
				.filter(a -> (a.getStatus() == AppointmentStatus.CONFIRMED || a.getStatus() == AppointmentStatus.WAITING
						|| a.getStatus() == AppointmentStatus.IN_CONSULT) && a.getEndAt().isAfter(now))
				.min(Comparator.comparing(Appointment::getStartAt)).orElse(null);

		List<Appointment> counted = all.stream()
				.filter(a -> a.getStatus() != AppointmentStatus.CANCELLED && a.getStatus() != AppointmentStatus.NO_SHOW)
				.toList();
		int noShows = (int) all.stream().filter(a -> a.getStatus() == AppointmentStatus.NO_SHOW).count();
		int bySiletry = (int) counted.stream().filter(a -> a.getBookedBy() == BookedBy.SILETRY).count();
		// First contact: whichever is earlier, the record being created or their first
		// visit
		LocalDate since = all.stream().map(a -> a.getStartAt().toLocalDate())
				.filter(d -> d.isBefore(p.getCreatedAt().toLocalDate())).min(Comparator.naturalOrder())
				.orElse(p.getCreatedAt().toLocalDate());

		List<FollowUp> fus = followUps.findByClinicIdAndPatientIdOrderByDueDateAsc(clinicId, p.getId());
		List<FollowUpRow> fuRows = fus.stream()
				.filter(f -> f.getStatus() != FollowUp.Status.CANCELLED && f.getStatus() != FollowUp.Status.DONE)
				.map(f -> new FollowUpRow(f.getId(), f.getTitle(), f.getDueDate(), f.getStatus().name(),
						dueLabel(f.getDueDate())))
				.toList();
		String dueFollowUp = fus.stream().filter(FollowUp::isOpen)
				.filter(f -> !f.getDueDate().isAfter(LocalDate.now().plusDays(7))).findFirst().map(FollowUp::getTitle)
				.orElse(null);

		Long convId = conversations.findByClinicIdAndPatientId(clinicId, p.getId()).map(c -> c.getId()).orElse(null);

		List<VisitRow> visits = all.stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED)
				.map(a -> new VisitRow(a.getId(), a.getStartAt(), a.getReason(), a.getDoctorId(),
						name(docs.get(a.getDoctorId()), true), a.getBookedBy(), a.getStatus()))
				.toList();

		NextAppointment nextView = next == null ? null
				: new NextAppointment(next.getId(), next.getStartAt(), next.getDoctorId(),
						name(docs.get(next.getDoctorId()), false), next.getReason(), next.getStatus(),
						next.getBookedBy(), next.getCreatedAt());

		return new PatientProfile(p.getId(), p.getName(), p.initials(), p.getAge(), p.getGender(),
				Phone.pretty(p.getPhone()), p.getPatientNo(), p.getPreferredLanguage(), dueFollowUp, convId, nextView,
				new Stats(counted.size(), noShows, bySiletry, since),
				new Details(p.isWhatsappOptIn(), p.getOptInAt(), p.isOptedOut(), p.getPreferredLanguage(),
						p.getPreferredDoctorId(), name(docs.get(p.getPreferredDoctorId()), false), usualTime(counted),
						p.getEmergencyContactName(), Phone.pretty(p.getEmergencyContactPhone()), p.getNotes()),
				fuRows, visits);
	}

	private String name(Doctor d, boolean shortForm) {
		if (d == null)
			return null;
		return shortForm ? d.shortName() : d.displayName();
	}

	/** Mornings / Afternoons / Evenings, from the times they usually come. */
	private String usualTime(List<Appointment> list) {
		if (list.isEmpty())
			return null;
		Map<String, Long> counts = list.stream().collect(Collectors.groupingBy(a -> {
			int h = a.getStartAt().getHour();
			return h < 12 ? "Mornings" : h < 16 ? "Afternoons" : "Evenings";
		}, Collectors.counting()));
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	static String dueLabel(LocalDate due) {
		LocalDate today = LocalDate.now();
		long days = ChronoUnit.DAYS.between(today, due);
		if (days < 0)
			return "Overdue";
		if (days == 0)
			return "Due today";
		if (days <= 7)
			return "Due this week";
		return null;
	}
}
