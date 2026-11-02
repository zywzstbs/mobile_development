package cn.gulupai.server.course.api;

import java.util.List;

public record CourseMatchCandidateView(
        SquareView square,
        String matchKind,
        List<String> differences
) {
    public CourseMatchCandidateView {
        differences = List.copyOf(differences);
    }
}
