package com.siletry.appointment;

import com.siletry.appointment.AppointmentDtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    /**
     * tab: today | week | noshows | cancelled
     * bookedBy: SILETRY | FRONT_DESK | WALK_IN | STAFF (front desk + walk-in)
     */
    @GetMapping
    public AppointmentList list(@RequestParam(defaultValue = "today") String tab,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestParam(required = false) Long doctorId,
                                @RequestParam(required = false) AppointmentStatus status,
                                @RequestParam(required = false) String bookedBy) {
        return service.list(tab, date, doctorId, status, bookedBy);
    }

    @PostMapping
    public AppointmentRow create(@Valid @RequestBody CreateAppointmentRequest req) {
        return service.create(req);
    }

    @GetMapping("/confirmation-preview")
    public ConfirmationPreview preview(@RequestParam(required = false) Long patientId,
                                       @RequestParam Long doctorId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt) {
        return service.preview(patientId, doctorId, startAt);
    }

    @PatchMapping("/{id}")
    public AppointmentRow update(@PathVariable Long id, @RequestBody UpdateAppointmentRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/reschedule")
    public AppointmentRow reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest req) {
        return service.reschedule(id, req);
    }

    @PostMapping("/{id}/cancel")
    public AppointmentRow cancel(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        return service.cancel(id, req);
    }

    @PostMapping("/{id}/no-show")
    public AppointmentRow noShow(@PathVariable Long id) {
        return service.noShow(id);
    }
}
