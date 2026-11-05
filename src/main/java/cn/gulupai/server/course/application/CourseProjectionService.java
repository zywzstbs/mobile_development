package cn.gulupai.server.course.application;

import cn.gulupai.server.course.api.AssignmentView;
import cn.gulupai.server.course.api.CourseNameView;
import cn.gulupai.server.course.api.PeriodView;
import cn.gulupai.server.course.api.SourceReferenceView;
import cn.gulupai.server.course.api.SquareView;
import cn.gulupai.server.course.api.TermView;
import cn.gulupai.server.course.domain.CourseSlot;
import cn.gulupai.server.course.persistence.AssignmentRow;
import cn.gulupai.server.course.persistence.CourseNameRow;
import cn.gulupai.server.course.persistence.PeriodRow;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.course.persistence.TermRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CourseProjectionService {

    private static final TypeReference<List<CourseSlot>> SLOT_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<SourceReferenceView>> REFERENCE_LIST =
            new TypeReference<>() {
            };
    private static final List<String> READ_CAPABILITIES = List.of("COURSE_READ");

    private final ObjectMapper objectMapper;

    public CourseProjectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SquareView square(SquareRow row, String membershipStatus) {
        return square(row, membershipStatus, READ_CAPABILITIES);
    }

    public SquareView square(SquareRow row, String membershipStatus, List<String> capabilities) {
        return new SquareView(
                row.id(),
                row.id(),
                row.squareNo(),
                row.schoolId(),
                row.termId(),
                row.name(),
                row.teacherName(),
                slots(row.slotsJson()),
                row.status(),
                row.revision(),
                capabilities,
                membershipStatus == null ? "NONE" : membershipStatus);
    }

    public AssignmentView assignment(AssignmentRow row, String viewerId) {
        boolean canEdit = viewerId != null
                && viewerId.equals(row.createdBy())
                && "STUDENT".equals(row.origin())
                && "PENDING_CONFIRMATION".equals(row.confirmationStatus());
        return new AssignmentView(
                row.id(),
                row.squareId(),
                row.name(),
                row.requirements(),
                row.origin(),
                row.confirmationStatus(),
                row.hardDueAt(),
                row.minMembers(),
                row.maxMembers(),
                row.gradingEnabled(),
                row.revision(),
                references(row.sourceReferencesJson()),
                row.createdBy(),
                canEdit);
    }

    public TermView term(TermRow row, String timezone) {
        return new TermView(row.id(), row.schoolId(), row.name(),
                row.startDate(), row.endDate(), timezone);
    }

    public PeriodView period(PeriodRow row) {
        return new PeriodView(row.id(), row.schoolId(), row.termId(), row.label(),
                row.startTime(), row.endTime());
    }

    public CourseNameView courseName(CourseNameRow row) {
        return new CourseNameView(row.id(), row.fullName(), row.aliases());
    }

    public String slotsJson(List<CourseSlot> slots) {
        try {
            return objectMapper.writeValueAsString(slots);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化课表", ex);
        }
    }

    public String referencesJson(List<SourceReferenceView> references) {
        try {
            return objectMapper.writeValueAsString(references);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化来源材料", ex);
        }
    }

    private List<CourseSlot> slots(String json) {
        try {
            return objectMapper.readValue(json, SLOT_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("square.slots_json 无法解析", ex);
        }
    }

    private List<SourceReferenceView> references(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, REFERENCE_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("assignment.source_references_json 无法解析", ex);
        }
    }
}
