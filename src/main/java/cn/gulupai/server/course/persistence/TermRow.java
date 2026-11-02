package cn.gulupai.server.course.persistence;

import java.time.LocalDate;

public record TermRow(
        String id,
        String schoolId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        int ordinal
) {
}
