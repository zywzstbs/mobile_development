package cn.gulupai.server.course.api;

import cn.gulupai.server.course.domain.CourseSlot;

import java.util.List;

public record SquareView(
        String id,
        String squareId,
        String squareNo,
        String schoolId,
        String termId,
        String fullCourseName,
        String teacherName,
        List<CourseSlot> slots,
        String status,
        int revision,
        List<String> capabilities,
        String myMembershipStatus
) {
    public SquareView {
        slots = List.copyOf(slots);
        capabilities = List.copyOf(capabilities);
    }
}
