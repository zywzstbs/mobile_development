package cn.gulupai.server.course.api;

import cn.gulupai.server.course.domain.CourseSlot;

import java.util.List;

public record NormalizedCourseInputView(
        String schoolId,
        String termId,
        String courseNameId,
        String teacherName,
        List<CourseSlot> slots
) {
    public NormalizedCourseInputView {
        slots = List.copyOf(slots);
    }
}
