package cn.gulupai.server.course;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.course.domain.CourseSlot;
import cn.gulupai.server.course.domain.SlotsCanonicalizer;
import cn.gulupai.server.course.domain.WeekPattern;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotsCanonicalizerTest {

    private final SlotsCanonicalizer canonicalizer = new SlotsCanonicalizer();
    private final Set<String> periods = Set.of("p1", "p2");

    @Test
    void allAndEquivalentCustomWeeksProduceSameKey() {
        CourseSlot all = new CourseSlot(3, "p1", WeekPattern.ALL, 1, 4, List.of());
        CourseSlot custom = new CourseSlot(3, "p1", WeekPattern.CUSTOM, 1, 4, List.of(1, 2, 3, 4));

        String allKey = canonicalizer.canonicalKey("s", "t", "课程", "教师", List.of(all));
        String customKey = canonicalizer.canonicalKey("s", "t", "课程", "教师", List.of(custom));
        assertThat(allKey).isEqualTo(customKey);
    }

    @Test
    void teacherAndWeekPatternAffectKey() {
        CourseSlot all = new CourseSlot(3, "p1", WeekPattern.ALL, 1, 4, List.of());
        CourseSlot odd = new CourseSlot(3, "p1", WeekPattern.ODD, 1, 4, List.of());
        String base = canonicalizer.canonicalKey("s", "t", "课程", "教师A", List.of(all));
        assertThat(base).isNotEqualTo(canonicalizer.canonicalKey("s", "t", "课程", "教师B", List.of(all)));
        assertThat(base).isNotEqualTo(canonicalizer.canonicalKey("s", "t", "课程", "教师A", List.of(odd)));
    }

    @Test
    void invalidCustomWeeksAreRejected() {
        CourseSlot bad = new CourseSlot(3, "p1", WeekPattern.CUSTOM, 1, 4, List.of(5));
        assertThatThrownBy(() -> canonicalizer.normalize(List.of(bad), periods))
                .isInstanceOf(ApiException.class);
        CourseSlot foreignPeriod = new CourseSlot(3, "unknown", WeekPattern.ALL, 1, 4, List.of());
        assertThatThrownBy(() -> canonicalizer.normalize(List.of(foreignPeriod), periods))
                .isInstanceOf(ApiException.class);
    }
}
