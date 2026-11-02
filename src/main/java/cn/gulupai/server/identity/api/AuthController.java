package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.identity.application.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/sessions")
    public ApiResponse<SessionView> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.login(request, clientIp(servletRequest)));
    }

    @PostMapping("/auth/sessions/refresh")
    public ApiResponse<SessionView> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @DeleteMapping("/auth/sessions/current")
    public ApiResponse<LogoutView> logout(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody(required = false) LogoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.logout(principal, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    private String clientIp(HttpServletRequest request) {
        // 不信任任意 X-Forwarded-For；只有受控内网反代接入时才在部署层改写来源。
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
