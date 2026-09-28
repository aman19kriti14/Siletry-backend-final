package com.siletry.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siletry.auth.AuthDtos.*;
import com.siletry.auth.OtpService.OtpTicket;
import com.siletry.clinic.Clinic;
import com.siletry.clinic.ClinicRepository;
import com.siletry.clinic.ClinicSetupService;
import com.siletry.common.ApiException;
import com.siletry.common.Phone;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final ClinicRepository clinics;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ClinicSetupService clinicSetup;
    private final OtpService otp;
    private final ObjectMapper mapper;

    public AuthService(AppUserRepository users, ClinicRepository clinics, PasswordEncoder encoder,
                       JwtService jwt, ClinicSetupService clinicSetup, OtpService otp, ObjectMapper mapper) {
        this.users = users;
        this.clinics = clinics;
        this.encoder = encoder;
        this.jwt = jwt;
        this.clinicSetup = clinicSetup;
        this.otp = otp;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // Password signup (API) and login
    // ------------------------------------------------------------------

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("An account with this email already exists");
        }
        String wa = Phone.normalize(req.whatsappNumber());
        if (wa != null && clinics.findByWhatsappNumber(wa).isPresent()) {
            throw ApiException.conflict("This WhatsApp number is already linked to another clinic");
        }
        Clinic clinic = newClinic(req.clinicName(), null);
        clinic.setArea(req.area());
        clinic.setCity(req.city());
        clinic.setPhone(Phone.normalize(req.phone()));
        clinic.setWhatsappNumber(wa);

        AppUser owner = newOwner(clinic, req.ownerName(), req.email(), req.phone(), req.password());
        return response(owner, clinic, true);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        AppUser user = findByIdentifier(req.id())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Wrong email, mobile or password"));
        Clinic clinic = clinics.findById(user.getClinicId()).orElseThrow(() -> ApiException.notFound("Clinic"));
        return response(user, clinic, !Boolean.FALSE.equals(req.remember()));
    }

    // ------------------------------------------------------------------
    // WhatsApp code login
    // ------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    public OtpTicket requestLoginCode(OtpLoginRequest req) {
        AppUser user = findByIdentifier(req.identifier())
                .orElseThrow(() -> ApiException.notFound("No account with this email or mobile. Check it, or create an account"));
        if (user.getPhone() == null) {
            throw ApiException.badRequest("There's no mobile number on this account. Sign in with your password.");
        }
        return otp.send(user.getPhone(), OtpCode.Purpose.LOGIN, String.valueOf(user.getId()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyLoginCode(OtpVerifyRequest req) {
        OtpCode o = otp.verify(req.ticket(), req.code(), OtpCode.Purpose.LOGIN);
        AppUser user = users.findById(Long.valueOf(o.getPayload())).filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "This account is no longer active"));
        Clinic clinic = clinics.findById(user.getClinicId()).orElseThrow(() -> ApiException.notFound("Clinic"));
        return response(user, clinic, !Boolean.FALSE.equals(req.remember()));
    }

    public OtpTicket resend(ResendRequest req) {
        return otp.resend(req.ticket());
    }

    // ------------------------------------------------------------------
    // Signup with a WhatsApp code (the web app's flow)
    // ------------------------------------------------------------------

    @Transactional(noRollbackFor = ApiException.class)
    public OtpTicket startSignup(SignupStartRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw ApiException.conflict("An account with this email already exists. Sign in instead.");
        }
        String phone = Phone.normalize(req.phone());
        if (phone != null && !users.findByPhoneAndActiveTrue(phone).isEmpty()) {
            throw ApiException.conflict("This mobile number already has an account. Sign in instead.");
        }
        try {
            return otp.send(phone, OtpCode.Purpose.SIGNUP, mapper.writeValueAsString(req));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifySignup(OtpVerifyRequest req) {
        OtpCode o = otp.verify(req.ticket(), req.code(), OtpCode.Purpose.SIGNUP);
        SignupStartRequest form;
        try {
            form = mapper.readValue(o.getPayload(), SignupStartRequest.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Start signup again");
        }
        if (users.existsByEmailIgnoreCase(form.email().trim())) {
            throw ApiException.conflict("An account with this email already exists. Sign in instead.");
        }
        Clinic clinic = newClinic(form.clinicName(), form.doctorCount());
        // No password yet: they sign in with WhatsApp codes, or set one in Settings
        AppUser owner = newOwner(clinic, form.name(), form.email(), o.getPhone(), null);
        return response(owner, clinic, true);
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AuthResponse me() {
        AppUser user = users.findById(CurrentUser.userId()).orElseThrow(() -> ApiException.notFound("User"));
        Clinic clinic = clinics.findById(user.getClinicId()).orElseThrow(() -> ApiException.notFound("Clinic"));
        return new AuthResponse(null, UserView.of(user), summary(clinic));
    }

    @Transactional
    public void setPassword(SetPasswordRequest req) {
        AppUser user = users.findById(CurrentUser.userId()).orElseThrow(() -> ApiException.notFound("User"));
        boolean hasPassword = !user.getPasswordHash().startsWith("{nopass}");
        if (hasPassword && (req.currentPassword() == null || !encoder.matches(req.currentPassword(), user.getPasswordHash()))) {
            throw ApiException.badRequest("Your current password is wrong");
        }
        user.setPasswordHash(encoder.encode(req.newPassword()));
    }

    @Transactional(readOnly = true)
    public boolean hasPassword() {
        return users.findById(CurrentUser.userId()).map(u -> !u.getPasswordHash().startsWith("{nopass}")).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<UserView> listStaff() {
        return users.findByClinicIdOrderByNameAsc(CurrentUser.clinicId()).stream().map(UserView::of).toList();
    }

    @Transactional
    public UserView createStaff(CreateStaffRequest req) {
        CurrentUser.requireOwner();
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("An account with this email already exists");
        }
        AppUser u = new AppUser();
        u.setClinicId(CurrentUser.clinicId());
        u.setName(req.name().trim());
        u.setEmail(req.email().trim().toLowerCase());
        u.setPhone(Phone.normalize(req.phone()));
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role());
        u.setDoctorId(req.doctorId());
        return UserView.of(users.save(u));
    }

    @Transactional
    public UserView setStaffActive(Long id, boolean active) {
        CurrentUser.requireOwner();
        AppUser u = users.findByIdAndClinicId(id, CurrentUser.clinicId()).orElseThrow(() -> ApiException.notFound("User"));
        if (u.getId().equals(CurrentUser.userId())) throw ApiException.badRequest("You can't deactivate yourself");
        u.setActive(active);
        return UserView.of(u);
    }

    // ------------------------------------------------------------------

    private Optional<AppUser> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String id = identifier.trim();
        Optional<AppUser> user = id.contains("@")
                ? users.findByEmailIgnoreCase(id)
                : Optional.ofNullable(Phone.normalize(id)).flatMap(p -> users.findByPhoneAndActiveTrue(p).stream().findFirst());
        return user.filter(AppUser::isActive);
    }

    private Clinic newClinic(String name, Integer doctorCount) {
        Clinic clinic = new Clinic();
        clinic.setName(name.trim());
        clinic.setCity("Bengaluru");
        clinic.setDoctorCountHint(doctorCount);
        clinic.setOnboardingStep(1);
        clinic.setReplyLanguages("en");
        clinic.setDelayAnnouncementsEnabled(true);
        clinic.setGoogleReviewsEnabled(false);
        clinic = clinics.save(clinic);
        clinicSetup.createDefaults(clinic);
        return clinic;
    }

    private AppUser newOwner(Clinic clinic, String name, String email, String phone, String password) {
        AppUser owner = new AppUser();
        owner.setClinicId(clinic.getId());
        owner.setName(name.trim());
        owner.setEmail(email.trim().toLowerCase());
        owner.setPhone(Phone.normalize(phone));
        // "{nopass}" marks accounts that only sign in with WhatsApp codes; it can never match a password
        owner.setPasswordHash(password == null ? "{nopass}" + UUID.randomUUID() : encoder.encode(password));
        owner.setRole(Role.OWNER);
        return users.save(owner);
    }

    private AuthResponse response(AppUser user, Clinic clinic, boolean remember) {
        return new AuthResponse(jwt.issue(user, remember), UserView.of(user), summary(clinic));
    }

    public static ClinicSummary summary(Clinic c) {
        return new ClinicSummary(c.getId(), c.getName(), c.getArea(), Phone.pretty(c.getWhatsappNumber()),
                c.isWhatsappConnected(), c.isBotEnabled(), c.getPlan().name(), c.isLive(), c.currentStep());
    }
}
