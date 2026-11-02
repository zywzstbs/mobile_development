package cn.gulupai.server.teaching.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.teaching.domain.TeacherCapabilities;
import cn.gulupai.server.teaching.persistence.ClassGrantRepository;
import cn.gulupai.server.teaching.persistence.ClassGrantRow;
import cn.gulupai.server.teaching.persistence.TeachingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CourseAuthorityService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final TeachingRepository teachingRepository;
    private final ClassGrantRepository grantRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CourseAuthorityService(TeachingRepository teachingRepository,
                                  ClassGrantRepository grantRepository,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.teachingRepository = teachingRepository;
        this.grantRepository = grantRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean isTeacher(String squareId, String userId) {
        return teachingRepository.isActiveTeacher(squareId, userId);
    }

    public void ensureTeacher(String squareId, String userId) {
        teachingRepository.ensureActive(squareId, userId);
    }

    public void requireTeacher(SquareRow square, String userId) {
        if (!isTeacher(square.id(), userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    public boolean hasCapability(String squareId, String userId, String capability) {
        return grantRepository.hasCapability(squareId, userId, capability, clock.instant());
    }

    public void requireCapability(SquareRow square, String userId, String capability) {
        if (!hasCapability(square.id(), userId, capability)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    public boolean hasReviewerOtherThan(String squareId, String excludedUserId) {
        return teachingRepository.activeAssignment(squareId).isPresent()
                || grantRepository.hasReviewerOtherThan(squareId, excludedUserId);
    }

    public List<String> capabilitiesFor(String squareId, String userId) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        capabilities.add(TeacherCapabilities.COURSE_READ);
        if (isTeacher(squareId, userId)) {
            capabilities.addAll(TeacherCapabilities.TEACHER);
        }
        grantRepository.find(squareId, userId)
                .filter(this::grantActive)
                .ifPresent(grant -> capabilities.addAll(parseCapabilities(grant.capabilitiesJson())));
        return List.copyOf(capabilities);
    }

    public boolean grantActive(ClassGrantRow grant) {
        return "ACTIVE".equals(grant.status()) && grant.expiresAt().isAfter(clock.instant());
    }

    public List<String> parseCapabilities(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("class_grant.capabilities_json 无法解析", ex);
        }
    }

    public List<String> teacherCapabilities() {
        return TeacherCapabilities.TEACHER;
    }

    public List<String> normalizeGrantCapabilities(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw ApiException.validation("capabilities 至少一项");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String capability : requested) {
            if (capability == null || !TeacherCapabilities.ASSISTANT_GRANTABLE.contains(capability)) {
                throw ApiException.validation("capability 不在班级助理授权白名单: " + capability);
            }
            unique.add(capability);
        }
        if (unique.size() > 20) {
            throw ApiException.validation("capabilities 过多");
        }
        return new ArrayList<>(unique);
    }
}
