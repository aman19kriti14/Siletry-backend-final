package com.siletry.auth;

import jakarta.validation.constraints.*;

public final class AuthDtos {

    private AuthDtos() {}

    /** Password signup (kept for API clients). The web app signs up with a WhatsApp code instead. */
    public record SignupRequest(
            @NotBlank String clinicName,
            String area,
            String city,
            @NotBlank String ownerName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            String phone,
            String whatsappNumber) {}

    /** identifier = email or mobile. `email` is accepted for older clients. */
    public record LoginRequest(String identifier, String email, @NotBlank String password, Boolean remember) {
        public String id() {
            return identifier != null && !identifier.isBlank() ? identifier.trim() : (email == null ? "" : email.trim());
        }
    }

    public record OtpLoginRequest(@NotBlank String identifier) {}

    public record OtpVerifyRequest(@NotBlank String ticket, @NotBlank String code, Boolean remember) {}

    public record ResendRequest(@NotBlank String ticket) {}

    public record SignupStartRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String phone,
            @NotBlank String clinicName,
            @Min(1) @Max(50) Integer doctorCount) {}

    public record SetPasswordRequest(String currentPassword, @NotBlank @Size(min = 8) String newPassword) {}

    public record UserView(Long id, String name, String email, String phone, Role role, Long doctorId, boolean active) {
        public static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getName(), u.getEmail(), u.getPhone(), u.getRole(), u.getDoctorId(), u.isActive());
        }
    }

    public record ClinicSummary(Long id, String name, String area, String whatsappNumber,
                                boolean whatsappConnected, boolean botEnabled, String plan,
                                boolean live, int onboardingStep) {}

    public record AuthResponse(String token, UserView user, ClinicSummary clinic) {}

    public record CreateStaffRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            String phone,
            @NotNull Role role,
            Long doctorId) {}
}
