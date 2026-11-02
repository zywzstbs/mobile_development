package cn.gulupai.server.course.persistence;

import java.time.LocalTime;

public record PeriodRow(
        String id,
        String schoolId,
        String termId,
        String label,
        LocalTime startTime,
        LocalTime endTime,
        int ordinal
) {
}
