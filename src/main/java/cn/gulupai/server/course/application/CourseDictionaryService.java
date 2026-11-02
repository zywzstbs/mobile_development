package cn.gulupai.server.course.application;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.api.CourseNameView;
import cn.gulupai.server.course.api.PeriodView;
import cn.gulupai.server.course.api.TermView;
import cn.gulupai.server.course.persistence.CourseDictionaryRepository;
import cn.gulupai.server.course.persistence.SchoolRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CourseDictionaryService {

    private final CourseDictionaryRepository repository;
    private final CourseLookupService lookupService;
    private final CourseProjectionService projectionService;

    public CourseDictionaryService(CourseDictionaryRepository repository,
                                   CourseLookupService lookupService,
                                   CourseProjectionService projectionService) {
        this.repository = repository;
        this.lookupService = lookupService;
        this.projectionService = projectionService;
    }

    @Transactional(readOnly = true)
    public List<CourseNameView> listCourseNames(AuthenticatedUser principal, String schoolId, String q) {
        lookupService.requireSameSchool(principal, schoolId);
        repository.findSchool(schoolId).orElseThrow(ApiException::notFound);
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) {
            throw ApiException.validation("q 不能超过80个字符");
        }
        return repository.listCourseNames(schoolId, query).stream()
                .map(projectionService::courseName).toList();
    }

    @Transactional(readOnly = true)
    public List<TermView> listTerms(AuthenticatedUser principal, String schoolId) {
        lookupService.requireSameSchool(principal, schoolId);
        SchoolRow school = repository.findSchool(schoolId).orElseThrow(ApiException::notFound);
        return repository.listTerms(schoolId).stream()
                .map(term -> projectionService.term(term, school.timezone())).toList();
    }

    @Transactional(readOnly = true)
    public List<PeriodView> listPeriods(AuthenticatedUser principal, String schoolId, String termId) {
        lookupService.requireSameSchool(principal, schoolId);
        lookupService.requireTerm(schoolId, termId);
        return repository.listPeriods(schoolId, termId).stream()
                .map(projectionService::period).toList();
    }
}
