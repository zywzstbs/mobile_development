package cn.gulupai.server.report;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class ReportRequests {

    private ReportRequests() {
    }

    public record MaterialRef(
            @NotBlank @Utf16Length(min = 1, max = 20) String kind,
            @NotBlank @Utf16Length(min = 1, max = 2000) String url,
            @NotBlank @Utf16Length(min = 1, max = 120) String title
    ) {
    }

    public record UpdateDeliveryDraft(
            @Min(0) @Max(999999999) int expectedRevision,
            @NotNull @Valid List<MaterialRef> work,
            @NotNull @Valid List<MaterialRef> report,
            @NotNull @Valid List<MaterialRef> demo,
            @Utf16Length(max = 2000) String notes
    ) {
        public UpdateDeliveryDraft {
            notes = notes == null ? "" : notes;
        }
    }

    public record PreviewDelivery(@NotBlank @Utf16Length(min = 1, max = 64) String expectedSourceKey) {
    }

    public record PublishDelivery(@NotBlank @Utf16Length(min = 1, max = 256) String previewToken) {
    }

    public record LifecycleAction(
            @Min(1) @Max(999999999) int expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 40) String action,
            @Utf16Length(max = 120) String expectedReportId,
            @Utf16Length(max = 120) String expectedReleaseId
    ) {
    }

    public record CreateSnapshot(
            @NotBlank @Utf16Length(min = 1, max = 20) String type,
            @NotBlank @Utf16Length(min = 1, max = 120) String name,
            @NotBlank @Utf16Length(min = 1, max = 64) String expectedSourceKey,
            @Min(0) @Max(999999999) int closingCycle,
            @Utf16Length(max = 1000) String emptyReason
    ) {
        public CreateSnapshot {
            emptyReason = emptyReason == null ? "" : emptyReason;
        }
    }

    public record PersonalReflection(
            @Min(0) @Max(999999999) int expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 2000) String workSummary,
            @NotBlank @Utf16Length(min = 1, max = 2000) String learning,
            @NotBlank @Utf16Length(min = 1, max = 2000) String problems,
            @NotBlank @Utf16Length(min = 1, max = 2000) String improvements
    ) {
    }

    public record OverallReflection(
            @Min(0) @Max(999999999) int expectedRevision,
            @NotBlank @Utf16Length(min = 1, max = 2000) String summary,
            @NotBlank @Utf16Length(min = 1, max = 2000) String coordination,
            @NotBlank @Utf16Length(min = 1, max = 2000) String improvements
    ) {
    }
}
