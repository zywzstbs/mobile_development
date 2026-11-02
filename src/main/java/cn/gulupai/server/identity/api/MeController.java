package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.identity.application.AuthService;
import cn.gulupai.server.identity.application.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AuthService authService;
    private final ProfileService profileService;

    public MeController(AuthService authService, ProfileService profileService) {
        this.authService = authService;
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(authService.me(principal));
    }

    @GetMapping("/me/profile")
    public ApiResponse<ProfileView> profile(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(profileService.getProfile(principal));
    }

    @GetMapping("/me/profile-visibility")
    public ApiResponse<VisibilityView> visibility(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.ok(profileService.getVisibility(principal));
    }

    @PutMapping("/me/profile")
    public ApiResponse<ProfileView> updateProfile(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ProfileUpdateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profileService.updateProfile(principal, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PutMapping("/me/profile-visibility")
    public ApiResponse<VisibilityView> updateVisibility(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody VisibilityUpdateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(profileService.updateVisibility(principal, request, idempotencyKey,
                servletRequest.getRequestURI()));
    }
}
