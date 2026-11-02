package cn.gulupai.server.course.domain;

import java.util.List;

/**
 * 规范化课表时段。CUSTOM 之外 customWeeks 固定为空；周次去重升序。
 */
public record CourseSlot(
        int weekday,
        String periodId,
        WeekPattern weekPattern,
        int startWeek,
        int endWeek,
        List<Integer> customWeeks
) {
    public CourseSlot {
        customWeeks = customWeeks == null ? List.of() : List.copyOf(customWeeks);
    }
}
