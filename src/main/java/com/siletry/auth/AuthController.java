package com.siletry.auth;

import com.siletry.auth.AuthDtos.*;
import com.siletry.auth.OtpService.OtpTicket;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest req) {
        return auth.signup(req);
    }

    /** Step 1 of the web signup: sends a WhatsApp code. */
    @PostMapping("/auth/signup/start")
    public OtpTicket signupStart(@Valid @RequestBody SignupStartRequest req) {
        return auth.startSignup(req);
    }

    /** Step 2: verifies the code and creates the clinic. */
    @PostMapping("/auth/signup/verify")
    public AuthResponse signupVerify(@Valid @RequestBody OtpVerifyRequest req) {
        return auth.verifySignup(req);
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/auth/otp/request")
    public OtpTicket otpRequest(@Valid @RequestBody OtpLoginRequest req) {
        return auth.requestLoginCode(req);
    }

    @PostMapping("/auth/otp/verify")
    public AuthResponse otpVerify(@Valid @RequestBody OtpVerifyRequest req) {
        return auth.verifyLoginCode(req);
    }

    @PostMapping("/auth/otp/resend")
    public OtpTicket otpResend(@Valid @RequestBody ResendRequest req) {
        return auth.resend(req);
    }

    @GetMapping("/auth/me")
    public AuthResponse me() {
        return auth.me();
    }

    @GetMapping("/auth/password")
    public Map<String, Boolean> hasPassword() {
        return Map.of("hasPassword", auth.hasPassword());
    }

    @PostMapping("/auth/password")
    public Map<String, Boolean> setPassword(@Valid @RequestBody SetPasswordRequest req) {
        auth.setPassword(req);
        return Map.of("hasPassword", true);
    }

    @GetMapping("/staff")
    public List<UserView> staff() {
        return auth.listStaff();
    }

    @PostMapping("/staff")
    public UserView createStaff(@Valid @RequestBody CreateStaffRequest req) {
        return auth.createStaff(req);
    }

    @PostMapping("/staff/{id}/deactivate")
    public UserView deactivate(@PathVariable Long id) {
        return auth.setStaffActive(id, false);
    }

    @PostMapping("/staff/{id}/activate")
    public UserView activate(@PathVariable Long id) {
        return auth.setStaffActive(id, true);
    }
}
