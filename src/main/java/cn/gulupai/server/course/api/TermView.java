package cn.gulupai.server.course.api;

import java.time.LocalDate;

public record TermView(
        String id,
        String schoolId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String timezone
) {
}
