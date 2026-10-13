package com.siletry.lead;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated endpoint used by the "Book a demo" form on siletry.com.
 * Always answers the same way for new, duplicate and bot submissions, so it can't be
 * used to find out whether a number has already asked for a demo.
 */
@RestController
@RequestMapping("/api/public/leads")
public class PublicLeadController {

    private final LeadService leads;

    public PublicLeadController(LeadService leads) {
        this.leads = leads;
    }

    @PostMapping
    public ResponseEntity<LeadDtos.CreateLeadResponse> create(@RequestBody LeadDtos.CreateLeadRequest body,
                                                              HttpServletRequest request) {
        leads.submit(body, ClientIp.of(request), request.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new LeadDtos.CreateLeadResponse(true));
    }
}
