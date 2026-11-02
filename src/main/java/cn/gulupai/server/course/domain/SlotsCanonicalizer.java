package cn.gulupai.server.course.domain;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.Hashing;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 课表规范化与课程匹配签名。
 *
 * <p>签名包含学校、学期、规范化课程名、教师名和展开后的 weekday/period/week 集合；
 * 同名但教师或时段不同的真实班不会混同。对象键/数组不参与签名，签名只做摘要。
 */
@Component
public class SlotsCanonicalizer {

    private static final String KEY_VERSION = "COURSE_MATCH_V1";
    private static final Comparator<CourseSlot> SLOT_ORDER =
            Comparator.comparingInt(CourseSlot::weekday)
                    .thenComparing(CourseSlot::periodId)
                    .thenComparingInt(CourseSlot::startWeek)
                    .thenComparingInt(CourseSlot::endWeek)
                    .thenComparing(slot -> slot.weekPattern().ordinal());

    public List<CourseSlot> normalize(List<CourseSlot> input, Set<String> validPeriodIds) {
        if (input == null || input.isEmpty()) {
            throw ApiException.validation("slots 至少需要一个时段");
        }
        if (input.size() > 50) {
            throw ApiException.validation("slots 最多50项");
        }
        List<CourseSlot> normalized = new ArrayList<>();
        for (CourseSlot slot : input) {
            normalized.add(normalizeOne(slot, validPeriodIds));
        }
        normalized.sort(SLOT_ORDER);
        return List.copyOf(new LinkedHashSet<>(normalized));
    }

    private CourseSlot normalizeOne(CourseSlot slot, Set<String> validPeriodIds) {
        if (slot == null) {
            throw ApiException.validation("slots 不能包含null");
        }
        if (slot.weekday() < 1 || slot.weekday() > 7) {
            throw ApiException.validation("weekday 必须在1～7之间");
        }
        if (slot.periodId() == null || !validPeriodIds.contains(slot.periodId())) {
            throw ApiException.validation("periodId 不属于当前学校学期");
        }
        if (slot.startWeek() < 1 || slot.endWeek() < slot.startWeek()) {
            throw ApiException.validation("startWeek/endWeek 无效");
        }
        if (slot.endWeek() > 1000) {
            throw ApiException.validation("endWeek 超出安全范围");
        }
        if (slot.weekPattern() == null) {
            throw ApiException.validation("weekPattern 不能为空");
        }
        List<Integer> customWeeks = slot.customWeeks() == null ? List.of() : slot.customWeeks();
        if (slot.weekPattern() == WeekPattern.CUSTOM) {
            if (customWeeks.isEmpty()) {
                throw ApiException.validation("CUSTOM 模式必须提供 customWeeks");
            }
            if (customWeeks.size() > 100) {
                throw ApiException.validation("customWeeks 过多");
            }
            TreeSet<Integer> unique = new TreeSet<>();
            for (Integer week : customWeeks) {
                if (week == null || week < slot.startWeek() || week > slot.endWeek()) {
                    throw ApiException.validation("customWeeks 必须位于 startWeek～endWeek 内");
                }
                unique.add(week);
            }
            customWeeks = List.copyOf(unique);
        } else if (!customWeeks.isEmpty()) {
            throw ApiException.validation("非 CUSTOM 模式的 customWeeks 必须为空");
        }
        return new CourseSlot(slot.weekday(), slot.periodId(), slot.weekPattern(),
                slot.startWeek(), slot.endWeek(), customWeeks);
    }

    public String canonicalKey(String schoolId, String termId, String courseName,
                               String teacherName, List<CourseSlot> slots) {
        StringBuilder builder = new StringBuilder(KEY_VERSION).append('\n');
        builder.append("school=").append(schoolId).append('\n');
        builder.append("term=").append(termId).append('\n');
        builder.append("course=").append(normalizedKeyText(courseName)).append('\n');
        builder.append("teacher=").append(normalizedKeyText(teacherName)).append('\n');
        TreeSet<String> weeks = new TreeSet<>();
        for (CourseSlot slot : slots) {
            for (int week : expandedWeeks(slot)) {
                weeks.add(slot.weekday() + "|" + slot.periodId() + "|" + week);
            }
        }
        weeks.forEach(value -> builder.append("slot=").append(value).append('\n'));
        return Hashing.sha256Hex(builder.toString());
    }

    public List<Integer> expandedWeeks(CourseSlot slot) {
        List<Integer> weeks = new ArrayList<>();
        for (int week = slot.startWeek(); week <= slot.endWeek(); week++) {
            boolean include = switch (slot.weekPattern()) {
                case ALL -> true;
                case ODD -> week % 2 == 1;
                case EVEN -> week % 2 == 0;
                case CUSTOM -> slot.customWeeks().contains(week);
            };
            if (include) {
                weeks.add(week);
            }
        }
        return weeks;
    }

    /** 显示名保留原大小写，只做首尾trim、空白折叠和NFKC规范化。 */
    public String normalizeDisplayName(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
    }

    /** 规范键/数据库唯一名额外做小写化；不改变真实展示名。 */
    public String normalizedKeyText(String value) {
        return normalizeDisplayName(value).toLowerCase(Locale.ROOT);
    }
}
