package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.platform.domain.PlatformCapability;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 首位超管只从受控 CLI 创建，不在启动时自动生成，也不提供匿名开户。
 * 重复执行同一目标不会新增第二条有效授权。
 */
@Service
public class PlatformSuperAdminService {

    private final PlatformRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public PlatformSuperAdminService(PlatformRepository repository, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public InitialGrant grantInitial(String actorId, String userId, String reason, boolean dryRun) {
        AdminTexts.requireId(userId, "userId");
        String note = AdminTexts.requiredText(reason, "reason");
        repository.lockAdminConstraint();
        var existing = repository.findActiveGrant(userId);
        if (existing.isPresent()) {
            return alreadyActiveOrConflict(userId, existing.get(), dryRun);
        }
        PlatformRepository.ManagedUser user = repository.lockUser(userId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND, "目标账号不存在"));
        assertEligible(user);
        if (dryRun) {
            return new InitialGrant(userId, "", false, true);
        }
        String grantId = repository.insertGrant(userId, actorId, clock.instant(), true, null);
        auditService.record(actorId, "SUPER_ADMIN_GRANTED", "PLATFORM_ADMIN_GRANT", grantId,
                null, 1, note);
        return new InitialGrant(userId, grantId, false, false);
    }

    /**
     * 存在 ACTIVE 授权时不能直接报 already-active：必须核对账号、角色、能力和全平台范围是否仍与
     * “首位/全平台管理员”这一承诺一致。残缺状态（缺角色、缺能力、只有学校范围、账号不可用）
     * 是明确冲突，交人工受控修复，不在这里自动补齐或静默升级。
     */
    private InitialGrant alreadyActiveOrConflict(String userId, PlatformRepository.ActiveGrant grant, boolean dryRun) {
        PlatformRepository.ManagedUser user = repository.lockUser(userId)
                .orElseThrow(() -> new ApiException(ApiCode.RESOURCE_NOT_FOUND, "目标账号不存在"));
        boolean roleHeld = repository.rolesOf(userId).contains(Role.SUPER_ADMIN.name());
        boolean capabilitiesComplete = grant.capabilities().containsAll(PlatformCapability.ALL);
        boolean platformScopeOnly = grant.platform() && grant.schools().isEmpty();
        if (!roleHeld || !capabilitiesComplete || !platformScopeOnly) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION, conflictMessage(user.loginId(),
                    roleHeld, capabilitiesComplete, platformScopeOnly));
        }
        assertEligible(user);
        return new InitialGrant(userId, grant.id(), true, dryRun);
    }

    private static String conflictMessage(String loginId, boolean roleHeld, boolean capabilitiesComplete,
                                          boolean platformScopeOnly) {
        List<String> missing = new ArrayList<>();
        if (!roleHeld) {
            missing.add("SUPER_ADMIN 角色");
        }
        if (!capabilitiesComplete) {
            missing.add("完整平台能力");
        }
        if (!platformScopeOnly) {
            missing.add("全平台范围");
        }
        return "账号 " + loginId + " 已有 ACTIVE 平台授权，但缺少" + String.join("、", missing)
                + "。这属于需要人工受控修复的残缺状态，初始化不会自动补齐或把学校授权升级为全平台；"
                + "请先核对 V031 授权、能力、范围与角色记录，再走受控授权修复流程。";
    }

    public static void assertEligible(PlatformRepository.ManagedUser user) {
        if (user.disabled() || !"ACTIVE".equals(user.accountStatus())
                || !"VERIFIED".equals(user.identityStatus())
                || !"VERIFIED_ACTIVE".equals(user.eligibility())
                || !user.hasPassword()) {
            throw new ApiException(ApiCode.INVALID_STATE_TRANSITION,
                    "只能给已激活、已核验且完成安全凭据设置的独立账号授予超级管理员");
        }
    }

    public record InitialGrant(String userId, String grantId, boolean alreadyActive, boolean dryRun) {
        public String describe() {
            if (dryRun && alreadyActive) {
                return "dry-run already-active user=" + userId + " grant=" + grantId;
            }
            if (dryRun) {
                return "dry-run would-grant user=" + userId;
            }
            if (alreadyActive) {
                return "already-active user=" + userId + " grant=" + grantId;
            }
            return "granted user=" + userId + " grant=" + grantId;
        }
    }
}
