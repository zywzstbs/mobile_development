package cn.gulupai.server.identity.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.identity.api.ProfileUpdateRequest;
import cn.gulupai.server.identity.api.ProfileView;
import cn.gulupai.server.identity.api.VisibilityUpdateRequest;
import cn.gulupai.server.identity.api.VisibilityView;
import cn.gulupai.server.identity.domain.ProfileData;
import cn.gulupai.server.identity.domain.VisibilityData;
import cn.gulupai.server.identity.persistence.ProfileRow;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    public ProfileService(UserRepository userRepository,
                          ObjectMapper objectMapper,
                          CurrentUserPolicy policy,
                          IdempotencyService idempotencyService,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ProfileView getProfile(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        return readProfile(principal.userId());
    }

    @Transactional(readOnly = true)
    public VisibilityView getVisibility(AuthenticatedUser principal) {
        policy.requireActiveBusinessUser(principal);
        UserRow user = requireUser(principal.userId());
        ProfileRow profile = userRepository.findProfile(user.id()).orElse(null);
        VisibilityData data = readVisibility(profile);
        return new VisibilityView(user.id(), data.worksAudience(),
                data.teacherSummaryVisible(), data.cooperationSummaryVisible(),
                profile == null ? 0 : profile.revision());
    }

    public ProfileView updateProfile(AuthenticatedUser principal, ProfileUpdateRequest request,
                                     String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        NormalizedProfile normalized = normalize(request);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request,
                        List.of(new IdempotencyRequest.ResourceRef("USER_PROFILE", principal.userId()))),
                ProfileView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> applyProfile(principal.userId(), normalized));
    }

    public VisibilityView updateVisibility(AuthenticatedUser principal, VisibilityUpdateRequest request,
                                           String idempotencyKey, String path) {
        policy.requireActiveBusinessUser(principal);
        VisibilityData data = new VisibilityData(request.worksAudience(),
                request.teacherSummaryVisible(), request.cooperationSummaryVisible());
        int expectedRevision = request.expectedRevision();
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request,
                        List.of(new IdempotencyRequest.ResourceRef("USER_PROFILE", principal.userId()))),
                VisibilityView.class,
                () -> policy.requireActiveBusinessUser(principal),
                () -> applyVisibility(principal.userId(), data, expectedRevision));
    }

    private ProfileView readProfile(String userId) {
        UserRow user = requireUser(userId);
        ProfileRow profile = userRepository.findProfile(userId).orElse(null);
        ProfileData data = readProfileData(profile);
        return new ProfileView(
                user.id(),
                user.displayName(),
                user.skills(),
                data.weeklyHours(),
                data.availability(),
                data.collaborationGoal(),
                profile == null ? "" : profile.intro(),
                profile == null ? 0 : profile.revision());
    }

    private ProfileView applyProfile(String userId, NormalizedProfile normalized) {
        UserRow user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        ProfileRow profile = userRepository.findProfileForUpdate(userId).orElse(null);
        int beforeRevision = profile == null ? 0 : profile.revision();
        if (beforeRevision != normalized.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }

        ProfileData data = new ProfileData(normalized.weeklyHours(),
                normalized.availability(), normalized.collaborationGoal());
        String profileJson = writeJson(data);
        int afterRevision;
        if (profile == null) {
            userRepository.insertProfile(userId, normalized.intro(), profileJson,
                    writeJson(VisibilityData.defaults()));
            afterRevision = 1;
        } else {
            userRepository.updateProfileFields(userId, normalized.intro(), profileJson);
            afterRevision = beforeRevision + 1;
        }

        if (!user.displayName().equals(normalized.displayName())
                || !user.skills().equals(normalized.skills())) {
            int updated = userRepository.updateBasics(userId, normalized.displayName(),
                    writeJson(normalized.skills()), user.revision());
            if (updated != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
        }

        auditService.record(userId, "PROFILE_UPDATE", "USER_PROFILE", userId,
                beforeRevision, afterRevision, null);
        return new ProfileView(userId, normalized.displayName(), normalized.skills(),
                normalized.weeklyHours(), normalized.availability(), normalized.collaborationGoal(),
                normalized.intro(), afterRevision);
    }

    private VisibilityView applyVisibility(String userId, VisibilityData data, int expectedRevision) {
        UserRow user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
        ProfileRow profile = userRepository.findProfileForUpdate(userId).orElse(null);
        int beforeRevision = profile == null ? 0 : profile.revision();
        if (beforeRevision != expectedRevision) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        int afterRevision;
        if (profile == null) {
            userRepository.insertProfile(userId, "", writeJson(ProfileData.defaults()), writeJson(data));
            afterRevision = 1;
        } else {
            userRepository.updateVisibilityFields(userId, writeJson(data));
            afterRevision = beforeRevision + 1;
        }
        auditService.record(userId, "VISIBILITY_UPDATE", "USER_PROFILE", userId,
                beforeRevision, afterRevision, null);
        return new VisibilityView(userId, data.worksAudience(),
                data.teacherSummaryVisible(), data.cooperationSummaryVisible(), afterRevision);
    }

    private UserRow requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ApiCode.SESSION_EXPIRED));
    }

    private ProfileData readProfileData(ProfileRow profile) {
        if (profile == null || profile.profileJson() == null || profile.profileJson().isBlank()) {
            return ProfileData.defaults();
        }
        try {
            ProfileData data = objectMapper.readValue(profile.profileJson(), ProfileData.class);
            return new ProfileData(
                    data.weeklyHours(),
                    data.availability() == null ? "READY" : data.availability(),
                    data.collaborationGoal() == null ? "" : data.collaborationGoal());
        } catch (Exception ex) {
            throw new IllegalStateException("user_profile.profile_json 无法解析", ex);
        }
    }

    private VisibilityData readVisibility(ProfileRow profile) {
        if (profile == null || profile.visibilityJson() == null || profile.visibilityJson().isBlank()) {
            return VisibilityData.defaults();
        }
        try {
            VisibilityData data = objectMapper.readValue(profile.visibilityJson(), VisibilityData.class);
            return new VisibilityData(
                    data.worksAudience() == null ? "PRIVATE" : data.worksAudience(),
                    data.teacherSummaryVisible(),
                    data.cooperationSummaryVisible());
        } catch (Exception ex) {
            throw new IllegalStateException("user_profile.visibility_json 无法解析", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化本人资料", ex);
        }
    }

    private NormalizedProfile normalize(ProfileUpdateRequest request) {
        String displayName = request.displayName().strip();
        if (displayName.isEmpty()) {
            throw ApiException.validation("显示名不能为空");
        }
        if (displayName.length() > 80) {
            throw ApiException.validation("显示名不能超过80个字符");
        }
        if (request.skills().size() > 12) {
            throw ApiException.validation("技能最多12项");
        }
        List<String> skills = new ArrayList<>();
        for (String skill : request.skills()) {
            String value = skill == null ? "" : skill.strip();
            if (value.isEmpty()) {
                throw ApiException.validation("技能不能为空");
            }
            if (value.length() > 80) {
                throw ApiException.validation("单个技能不能超过80个字符");
            }
            skills.add(value);
        }
        int weeklyHours = request.weeklyHours();
        if (weeklyHours < 0 || weeklyHours > 168) {
            throw ApiException.validation("每周可投入小时必须在0～168之间");
        }
        String availability = request.availability() == null ? "" : request.availability().strip();
        String collaborationGoal = request.collaborationGoal().strip();
        if (collaborationGoal.isEmpty()) {
            throw ApiException.validation("协作目标不能为空");
        }
        String intro = request.intro() == null ? "" : request.intro().strip();
        if (availability.length() > 2000 || collaborationGoal.length() > 2000 || intro.length() > 2000) {
            throw ApiException.validation("资料文本不能超过2000个字符");
        }
        return new NormalizedProfile(request.expectedRevision(), displayName, skills,
                weeklyHours, availability, collaborationGoal, intro);
    }

    private record NormalizedProfile(
            int expectedRevision,
            String displayName,
            List<String> skills,
            int weeklyHours,
            String availability,
            String collaborationGoal,
            String intro
    ) {
        private NormalizedProfile {
            skills = List.copyOf(skills);
        }
    }
}
