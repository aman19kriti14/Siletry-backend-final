package com.siletry.queue;

import com.siletry.appointment.AppointmentDtos.AppointmentRow;
import com.siletry.appointment.AppointmentService;
import com.siletry.appointment.BookedBy;
import com.siletry.auth.CurrentUser;
import com.siletry.common.ApiException;
import com.siletry.patient.PatientService;
import com.siletry.queue.QueueDtos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Receptionist's queue actions. Lives on the Appointments → Today tab in the
 * UI.
 */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

	private final QueueService queue;
	private final PatientService patients;
	private final AppointmentService appointments;

	public QueueController(QueueService queue, PatientService patients, AppointmentService appointments) {
		this.queue = queue;
		this.patients = patients;
		this.appointments = appointments;
	}

	@GetMapping
	public List<QueueView> all() {
		return queue.all(CurrentUser.clinicId());
	}

	@GetMapping("/doctors/{doctorId}")
	public QueueView one(@PathVariable Long doctorId) {
		return queue.view(CurrentUser.clinicId(), doctorId);
	}

	@PostMapping("/doctors/{doctorId}/call-next")
	public QueueView callNext(@PathVariable Long doctorId) {
		return queue.callNext(CurrentUser.clinicId(), doctorId);
	}

	@PostMapping("/doctors/{doctorId}/announce-delay")
	public DelayResult delay(@PathVariable Long doctorId, @Valid @RequestBody DelayRequest req) {
		return queue.announceDelay(CurrentUser.clinicId(), doctorId, req.minutes(), req.note());
	}

	@PostMapping("/check-in/{appointmentId}")
	public AppointmentRow checkIn(@PathVariable Long appointmentId) {
		return appointments.row(queue.checkIn(CurrentUser.clinicId(), appointmentId));
	}

	@PostMapping("/walk-in")
	public AppointmentRow walkIn(@Valid @RequestBody WalkInRequest req) {
		Long clinicId = CurrentUser.clinicId();
		Long patientId = req.patientId();
		if (patientId == null) {
			if (req.phone() == null || req.phone().isBlank())
				throw ApiException.badRequest("Phone number is required for a new patient");
			patientId = patients.findOrCreate(clinicId, req.phone(), req.name(), true).getId();
		}
		return appointments.row(queue.walkIn(clinicId, patientId, req.doctorId(), req.reason(),
				Boolean.TRUE.equals(req.emergency()), BookedBy.WALK_IN, "front-desk", CurrentUser.userId()));
	}

	@PostMapping("/appointments/{id}/done")
	public QueueView done(@PathVariable Long id) {
		return queue.complete(CurrentUser.clinicId(), id);
	}

	@PostMapping("/appointments/{id}/not-here")
	public QueueView notHere(@PathVariable Long id) {
		return queue.notHere(CurrentUser.clinicId(), id);
	}

	@PostMapping("/appointments/{id}/skip")
	public QueueView skip(@PathVariable Long id) {
		return queue.skip(CurrentUser.clinicId(), id);
	}

	@PostMapping("/appointments/{id}/move-to-front")
	public QueueView front(@PathVariable Long id) {
		return queue.moveToFront(CurrentUser.clinicId(), id);
	}
}