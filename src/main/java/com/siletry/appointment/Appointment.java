package com.siletry.appointment;

import com.siletry.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointment", indexes = { @Index(name = "idx_appt_clinic_start", columnList = "clinic_id,start_at"),
		@Index(name = "idx_appt_doctor_start", columnList = "doctor_id,start_at"),
		@Index(name = "idx_appt_patient", columnList = "patient_id"),
		@Index(name = "idx_appt_clinic_created", columnList = "clinic_id,created_at") })
@Getter
@Setter
public class Appointment extends BaseEntity {

	@Column(name = "patient_id", nullable = false)
	private Long patientId;

	@Column(name = "doctor_id", nullable = false)
	private Long doctorId;

	@Column(name = "start_at", nullable = false)
	private LocalDateTime startAt;

	@Column(name = "end_at", nullable = false)
	private LocalDateTime endAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	/** Free text, in the patient's or staff's own words. */
	@Column(length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "booked_by", nullable = false)
	private BookedBy bookedBy;

	/** Where it came from: QR code (BK-POSTER), "whatsapp", "web". */
	private String source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AppointmentStatus status = AppointmentStatus.CONFIRMED;

	@Column(nullable = false)
	private boolean emergency = false;

	@Column(name = "front_desk_note", length = 500)
	private String frontDeskNote;

	/**
	 * Fee captured when booked, so "Revenue booked" doesn't change when fees
	 * change.
	 */
	@Column(name = "fee_at_booking", nullable = false)
	private int feeAtBooking;

	/**
	 * doctorId|startAt while the appointment holds a slot, null otherwise. The
	 * unique constraint makes double-booking impossible at the database level.
	 * Postgres allows many NULLs, so cancelled/no-show rows free the slot.
	 */
	@Column(name = "slot_key", unique = true)
	private String slotKey;

	// ----- Queue -----
	@Column(name = "token_number")
	private Integer tokenNumber;

	/**
	 * Sort order in the waiting list. Skip / move-to-front change this, not the
	 * token.
	 */
	@Column(name = "queue_order")
	private Long queueOrder;

	@Column(name = "checked_in_at")
	private LocalDateTime checkedInAt;

	@Column(name = "called_at")
	private LocalDateTime calledAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "stale_nudged_at")
	private LocalDateTime staleNudgedAt;

	/**
	 * Times the desk tapped "Not here" after calling this patient. Nullable so the
	 * column adds cleanly to old rows.
	 */
	@Column(name = "missed_calls")
	private Integer missedCalls;

	// ----- Cancellation -----
	@Column(name = "cancelled_at")
	private LocalDateTime cancelledAt;

	@Column(name = "cancel_reason")
	private String cancelReason;

	// ----- Reminders -----
	@Column(name = "remind_evening_before", nullable = false)
	private boolean remindEveningBefore = true;

	@Column(name = "reminder_sent_at")
	private LocalDateTime reminderSentAt;

	/** YES / CHANGE / CANCEL from the reminder reply. */
	@Column(name = "reminder_response")
	private String reminderResponse;

	@Column(name = "created_by_user_id")
	private Long createdByUserId;

	public static String slotKeyFor(Long doctorId, LocalDateTime start) {
		return doctorId + "|" + start.withSecond(0).withNano(0);
	}
}