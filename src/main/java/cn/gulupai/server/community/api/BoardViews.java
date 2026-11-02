package cn.gulupai.server.community.api;

import java.time.Instant;
import java.util.List;

public final class BoardViews {
    private BoardViews() {
    }

    public record Ref(String type, String id, int version) {
    }

    public record Topic(String id, String projectId, String authorId, String authorName,
                        String title, String content, String tag, List<Ref> refs,
                        List<String> mentions, String status, int revision, boolean withdrawn,
                        int replyCount, String helpfulReplyId, String resolutionNote,
                        String closeReason, boolean pinned, boolean following,
                        Instant createdAt, Instant updatedAt) {
        public Topic {
            refs = List.copyOf(refs);
            mentions = List.copyOf(mentions);
        }
    }

    public record Reply(String id, String topicId, String projectId, String authorId,
                        String authorName, String content, String replyToId,
                        List<String> mentions, int revision, boolean withdrawn,
                        Instant createdAt, Instant updatedAt) {
        public Reply {
            mentions = List.copyOf(mentions);
        }
    }

    public record Revision(String id, String contentType, String contentId, int version,
                           String title, String content, int revision, Instant createdAt) {
    }
}
