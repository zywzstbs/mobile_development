package cn.gulupai.server.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class ReportViews {

    private ReportViews() {
    }

    public record Gap(String code, String section, String resourceId, String message) {
    }

    public record DeliveryDraft(JsonNode draft, String sourceKey, List<Gap> readiness, List<Gap> gaps) {
    }

    public record SourceRef(String taskId, String submissionId) {
    }

    public record DeliveryPreview(String previewToken, Instant expiresAt, String sourceKey,
                                  JsonNode draft, List<SourceRef> sources) {
    }

    public record DeliveryRelease(String id, String projectId, int version, String sourceKey,
                                  List<ReportRequests.MaterialRef> work,
                                  List<ReportRequests.MaterialRef> report,
                                  List<ReportRequests.MaterialRef> demo,
                                  List<ReportRequests.MaterialRef> artifacts,
                                  String confirmedBy, Instant confirmedAt, int revision) {
    }

    public record Report(String sourceKey, int closingCycle, JsonNode data, List<Gap> gaps) {
    }

    public record Snapshot(String id, String projectId, String type, String name, String sourceKey,
                           int closingCycle, JsonNode data, String emptyReason, String createdBy,
                           Instant createdAt, int revision) {
    }

    public record Reflection(String id, String projectId, String userId, String authorName,
                             String workSummary, String learning, String problems, String improvements,
                             int revision, Instant updatedAt) {
    }

    public record OverallReflection(String id, String projectId, String authorId,
                                    String summary, String coordination, String improvements,
                                    int revision, Instant updatedAt) {
    }
}
