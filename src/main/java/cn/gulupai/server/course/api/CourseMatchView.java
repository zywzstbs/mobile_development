package cn.gulupai.server.course.api;

import java.time.Instant;
import java.util.List;

public record CourseMatchView(
        SquareView exactMatch,
        List<CourseMatchCandidateView> candidates,
        NormalizedCourseInputView normalizedInput,
        boolean canCreate,
        String candidateCheckToken,
        Instant expiresAt
) {
    public CourseMatchView {
        candidates = List.copyOf(candidates);
    }
}
