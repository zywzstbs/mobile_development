package cn.gulupai.server.file;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping("/files/upload-intents")
    public ApiResponse<FileService.IntentView> intent(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody FileRequests.IntentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(files.createIntent(principal, toIntent(request), idempotencyKey,
                servletRequest.getRequestURI()));
    }

    @PostMapping("/files/{fileId}/completions")
    public ApiResponse<FileService.FileView> complete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String fileId,
            @Valid @RequestBody FileRequests.CompletionRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(files.complete(principal, fileId,
                new FileService.CompletionRequest(request.expectedRevision(), request.uploadToken()),
                idempotencyKey, servletRequest.getRequestURI()));
    }

    @GetMapping("/files/{fileId}")
    public ApiResponse<FileService.FileView> metadata(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String fileId) {
        return ApiResponse.ok(files.metadata(principal, fileId));
    }

    @PostMapping("/files/{fileId}/download-authorizations")
    public ApiResponse<FileService.DownloadView> download(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String fileId,
            @Valid @RequestBody FileRequests.DownloadRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(files.authorizeDownload(principal, fileId, idempotencyKey,
                servletRequest.getRequestURI()));
    }

    private static FileService.IntentRequest toIntent(FileRequests.IntentRequest request) {
        return new FileService.IntentRequest(request.purpose(), request.fileName(), request.contentType(),
                request.sizeBytes(), request.sha256(), request.projectId(), request.caseId(), request.ticketId());
    }
}
