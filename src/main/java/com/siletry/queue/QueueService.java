package com.siletry.queue;

import com.siletry.appointment.*;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.messaging.MessageTexts;
import com.siletry.messaging.NotificationService;
import com.siletry.messaging.OutboundMessageService;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import com.siletry.queue.QueueDtos.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The lobby queue. Receptionist calls a name and taps "Call next"; everything
 * else follows: previous patient marked seen, next patient told to come in, the
 * one after told to be ready.
 */
@Service
public class QueueService {

	private static final Set<AppointmentStatus> IN_QUEUE = EnumSet.of(AppointmentStatus.WAITING,
			AppointmentStatus.IN_CONSULT);

	private final AppointmentRepository appointments;
	private final DoctorRepository doctors;
	private final PatientRepository patients;
	private final ClinicRepository clinics;
	private final NotificationService notify;
	private final OutboundMessageService outbound;

	public QueueService(AppointmentRepository appointments, DoctorRepository doctors, PatientRepository patients,
			ClinicRepository clinics, NotificationService notify, OutboundMessageService outbound) {
		this.appointments = appointments;
		this.doctors = doctors;
		this.patients = patients;
		this.clinics = clinics;
		this.notify = notify;
		this.outbound = outbound;
	}

	private static LocalDateTime dayStart() {
		return LocalDate.now().atStartOfDay();
	}

	private static LocalDateTime dayEnd() {
		return dayStart().plusDays(1);
	}

