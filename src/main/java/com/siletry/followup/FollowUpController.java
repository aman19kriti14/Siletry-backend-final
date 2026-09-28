package com.siletry.followup;

import com.siletry.followup.FollowUpService.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/follow-ups")
public class FollowUpController {

    private final FollowUpService service;

    public FollowUpController(FollowUpService service) {
        this.service = service;
    }

    @GetMapping
    public List<FollowUpView> list(@RequestParam(required = false) Long patientId,
                                   @RequestParam(defaultValue = "7") int dueWithinDays) {
        return patientId != null ? service.forPatient(patientId) : service.dueSoon(dueWithinDays);
    }

    @PostMapping
    public FollowUpView create(@Valid @RequestBody CreateFollowUp req) {
        return service.create(req);
    }

    @PatchMapping("/{id}")
    public FollowUpView update(@PathVariable Long id, @RequestBody UpdateFollowUp req) {
        return service.update(id, req);
    }
}
