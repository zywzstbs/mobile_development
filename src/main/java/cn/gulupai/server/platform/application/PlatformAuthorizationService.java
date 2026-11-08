package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.platform.domain.PlatformAction;
import cn.gulupai.server.platform.domain.PlatformCapability;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 每次管理访问都重查会话、账号、角色、有效授权、能力和范围。角色行本身不能放行。
 */
@Service
public class PlatformAuthorizationService {

    private final CurrentUserPolicy policy;
    private final PlatformRepository repository;
    private final Clock clock;

    public PlatformAuthorizationService(CurrentUserPolicy policy, PlatformRepository repository, Clock clock) {
        this.policy = policy;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 访问判定只依据数据库当前事实：账号可用、会话仍有效、SUPER_ADMIN 角色行存在、
     * 有效授权含该能力，之后才按授权范围判断目标。{@code principal.roles} 是请求开始时
     * 的快照，不能单独放行；锁外调用只用于快速拒绝，正式写入必须在锁内重新调用。
     */
    public Access require(AuthenticatedUser principal, PlatformCapability capability) {
        policy.requireActiveBusinessUser(principal);
        if (!repository.activeSession(principal.sessionId(), principal.userId(), clock.instant())) {
            throw new ApiException(ApiCode.SESSION_EXPIRED);
        }
        if (!repository.rolesOf(principal.userId()).contains(Role.SUPER_ADMIN.name())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        PlatformRepository.ActiveGrant grant = repository.findActiveGrant(principal.userId())
                .orElseThrow(() -> new ApiException(ApiCode.FORBIDDEN));
        if (!grant.capabilities().contains(capability.wireName())) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
        return new Access(principal, grant);
    }

    public void requireSchool(Access access, String schoolId) {
        if (!access.grant().covers(schoolId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    /**
     * 再认证签发前核对目标存在、范围和当前版本。写入锁内会再查一次。
     */
    public void prepare(Access access, AdminCommands.CanonicalCommand command) {
        PlatformAction action = command.action();
        switch (action) {
            case ACCOUNT_STATUS_CHANGE, PASSWORD_RESET_REQUEST -> {
                PlatformRepository.ManagedUser user = repository.findUser(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, user.schoolId());
                if (action.revisionRequired() && user.revision() != command.expectedRevision()) {
                    throw new ApiException(ApiCode.REVISION_CONFLICT);
                }
            }
            case TEACHER_APPLICATION_DECIDE -> {
                PlatformRepository.TargetRef target = repository.findApplication(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, target.schoolId());
                if (access.actorId().equals(target.userId())) {
                    throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "不能审核自己的申请");
                }
                if (target.revision() != command.expectedRevision()) {
                    throw new ApiException(ApiCode.REVISION_CONFLICT);
                }
            }
            case COURSE_CLAIM_DECIDE -> {
                PlatformRepository.TargetRef target = repository.findClaimTarget(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, target.schoolId());
                if (access.actorId().equals(target.userId())) {
                    throw new ApiException(ApiCode.SELF_REVIEW_FORBIDDEN, "不能审核自己的认领申请");
                }
                if (target.revision() != command.expectedRevision()) {
                    throw new ApiException(ApiCode.REVISION_CONFLICT);
                }
            }
            case ADMIN_GRANT_REVOKE -> {
                PlatformRepository.TargetRef target = repository.findClassGrantTarget(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, target.schoolId());
                if (target.revision() != command.expectedRevision()) {
                    throw new ApiException(ApiCode.REVISION_CONFLICT);
                }
            }
            case SUPER_ADMIN_GRANT -> {
                if (access.actorId().equals(command.targetId())) {
                    throw new ApiException(ApiCode.FORBIDDEN, "不能给自己授予超级管理员");
                }
                PlatformRepository.ManagedUser user = repository.findUser(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, user.schoolId());
                if (repository.findActiveGrant(user.id()).isPresent()) {
                    throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, "该账号已有有效的超级管理员授权");
                }
            }
            case SUPER_ADMIN_REVOKE -> {
                PlatformRepository.ManagedUser user = repository.findUser(command.targetId())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                requireSchool(access, user.schoolId());
                PlatformRepository.ActiveGrant grant = repository.findActiveGrant(user.id())
                        .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND));
                if (grant.platform() && !access.grant().platform()) {
                    throw new ApiException(ApiCode.FORBIDDEN);
                }
                if (grant.revision() != command.expectedRevision()) {
                    throw new ApiException(ApiCode.REVISION_CONFLICT);
                }
            }
        }
    }

    public record Access(AuthenticatedUser principal, PlatformRepository.ActiveGrant grant) {
        public String actorId() {
            return principal.userId();
        }

        public String sessionId() {
            return principal.sessionId();
        }
    }
}