	/** Patient with a booking arrives. */
	@Transactional(noRollbackFor = ApiException.class)
	public Appointment checkIn(Long clinicId, Long appointmentId) {
		Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId)
				.orElseThrow(() -> ApiException.notFound("Appointment"));
		if (a.getStatus() == AppointmentStatus.WAITING || a.getStatus() == AppointmentStatus.IN_CONSULT)
			return a;
		if (a.getStatus() != AppointmentStatus.CONFIRMED)
			throw ApiException.badRequest("This appointment can't be checked in");
		if (!a.getStartAt().toLocalDate().equals(LocalDate.now()))
			throw ApiException.badRequest("This appointment isn't today");
		enqueue(a);
		notifyCheckedIn(a);
		return a;
	}

	/** Patient without a booking. Doesn't take a slot. */
	@Transactional(noRollbackFor = ApiException.class)
	public Appointment walkIn(Long clinicId, Long patientId, Long doctorId, String reason, boolean emergency,
			BookedBy bookedBy, String source, Long userId) {
		Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
		patients.findByIdAndClinicId(patientId, clinicId).orElseThrow(() -> ApiException.notFound("Patient"));
		LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
		Appointment a = new Appointment();
		a.setClinicId(clinicId);
		a.setPatientId(patientId);
		a.setDoctorId(d.getId());
		a.setStartAt(now);
		a.setEndAt(now.plusMinutes(d.getSlotMinutes()));
		a.setDurationMinutes(d.getSlotMinutes());
		a.setReason(reason == null || reason.isBlank() ? "Walk-in" : reason.trim());
		a.setBookedBy(bookedBy);
		a.setSource(source);
		a.setEmergency(emergency);
		a.setFeeAtBooking(d.getFee());
		a.setRemindEveningBefore(false);
		a.setCreatedByUserId(userId);
		a.setStatus(AppointmentStatus.CONFIRMED);
		appointments.save(a);
		enqueue(a);
		notifyCheckedIn(a);
		return a;
	}

	private void enqueue(Appointment a) {
		clinics.findByIdForUpdate(a.getClinicId()).orElseThrow(); // serialize token numbers per clinic
		Integer max = appointments.maxTokenForDay(a.getClinicId(), dayStart(), dayEnd());
		LocalDateTime now = LocalDateTime.now();
		a.setTokenNumber(max == null ? 1 : max + 1);
		a.setQueueOrder(appointments.maxQueueOrder(a.getDoctorId(), dayStart(), dayEnd()) + 1);
		a.setCheckedInAt(now);
		a.setStatus(AppointmentStatus.WAITING);
		appointments.flush();
	}

	private void notifyCheckedIn(Appointment a) {
		QueueView q = view(a.getClinicId(), a.getDoctorId());
		q.waiting().stream().filter(e -> e.appointmentId().equals(a.getId())).findFirst()
				.ifPresent(e -> notify.checkedIn(a, e.position() - 1, e.etaMinutes()));
	}

	@Transactional
	public QueueView callNext(Long clinicId, Long doctorId) {
		Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
		LocalDateTime now = LocalDateTime.now();
		List<Appointment> q = appointments.findQueue(d.getId(), dayStart(), dayEnd(), IN_QUEUE);

		q.stream().filter(a -> a.getStatus() == AppointmentStatus.IN_CONSULT).forEach(a -> {
			a.setStatus(AppointmentStatus.SEEN);
			a.setCompletedAt(now);
		});
		List<Appointment> waiting = q.stream().filter(a -> a.getStatus() == AppointmentStatus.WAITING).toList();
		if (!waiting.isEmpty()) {
			Appointment next = waiting.get(0);
			next.setStatus(AppointmentStatus.IN_CONSULT);
			next.setCalledAt(now);
			notify.comeIn(next);
			if (waiting.size() > 1)
				notify.almostTurn(waiting.get(1));
		}
		appointments.flush();
		return view(clinicId, d.getId());
	}

	/**
	 * Desk called a patient but they didn't come in. First miss: they go back in
	 * line right after whoever is called next, and get a WhatsApp nudge. Second
	 * miss: marked as no-show. Either way the next waiting patient is called in, so
	 * the doctor isn't left idle.
	 */
	@Transactional
	public QueueView notHere(Long clinicId, Long appointmentId) {
		Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId)
				.orElseThrow(() -> ApiException.notFound("Appointment"));
		if (a.getStatus() != AppointmentStatus.IN_CONSULT)
			throw ApiException.badRequest("This patient hasn't been called in");
		int misses = (a.getMissedCalls() == null ? 0 : a.getMissedCalls()) + 1;
		a.setMissedCalls(misses);
		a.setCalledAt(null);
		boolean noShow = misses >= 2;
		if (noShow) {
			a.setStatus(AppointmentStatus.NO_SHOW);
			a.setSlotKey(null);
		} else {
			a.setStatus(AppointmentStatus.WAITING);
		}
		appointments.flush();

		List<Appointment> waiting = appointments
				.findQueue(a.getDoctorId(), dayStart(), dayEnd(), EnumSet.of(AppointmentStatus.WAITING)).stream()
				.filter(w -> !w.getId().equals(a.getId())).toList();
		Appointment next = waiting.isEmpty() ? null : waiting.get(0);
		if (next != null) {
			next.setStatus(AppointmentStatus.IN_CONSULT);
			next.setCalledAt(LocalDateTime.now());
			notify.comeIn(next);
		}
		if (noShow) {
			if (waiting.size() > 1)
				notify.almostTurn(waiting.get(1));
		} else {
			// Front of the remaining line, i.e. straight after the patient now going in
			a.setQueueOrder(appointments.minQueueOrder(a.getDoctorId(), dayStart(), dayEnd()) - 1);
			notify.missedCall(a, next != null);
		}
		appointments.flush();
		return view(clinicId, a.getDoctorId());
	}

	/** Doctor finished with the current patient but nobody else is waiting. */
	@Transactional
	public QueueView complete(Long clinicId, Long appointmentId) {
		Appointment a = appointments.findByIdAndClinicId(appointmentId, clinicId)
				.orElseThrow(() -> ApiException.notFound("Appointment"));
		if (a.getStatus() == AppointmentStatus.IN_CONSULT || a.getStatus() == AppointmentStatus.WAITING) {
			if (a.getCalledAt() == null)
				a.setCalledAt(LocalDateTime.now());
			a.setStatus(AppointmentStatus.SEEN);
			a.setCompletedAt(LocalDateTime.now());
		}
		return view(clinicId, a.getDoctorId());
	}

	/**
	 * Patient stepped out: move to the end of the line (keeps their token number).
	 */
	@Transactional
	public QueueView skip(Long clinicId, Long appointmentId) {
		Appointment a = waitingAppt(clinicId, appointmentId);
		a.setQueueOrder(appointments.maxQueueOrder(a.getDoctorId(), dayStart(), dayEnd()) + 1);
		return view(clinicId, a.getDoctorId());
	}

	@Transactional
	public QueueView moveToFront(Long clinicId, Long appointmentId) {
		Appointment a = waitingAppt(clinicId, appointmentId);
		a.setQueueOrder(appointments.minQueueOrder(a.getDoctorId(), dayStart(), dayEnd()) - 1);
		return view(clinicId, a.getDoctorId());
	}

	private Appointment waitingAppt(Long clinicId, Long id) {
		Appointment a = appointments.findByIdAndClinicId(id, clinicId)
				.orElseThrow(() -> ApiException.notFound("Appointment"));
		if (a.getStatus() != AppointmentStatus.WAITING)
			throw ApiException.badRequest("This patient isn't waiting");
		return a;
	}

	/** Messages everyone still due to see this doctor today. */
	@Transactional
	public DelayResult announceDelay(Long clinicId, Long doctorId, int minutes, String note) {
		Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
		LocalDateTime now = LocalDateTime.now();
		List<Appointment> targets = appointments.findForDoctorInRange(d.getId(), dayStart(), dayEnd()).stream()
				.filter(a -> a.getStatus() == AppointmentStatus.WAITING
						|| (a.getStatus() == AppointmentStatus.CONFIRMED && a.getEndAt().isAfter(now)))
				.toList();
		targets.forEach(a -> notify.delay(a, minutes, note));
		return new DelayResult(targets.size());
	}

	@Transactional(readOnly = true)
	public List<QueueView> all(Long clinicId) {
		return doctors.findByClinicIdAndActiveTrueOrderByNameAsc(clinicId).stream().map(d -> view(clinicId, d.getId()))
				.toList();
	}

	@Transactional(readOnly = true)
	public QueueView view(Long clinicId, Long doctorId) {
		Doctor d = doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> ApiException.notFound("Doctor"));
		List<Appointment> today = appointments.findQueue(d.getId(), dayStart(), dayEnd(),
				EnumSet.of(AppointmentStatus.WAITING, AppointmentStatus.IN_CONSULT, AppointmentStatus.SEEN));
		Map<Long, Patient> pmap = patients.findAllById(today.stream().map(Appointment::getPatientId).toList()).stream()
				.collect(Collectors.toMap(Patient::getId, Function.identity()));

		int avg = avgConsultMinutes(d);
		LocalDateTime now = LocalDateTime.now();
		Appointment current = today.stream().filter(a -> a.getStatus() == AppointmentStatus.IN_CONSULT)
				.max(Comparator.comparing(Appointment::getCalledAt)).orElse(null);
		long seen = today.stream().filter(a -> a.getStatus() == AppointmentStatus.SEEN).count();

		int remainingCurrent = 0;
		if (current != null) {
			long elapsed = Duration.between(current.getCalledAt(), now).toMinutes();
			remainingCurrent = (int) Math.max(1, avg - elapsed);
		}
		List<Appointment> waiting = today.stream().filter(a -> a.getStatus() == AppointmentStatus.WAITING).toList();
		List<QueueEntry> entries = new ArrayList<>();
		for (int i = 0; i < waiting.size(); i++) {
			Appointment a = waiting.get(i);
			entries.add(entry(a, pmap.get(a.getPatientId()), i + 1, remainingCurrent + i * avg, now));
		}

		LocalDateTime lastCalled = today.stream().map(Appointment::getCalledAt).filter(Objects::nonNull)
				.max(Comparator.naturalOrder()).orElse(null);
		boolean stale = current != null && !waiting.isEmpty()
				&& Duration.between(current.getCalledAt(), now).toMinutes() > 2L * avg;

		return new QueueView(d.getId(), d.displayName(),
				current == null ? null : entry(current, pmap.get(current.getPatientId()), 0, 0, now), entries, avg,
				stale, lastCalled, seen);
	}

	private QueueEntry entry(Appointment a, Patient p, int position, int eta, LocalDateTime now) {
		return new QueueEntry(a.getId(), a.getTokenNumber(), a.getPatientId(), p == null ? null : p.getName(),
				p == null ? null : Phone.pretty(p.getPhone()), a.getReason(), a.getStatus(), a.isEmergency(),
				a.getBookedBy(), a.getBookedBy() == BookedBy.WALK_IN ? null : a.getStartAt(), a.getCheckedInAt(),
				position, eta, a.getCheckedInAt() == null ? 0 : Duration.between(a.getCheckedInAt(), now).toMinutes());
	}

	/** Real average from "Call next" timestamps; falls back to the slot length. */
	public int avgConsultMinutes(Doctor d) {
		List<Appointment> recent = appointments.findRecentCompleted(d.getId(), PageRequest.of(0, 30));
		OptionalDouble avg = recent.stream()
				.mapToLong(a -> Duration.between(a.getCalledAt(), a.getCompletedAt()).toMinutes())
				.filter(m -> m >= 1 && m <= 90).average();
		if (avg.isEmpty() || recent.size() < 5)
			return d.getSlotMinutes();
		return (int) Math.max(3, Math.min(60, Math.round(avg.getAsDouble())));
	}

	/**
	 * Nudge the doctor/desk when nobody has tapped "Call next" for too long. Called
	 * by the scheduler.
	 */
	@Transactional
	public void nudgeStaleQueues() {
		LocalDateTime now = LocalDateTime.now();
		for (Appointment a : appointments.findAllInConsultSince(dayStart())) {
			if (a.getStaleNudgedAt() != null && a.getStaleNudgedAt().isAfter(now.minusMinutes(20)))
				continue;
			Doctor d = doctors.findById(a.getDoctorId()).orElse(null);
			if (d == null || d.getAlertMobile() == null)
				continue;
			int avg = avgConsultMinutes(d);
			if (Duration.between(a.getCalledAt(), now).toMinutes() <= 2L * avg)
				continue;
			boolean othersWaiting = appointments
					.findQueue(d.getId(), dayStart(), dayEnd(), EnumSet.of(AppointmentStatus.WAITING)).size() > 0;
			if (!othersWaiting)
				continue;
			String clinicName = clinics.findById(a.getClinicId()).map(x -> x.getName()).orElse("your clinic");
			outbound.alertStaff(d.getAlertMobile(), clinicName,
					"Still with token #" + a.getTokenNumber()
							+ "? Patients are waiting. Tap Call next when you're ready for the next one.",
					List.of(MessageTexts.BTN_CALL_NEXT));
			a.setStaleNudgedAt(now);
		}
	}
}