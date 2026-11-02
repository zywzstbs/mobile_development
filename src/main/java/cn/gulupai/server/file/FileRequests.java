package cn.gulupai.server.file;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class FileRequests {

    private FileRequests() {
    }

    public record IntentRequest(
            @NotBlank @Pattern(regexp = "SUPPORT_ATTACHMENT|DELIVERY_ARTIFACT|MATERIAL_PROOF|WORK_ARTIFACT")
            String purpose,
            @NotBlank @Utf16Length(max = 200) String fileName,
            @NotBlank @Utf16Length(max = 200) String contentType,
            @NotNull @Min(1) @Max(8388608) Integer sizeBytes,
            @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @Utf16Length(max = 120) String projectId,
            @Utf16Length(max = 120) String caseId,
            @Utf16Length(max = 120) String ticketId
    ) {
    }

    public record CompletionRequest(
            @NotNull @Min(1) @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 200) String uploadToken
    ) {
    }

    public record DownloadRequest() {
    }
}
