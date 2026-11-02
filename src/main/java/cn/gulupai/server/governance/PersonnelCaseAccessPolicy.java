package cn.gulupai.server.governance;

import cn.gulupai.server.governance.GovRepository.CaseRow;
import cn.gulupai.server.governance.GovRepository.HandoffRow;
import cn.gulupai.server.governance.GovRepository.MaterialRow;
import cn.gulupai.server.teaching.application.CourseAuthorityService;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 人员事项 case 级权限策略（D01）。
 *
 * <p>这里把四种概念分开，调用方必须按用途选择，不能再用一个含混的
 * “hasQualification” 同时承担读取、办理、回应和收回判断：
 * <ol>
 *   <li>有效教师身份：账号有效、已核验、仍具 TEACHER 角色，且与 case 广场同校；</li>
 *   <li>本 case 处理资格：有效教师 + 无回避 +（当前任课教师 或 本 case 有效授权）；</li>
 *   <li>本 case 当前处理人：具备资格且 {@code case.assigneeId == userId}；</li>
 *   <li>本 case 读取资格：本人材料、指定交接必要摘要、当前处理人全案、当前任课教师监督读取。</li>
 * </ol>
 *
 * <p>资格不等于接任：管理员授予的单 case 资格必须经本人接受交接后，才成为 assignee。
 * 学生项目 membership 是回避条件，不能作为处理资格。所有判断在请求/事务内实时查询，
 * 不缓存到下一次请求替代核权。
 */
@Service
public class PersonnelCaseAccessPolicy {

    private final GovRepository governance;
    private final CourseAuthorityService authority;
    private final Clock clock;

    public PersonnelCaseAccessPolicy(GovRepository governance, CourseAuthorityService authority,
                                     Clock clock) {
        this.governance = governance;
        this.authority = authority;
        this.clock = clock;
    }

    /** 当前有效教师身份；不含本 case 资格、不含任课关系或授权。 */
    public boolean isEligibleTeacher(CaseRow row, String userId) {
        return governance.eligibleTeacher(row.squareId(), userId);
    }

    /** 该广场当前任课教师，且在使用时仍满足有效教师身份。 */
    public boolean isSupervisingTeacher(CaseRow row, String userId) {
        return isSupervisingTeacher(row.squareId(), userId);
    }

    public boolean isSupervisingTeacher(String squareId, String userId) {
        return governance.eligibleTeacher(squareId, userId) && authority.isTeacher(squareId, userId);
    }

    public boolean activeGrant(String caseId, String userId) {
        return governance.activeGrant(caseId, userId, clock.instant()).isPresent();
    }

    public boolean recused(String projectId, String userId) {
        return governance.projectMember(projectId, userId);
    }

    public boolean activeVerifiedAccount(String userId) {
        return governance.activeVerifiedAccount(userId);
    }

    /** 本 case 处理资格：有效教师、无回避，并且仍是当前任课教师或有本 case 未撤销未到期授权。 */
    public boolean hasEffectiveCaseQualification(CaseRow row, String userId) {
        return isEligibleTeacher(row, userId)
                && !recused(row.projectId(), userId)
                && (authority.isTeacher(row.squareId(), userId) || activeGrant(row.id(), userId));
    }

    /** 只有当前 assignee 是“当前处理人”；资格与已结案都不改变这个事实。 */
    public boolean isCurrentHandler(CaseRow row, String userId) {
        return !"CLOSED".equals(row.status()) && userId.equals(row.assigneeId());
    }

    /** 正常办理：当前处理人 + 完整有效资格。 */
    public boolean canHandleCase(CaseRow row, String userId) {
        return isCurrentHandler(row, userId) && hasEffectiveCaseQualification(row, userId);
    }

    /** 已结案的当前负责人仍保留历史读取权，但不能再办理。 */
    public boolean isQualifiedAssignee(CaseRow row, String userId) {
        return userId.equals(row.assigneeId()) && hasEffectiveCaseQualification(row, userId);
    }

    /**
     * 全案读取：当前任课教师监督读取，或已成为当前处理人的合法接续教师。
     * 仅有 case 授权但未接任的候选不能借资格读取全案。
     */
    public boolean canReadCase(CaseRow row, String userId) {
        if (!activeVerifiedAccount(userId) || recused(row.projectId(), userId)) {
            return false;
        }
        return isSupervisingTeacher(row, userId) || isQualifiedAssignee(row, userId);
    }

    /** 本人材料可读；教师侧只在具备全案读取资格时读取材料正文。 */
    public boolean canReadMaterial(CaseRow row, MaterialRow material, String userId) {
        if (userId.equals(material.recipientId())) {
            return activeVerifiedAccount(userId);
        }
        return canReadCase(row, userId);
    }

    /**
     * 交接读取：PENDING 只给“指定接续人且当前仍有本 case 资格”或“当前处理人”；
     * 终态按当前本 case 读取资格，不因历史 from/to 永久保留。
     */
    public boolean canReadHandoff(CaseRow row, HandoffRow handoff, String userId) {
        if ("PENDING".equals(handoff.status())) {
            if (userId.equals(handoff.toUserId())) {
                return canReceiveHandoff(row, userId) && handoff.sourceRevision() == row.revision();
            }
            if (userId.equals(handoff.fromUserId())) {
                return canHandleCase(row, userId);
            }
            return false;
        }
        return canReadCase(row, userId);
    }

    public boolean canReceiveHandoff(CaseRow row, String toUserId) {
        return !"CLOSED".equals(row.status())
                && !toUserId.equals(row.assigneeId())
                && hasEffectiveCaseQualification(row, toUserId);
    }

    /**
     * 回应交接：仍 PENDING、来源版本未变、本人是指定接续人且仍有资格，
     * 同时发起人仍是当前处理人并有资格，避免“校验后被撤权仍完成接任”。
     */
    public boolean canRespondHandoff(CaseRow row, HandoffRow handoff, String userId) {
        return "PENDING".equals(handoff.status())
                && handoff.revision() >= 1
                && handoff.sourceRevision() == row.revision()
                && userId.equals(handoff.toUserId())
                && canReceiveHandoff(row, userId)
                && row.assigneeId() != null
                && row.assigneeId().equals(handoff.fromUserId())
                && canHandleCase(row, row.assigneeId());
    }

    /** 发起交接：只有当前处理人可发起。 */
    public boolean canInitiateHandoff(CaseRow row, String userId) {
        return canHandleCase(row, userId);
    }

    /** 关联人员决定：必须由本 case 当前合法处理人办理，监督读取不能代办。 */
    public boolean canMakePersonnelDecision(CaseRow row, String userId) {
        return canHandleCase(row, userId);
    }

    /**
     * 收回失权事项：发起者必须是当前任课教师；事项未结案且已有处理人；
     * 原处理人必须已经不能正常办理（停用、撤权、角色/核验失效、回避或授权到期）。
     */
    public boolean canRecoverCase(CaseRow row, String currentTeacherId) {
        if ("CLOSED".equals(row.status()) || row.assigneeId() == null) {
            return false;
        }
        if (recused(row.projectId(), currentTeacherId) || !isSupervisingTeacher(row, currentTeacherId)) {
            return false;
        }
        return !canHandleCase(row, row.assigneeId());
    }
}
