package cn.gulupai.server.profile.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class ProfileViews {

    private ProfileViews() {
    }

    public record ArtifactView(String kind, String url, String title) {
    }

    public record WorkVersionView(int version, int revision, String title, String content,
                                  Instant updatedAt) {
    }

    public record WorkView(String id, String userId, String title, String description,
                           String role, String myContribution, String sourceProjectId,
                           List<ArtifactView> artifacts, String audience, String status,
                           List<WorkVersionView> versions, int revision, Instant createdAt) {
        public WorkView {
            artifacts = List.copyOf(artifacts);
            versions = List.copyOf(versions);
        }
    }

    public record CooperationMember(String userId, String displayName) {
    }

    public record CooperationView(String projectId, String projectName, Instant endedAt,
                                  List<CooperationMember> members, boolean canReview,
                                  Instant reviewEndsAt) {
        public CooperationView {
            members = List.copyOf(members);
        }
    }

    public record CooperationSummary(String dimension, String status, int validAuthors,
                                     String positive, String needsImprovement) {
    }

    public record ProfileView(String userId, String displayName, List<String> skills,
                              int weeklyHours, String availability, String collaborationGoal,
                              String intro, List<WorkView> works, JsonNode processSummary,
                              List<CooperationSummary> cooperationSummaries,
                              boolean cooperationSummaryVisible, String teacherSummaryStatus,
                              List<JsonNode> teacherSummaries, boolean isSelf) {
        public ProfileView {
            skills = List.copyOf(skills);
            works = List.copyOf(works);
            cooperationSummaries = List.copyOf(cooperationSummaries);
            teacherSummaries = List.copyOf(teacherSummaries);
        }
    }

    public record PeerObservationView(String dimension, String level, String evidenceText) {
    }

    public record PeerReviewVersionView(int version, int revision, String title, String content,
                                        Instant updatedAt) {
    }

    public record PeerReviewView(String id, String projectId, String projectName, String authorId,
                                 String targetUserId, String targetName, boolean observedEnough,
                                 List<PeerObservationView> observations, String comment,
                                 String status, boolean canEdit, boolean canWithdraw,
                                 List<PeerReviewVersionView> versions, int revision,
                                 Instant createdAt) {
        public PeerReviewView {
            observations = List.copyOf(observations);
            versions = List.copyOf(versions);
        }
    }

    public record PeerContextTarget(String userId, String displayName, boolean canReview,
                                    JsonNode myReview) {
    }

    public record PeerContextView(String projectId, String projectName,
                                  List<PeerContextTarget> targets) {
        public PeerContextView {
            targets = List.copyOf(targets);
        }
    }

    public record AiReferenceView(String id, String projectId, String sourceProjectId,
                                  String clientRecordId, String scene, String model, String title,
                                  String text, String createdBy, int revision, Instant createdAt,
                                  String origin, String authorId) {
    }
}
