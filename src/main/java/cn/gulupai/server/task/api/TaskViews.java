package cn.gulupai.server.task.api;

import cn.gulupai.server.team.api.TeamViews;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public final class TaskViews {

    private TaskViews() {
    }

    public record CriterionView(String id, String text) {
    }

    public record ShareView(String userId, String displayName, String points, String responsibility, boolean accepted) {
    }

    public record ContributionView(String id, String userId, String acceptanceId, String statement, Instant at) {
    }

    public record UnitView(
            String id, String title, Instant dueAt, String points, List<ShareView> shares,
            List<CriterionView> criteria, int revision, Object acceptance,
            List<ContributionView> contributionConfirmations
    ) {
    }

    public record ReviewerAssignmentView(String userId, String displayName, String kind, String status, int version) {
    }

    public record DependencyView(String taskId, String gate) {
    }

    public record TaskView(
            String id,
            String projectId,
            String title,
            String description,
            String mode,
            String stageId,
            String ownerId,
            String ownerName,
            String status,
            Instant dueAt,
            List<CriterionView> criteria,
            List<UnitView> units,
            List<DependencyView> dependencies,
            Object reviewerAssignment,
            int revision,
            boolean claimable,
            @JsonInclude(JsonInclude.Include.NON_NULL) String agreementStatus,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer agreementVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) String agreementId
    ) {
    }

    public record BoardView(
            int projectRevision,
            List<TeamViews.StageView> stages,
            List<TaskView> tasks,
            List<TeamViews.MembershipView> members
    ) {
    }

    public record StagesResult(int projectRevision, List<TeamViews.StageView> stages) {
    }

    public record ColorResult(String color, int revision) {
    }
}
