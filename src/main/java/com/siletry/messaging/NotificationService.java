package com.siletry.messaging;

import com.siletry.appointment.Appointment;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.common.Fmt;
import com.siletry.doctor.Doctor;
import com.siletry.doctor.DoctorRepository;
import com.siletry.messaging.OutboundMessageService.Outgoing;
import com.siletry.messaging.gateway.WhatsAppGateway.TemplateMessage;
import com.siletry.patient.Patient;
import com.siletry.patient.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Patient notifications from appointment and queue events. Each one has the
 * chat wording (inside 24 hours) and the matching approved template (outside).
 */
@Service
public class NotificationService {

	private final OutboundMessageService outbound;
	private final ClinicRepository clinics;
	private final PatientRepository patients;
	private final DoctorRepository doctors;

	public NotificationService(OutboundMessageService outbound, ClinicRepository clinics, PatientRepository patients,
			DoctorRepository doctors) {
		this.outbound = outbound;
		this.clinics = clinics;
		this.patients = patients;
		this.doctors = doctors;
	}

	private record Ctx(Clinic clinic, Patient patient, Doctor doctor) {
	}

	private Ctx ctx(Appointment a) {
		return new Ctx(clinics.findById(a.getClinicId()).orElseThrow(),
				patients.findById(a.getPatientId()).orElseThrow(), doctors.findById(a.getDoctorId()).orElseThrow());
	}

	private static TemplateMessage t(TemplateCatalog.Spec spec, String... params) {
		return TemplateMessage.of(spec.name(), params);
	}

	@Transactional
	public void bookingConfirmed(Appointment a) {
		Ctx c = ctx(a);
		outbound.send(c.clinic(), c.patient(),
				Outgoing.template(MessageTexts.booked(c.clinic(), c.doctor(), a.getStartAt()), List.of(),
						t(TemplateCatalog.BOOKING_CONFIRMED, c.doctor().displayName(), Fmt.when(a.getStartAt()),
								c.clinic().locationLine())));
	}

	@Transactional
	public void rescheduled(Appointment a) {
		Ctx c = ctx(a);
		outbound.send(c.clinic(), c.patient(),
				Outgoing.template(MessageTexts.rescheduled(c.clinic(), c.doctor(), a.getStartAt()), List.of(),
						t(TemplateCatalog.APPOINTMENT_RESCHEDULED, c.doctor().displayName(), Fmt.when(a.getStartAt()),
								c.clinic().locationLine())));
	}

	@Transactional
	public void cancelledByClinic(Appointment a, String reason) {
		Ctx c = ctx(a);
		outbound.send(c.clinic(), c.patient(),
				Outgoing.template(MessageTexts.clinicCancelled(c.doctor(), a.getStartAt(), reason), List.of(),
						t(TemplateCatalog.APPOINTMENT_CANCELLED, c.doctor().displayName(), Fmt.when(a.getStartAt()))));
	}

	@Transactional
	public void reminder(Appointment a) {
		Ctx c = ctx(a);
		outbound.send(c.clinic(), c.patient(),
				Outgoing.template(MessageTexts.reminder(c.clinic(), c.doctor(), a.getStartAt()),
						List.of(MessageTexts.BTN_YES, MessageTexts.BTN_CHANGE, MessageTexts.BTN_CANCEL),
						t(TemplateCatalog.APPOINTMENT_REMINDER, c.doctor().displayName(), Fmt.when(a.getStartAt()),
								c.clinic().locationLine())));
	}

	@Transactional
	public void checkedIn(Appointment a, int ahead, int eta) {
		Ctx c = ctx(a);
		if (!c.clinic().isQueueUpdatesEnabled())
			return;
		outbound.send(c.clinic(), c.patient(), Outgoing.template(MessageTexts.checkedIn(a.getTokenNumber(), ahead, eta),
				List.of(), t(TemplateCatalog.QUEUE_CHECKED_IN, c.clinic().getName(), "#" + a.getTokenNumber())));
	}

	@Transactional
	public void comeIn(Appointment a) {
		Ctx c = ctx(a);
		if (!c.clinic().isQueueUpdatesEnabled())
			return;
		outbound.send(c.clinic(), c.patient(), Outgoing.template(MessageTexts.comeIn(a.getTokenNumber(), c.doctor()),
				List.of(), t(TemplateCatalog.QUEUE_YOUR_TURN, "#" + a.getTokenNumber(), c.doctor().displayName())));
	}

	@Transactional
	public void almostTurn(Appointment a) {
		Ctx c = ctx(a);
		if (!c.clinic().isQueueUpdatesEnabled())
			return;
		outbound.send(c.clinic(), c.patient(), Outgoing.template(MessageTexts.almostTurn(a.getTokenNumber()), List.of(),
				t(TemplateCatalog.QUEUE_NEXT, "#" + a.getTokenNumber())));
	}

	/**
	 * Called but didn't show up. Outside the 24h window this falls back to the
	 * approved queue_next template.
	 */
	@Transactional
	public void missedCall(Appointment a, boolean someoneElseIn) {
		Ctx c = ctx(a);
		if (!c.clinic().isQueueUpdatesEnabled())
			return;
		outbound.send(c.clinic(), c.patient(),
				Outgoing.template(MessageTexts.missedCall(a.getTokenNumber(), someoneElseIn), List.of(),
						t(TemplateCatalog.QUEUE_NEXT, "#" + a.getTokenNumber())));
	}

	@Transactional
	public void delay(Appointment a, int minutes, String note) {
		Ctx c = ctx(a);
		outbound.send(c.clinic(), c.patient(), Outgoing.template(MessageTexts.delay(c.doctor(), minutes, note),
				List.of(), t(TemplateCatalog.DOCTOR_DELAY, c.doctor().displayName(), String.valueOf(minutes))));
	}
}