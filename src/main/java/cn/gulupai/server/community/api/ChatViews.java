package cn.gulupai.server.community.api;

import java.time.Instant;
import java.util.List;

public final class ChatViews {
    private ChatViews() {
    }

    public record Ref(String type, String id, int version) {
    }

    public record Message(String id, String projectId, String clientMessageId, long sequence,
                          String authorId, String authorName, String content, String replyToId,
                          List<String> mentions, List<Ref> refs, int revision, boolean withdrawn,
                          Instant createdAt) {
        public Message {
            mentions = List.copyOf(mentions);
            refs = List.copyOf(refs);
        }
    }

    public record Page(List<Message> items, String nextCursor, boolean hasMore,
                       long nextAfterSequence, long latestSequence, List<Message> changes,
                       String nextChangeCursor, boolean hasMoreChanges) {
        public Page {
            items = List.copyOf(items);
            changes = List.copyOf(changes);
        }
    }

    public record Resolution(boolean found, Message message) {
    }

    public record Cursor(String projectId, long lastSequence) {
    }
}
