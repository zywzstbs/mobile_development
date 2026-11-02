package cn.gulupai.server.course.api;

import cn.gulupai.server.common.api.ApiResponse;
import cn.gulupai.server.common.api.ListEnvelope;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.course.application.CourseDictionaryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CourseDictionaryController {

    private final CourseDictionaryService dictionaryService;

    public CourseDictionaryController(CourseDictionaryService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/course-names")
    public ApiResponse<ListEnvelope<CourseNameView>> courseNames(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String schoolId,
            @RequestParam(required = false) String q) {
        List<CourseNameView> items = dictionaryService.listCourseNames(principal, schoolId, q);
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @GetMapping("/schools/{schoolId}/terms")
    public ApiResponse<ListEnvelope<TermView>> terms(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String schoolId) {
        List<TermView> items = dictionaryService.listTerms(principal, schoolId);
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @GetMapping("/schools/{schoolId}/periods")
    public ApiResponse<ListEnvelope<PeriodView>> periods(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String schoolId,
            @RequestParam String termId) {
        List<PeriodView> items = dictionaryService.listPeriods(principal, schoolId, termId);
        return ApiResponse.ok(ListEnvelope.full(items));
    }
}
