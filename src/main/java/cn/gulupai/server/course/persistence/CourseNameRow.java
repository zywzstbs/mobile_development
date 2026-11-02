package cn.gulupai.server.course.persistence;

import java.util.List;

public record CourseNameRow(
        String id,
        String schoolId,
        String fullName,
        String normalizedName,
        List<String> aliases
) {
    public CourseNameRow {
        aliases = List.copyOf(aliases);
    }
}
