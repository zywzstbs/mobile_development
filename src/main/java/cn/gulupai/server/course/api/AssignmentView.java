package cn.gulupai.server.course.api;

import java.time.Instant;
import java.util.List;

public record AssignmentView(
        String id,
        String squareId,
        String name,
        String requirements,
        String origin,
        String confirmationStatus,
        Instant hardDueAt,
        int minMembers,
        int maxMembers,
        boolean gradingEnabled,
        int revision,
        List<SourceReferenceView> sourceReferences
) {
    public AssignmentView {
        sourceReferences = List.copyOf(sourceReferences);
    }
}
