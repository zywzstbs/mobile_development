package cn.gulupai.server.course.api;

import java.time.LocalTime;

public record PeriodView(
        String id,
        String schoolId,
        String termId,
        String label,
        LocalTime startTime,
        LocalTime endTime
) {
}
