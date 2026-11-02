package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.api.CourseMatchRequest;
import cn.gulupai.server.course.api.CourseSlotRequest;
import cn.gulupai.server.course.api.CreateSquareRequest;
import cn.gulupai.server.course.domain.CourseSlot;
import cn.gulupai.server.course.domain.SlotsCanonicalizer;
import cn.gulupai.server.course.domain.WeekPattern;
import cn.gulupai.server.course.persistence.CourseDictionaryRepository;
import cn.gulupai.server.course.persistence.CourseNameRow;
import cn.gulupai.server.course.persistence.PeriodRow;
import cn.gulupai.server.course.persistence.SchoolRow;
import cn.gulupai.server.course.persistence.TermRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课程匹配/创建共用的规范化入口：学校学期、课程名、教师和课表都先验证再签名。
 */
@Service
public class CourseLookupService {

    private final CourseDictionaryRepository dictionaryRepository;
    private final SlotsCanonicalizer canonicalizer;

    public CourseLookupService(CourseDictionaryRepository dictionaryRepository,
                               SlotsCanonicalizer canonicalizer) {
        this.dictionaryRepository = dictionaryRepository;
        this.canonicalizer = canonicalizer;
    }

    public NormalizedCourseInput normalize(AuthenticatedUser principal, CourseMatchRequest request) {
        return normalize(principal, request.schoolId(), request.termId(), request.teacherName(),
                request.slots(), request.courseNameId(), request.customCourseName());
    }

    public NormalizedCourseInput normalize(AuthenticatedUser principal, CreateSquareRequest request) {
        return normalize(principal, request.schoolId(), request.termId(), request.teacherName(),
                request.slots(), request.courseNameId(), request.customCourseName());
    }

    public NormalizedCourseInput normalize(AuthenticatedUser principal,
                                           String schoolId, String termId, String teacherName,
                                           List<CourseSlotRequest> slots,
                                           String courseNameId, String customCourseName) {
        requireSameSchool(principal, schoolId);
        SchoolRow school = dictionaryRepository.findSchool(schoolId)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND));
        TermRow term = requireTerm(schoolId, termId);

        List<PeriodRow> periods = dictionaryRepository.listPeriods(schoolId, termId);
        Set<String> periodIds = periods.stream().map(PeriodRow::id).collect(Collectors.toSet());
        List<CourseSlot> domainSlots = new ArrayList<>();
        for (CourseSlotRequest slot : slots) {
            domainSlots.add(new CourseSlot(slot.weekday(), slot.periodId(),
                    WeekPattern.valueOf(slot.weekPattern()), slot.startWeek(), slot.endWeek(),
                    slot.customWeeks() == null ? List.of() : slot.customWeeks()));
        }
        List<CourseSlot> normalizedSlots = canonicalizer.normalize(domainSlots, periodIds);

        boolean hasId = courseNameId != null && !courseNameId.isBlank();
        boolean hasCustom = customCourseName != null && !customCourseName.isBlank();
        if (hasId == hasCustom) {
            throw ApiException.validation("courseNameId 与 customCourseName 必须二选一");
        }
        String resolvedCourseNameId = null;
        String fullCourseName;
        if (hasId) {
            CourseNameRow courseName = dictionaryRepository.findCourseName(courseNameId)
                    .orElseThrow(() -> ApiException.validation("课程字典ID不存在"));
            if (!courseName.schoolId().equals(schoolId)) {
                throw ApiException.validation("课程字典不属于当前学校");
            }
            resolvedCourseNameId = courseName.id();
            fullCourseName = courseName.fullName();
        } else {
            fullCourseName = canonicalizer.normalizeDisplayName(customCourseName);
            if (fullCourseName.isEmpty() || fullCourseName.length() > 160) {
                throw ApiException.validation("自定义课程名必须为1～160个字符");
            }
        }

        String normalizedTeacher = canonicalizer.normalizeDisplayName(teacherName);
        if (normalizedTeacher.isEmpty() || normalizedTeacher.length() > 80) {
            throw ApiException.validation("教师名必须为1～80个字符");
        }
        String canonicalKey = canonicalizer.canonicalKey(school.id(), term.id(), fullCourseName,
                normalizedTeacher, normalizedSlots);
        return new NormalizedCourseInput(schoolId, termId, resolvedCourseNameId, fullCourseName,
                normalizedTeacher, normalizedSlots, canonicalKey);
    }

    public TermRow requireTerm(String schoolId, String termId) {
        TermRow term = dictionaryRepository.findTerm(termId)
                .orElseThrow(() -> new ApiException(ApiCode.NOT_FOUND));
        if (!term.schoolId().equals(schoolId)) {
            throw new ApiException(ApiCode.NOT_FOUND);
        }
        return term;
    }

    public void requireSameSchool(AuthenticatedUser principal, String schoolId) {
        if (principal == null) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        if (!principal.schoolId().equals(schoolId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }
}
