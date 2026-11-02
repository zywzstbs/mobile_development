package cn.gulupai.server.course.application;

import cn.gulupai.server.course.domain.CourseSlot;

import java.util.List;

public record NormalizedCourseInput(
        String schoolId,
        String termId,
        String courseNameId,
        String courseFullName,
        String teacherName,
        List<CourseSlot> slots,
        String canonicalKey
) {
    public NormalizedCourseInput {
        slots = List.copyOf(slots);
    }
}
