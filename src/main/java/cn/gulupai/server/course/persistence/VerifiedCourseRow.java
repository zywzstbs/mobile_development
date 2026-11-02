package cn.gulupai.server.course.persistence;

public record VerifiedCourseRow(
        String id,
        String schoolId,
        String termId,
        String courseNameId,
        String courseFullName,
        String teacherUserId,
        String canonicalKey
) {
}
