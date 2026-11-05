package cn.gulupai.server.identity.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.identity.application.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/auth/registration-options")
    public ApiResponse<RegistrationOptions> options() {
        return ApiResponse.ok(registrationService.options());
    }

    @PostMapping("/auth/registrations")
    public ApiResponse<RegistrationReceipt> register(
            @Valid @RequestBody RegistrationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(registrationService.register(request, idempotencyKey, clientIp(servletRequest)));
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
