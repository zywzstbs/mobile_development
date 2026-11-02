package cn.gulupai.server.teaching.api;

import cn.gulupai.server.common.validation.OpaqueId;
import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class TeachingRequests {

    private TeachingRequests() {
    }

    public record ProofReferenceRequest(
            @NotBlank @Pattern(regexp = "LINK") String kind,
            @NotBlank @Utf16Length(max = 2048)
            @Pattern(regexp = "https?://.+", message = "材料链接必须为http/https URL") String url,
            @NotBlank @Utf16Length(max = 120) String title
    ) {
    }

    public record TeacherClaimRequest(
            @NotBlank @Utf16Length(max = 500) String message,
            @Size(max = 3) List<@Valid ProofReferenceRequest> proofRefs
    ) {
    }

    public record GrantRequest(
            @NotBlank @OpaqueId String studentId,
            @NotNull @Size(min = 1, max = 20) List<@NotBlank String> capabilities
    ) {
    }

    public record GrantRevocationRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record RelinquishRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedSquareRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record TeacherTransferRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedSquareRevision,
            @NotBlank @Utf16Length(max = 500) String reason,
            @NotBlank @OpaqueId String recipientTeacherId
    ) {
    }

    public record TransferResponseRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision,
            @Utf16Length(max = 500) String reason
    ) {
    }
}
