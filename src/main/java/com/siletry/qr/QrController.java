package com.siletry.qr;

import com.siletry.qr.QrService.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/qr")
public class QrController {

    private final QrService service;

    public QrController(QrService service) {
        this.service = service;
    }

    @GetMapping
    public List<QrView> list() {
        return service.list();
    }

    @PostMapping
    public QrView create(@Valid @RequestBody CreateQr req) {
        return service.create(req);
    }

    @PostMapping("/{id}/deactivate")
    public QrView deactivate(@PathVariable Long id) {
        return service.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    public QrView activate(@PathVariable Long id) {
        return service.setActive(id, true);
    }

    /** PNG for printing. Use ?access_token=... in an <img> tag, or fetch with the Authorization header. */
    @GetMapping(value = "/{id}/image.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable Long id, @RequestParam(defaultValue = "600") int size) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(service.png(id, Math.max(200, Math.min(size, 2000))));
    }
}
