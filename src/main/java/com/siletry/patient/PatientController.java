package com.siletry.patient;

import com.siletry.patient.PatientDtos.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping("/patients")
    public PageResult<PatientRow> list(@RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/patients/{id}")
    public PatientProfile profile(@PathVariable Long id) {
        return service.profile(id);
    }

    @PostMapping("/patients")
    public PatientProfile create(@Valid @RequestBody CreatePatientRequest req) {
        return service.create(req);
    }

    @PatchMapping("/patients/{id}")
    public PatientProfile update(@PathVariable Long id, @RequestBody UpdatePatientRequest req) {
        return service.update(id, req);
    }

    /** Header search box (⌘K): patients by name, phone or ID. */
    @GetMapping("/search")
    public List<PatientRow> search(@RequestParam String q) {
        return service.list(q, 0, 8).items();
    }
}
