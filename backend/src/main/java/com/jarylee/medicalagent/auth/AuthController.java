package com.jarylee.medicalagent.auth;

import com.jarylee.medicalagent.common.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.Duration;
import java.util.Arrays;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationService service;
    private final CurrentUserProvider currentUser;
    private final boolean secureCookie;

    public AuthController(AuthenticationService service, CurrentUserProvider currentUser,
                          @Value("${medical.security.secure-cookie:false}") boolean secureCookie,
                          Environment environment) {
        this.service = service;
        this.currentUser = currentUser;
        this.secureCookie = secureCookie
                || environment.acceptsProfiles(Profiles.of("prod"));
    }

    @PostMapping("/login")
    public ApiResponse<AuthenticatedUser> login(@Valid @RequestBody LoginRequest request,
                                                 HttpServletResponse response) {
        var result = service.login(request.hospitalCode(), request.username(), request.password());
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader(result.token(), Duration.ofHours(8).toSeconds()));
        return ApiResponse.ok(result.user());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        service.logout(cookie(request));
        response.addHeader(HttpHeaders.SET_COOKIE, cookieHeader("", 0));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthenticatedUser> me() {
        return ApiResponse.ok(currentUser.requireUser());
    }

    @GetMapping("/csrf")
    public ApiResponse<String> csrf(CsrfToken token) {
        return ApiResponse.ok(token.getToken());
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(currentUser.requireUser(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok(null);
    }

    private String cookieHeader(String token, long maxAge) {
        return SessionAuthenticationFilter.COOKIE_NAME + "=" + token + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=Strict" + (secureCookie ? "; Secure" : "");
    }

    private String cookie(HttpServletRequest request) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(item -> SessionAuthenticationFilter.COOKIE_NAME.equals(item.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    public record LoginRequest(String hospitalCode, @NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
}
