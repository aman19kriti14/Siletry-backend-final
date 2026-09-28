package com.siletry.doctor;

import com.siletry.appointment.SlotService;
import com.siletry.doctor.DoctorDtos.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {

    private final DoctorService service;
    private final SlotService slots;

    public DoctorController(DoctorService service, SlotService slots) {
        this.service = service;
        this.slots = slots;
    }

    @GetMapping
    public List<DoctorView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public DoctorView one(@PathVariable Long id) {
        return service.one(id);
    }

    @PostMapping
    public DoctorView create(@Valid @RequestBody CreateDoctorRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public DoctorView update(@PathVariable Long id, @Valid @RequestBody UpdateDoctorRequest req) {
        return service.update(id, req);
    }

    @PutMapping("/{id}/hours")
    public HoursUpdateResult hours(@PathVariable Long id, @Valid @RequestBody HoursUpdate req) {
        return service.updateHours(id, req);
    }

    @GetMapping("/{id}/leave")
    public List<LeaveDto> leave(@PathVariable Long id) {
        return service.listLeave(id);
    }

    @PostMapping("/{id}/leave")
    public LeaveDto addLeave(@PathVariable Long id, @Valid @RequestBody LeaveDto req) {
        return service.addLeave(id, req);
    }

    @DeleteMapping("/{id}/leave/{leaveId}")
    public void deleteLeave(@PathVariable Long id, @PathVariable Long leaveId) {
        service.deleteLeave(id, leaveId);
    }

    /** Slots for one day, with FREE / HELD / BOOKED / BLOCKED state (the Book a visit grid). */
    @GetMapping("/{id}/slots")
    public List<SlotService.Slot> slots(@PathVariable Long id,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return slots.daySlots(service.get(id), date, false);
    }

    /** Free-slot counts per day (the day strip: "Tomorrow 23, 9 free"). */
    @GetMapping("/{id}/days")
    public List<SlotService.DaySummary> days(@PathVariable Long id,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(defaultValue = "7") int days) {
        return slots.daySummaries(service.get(id), from == null ? LocalDate.now() : from, Math.min(days, 31), false);
    }
}
