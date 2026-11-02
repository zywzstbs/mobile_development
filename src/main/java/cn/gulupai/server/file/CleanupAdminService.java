package cn.gulupai.server.file;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.file.FileRepository.PlannedCleanup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 清理计划只读预览和撤销。dry-run 不改状态、不删字节。
 */
@Service
public class CleanupAdminService {

    private final FileRepository files;
    private final AuditService audit;
    private final Clock clock;

    public CleanupAdminService(FileRepository files, AuditService audit, Clock clock) {
        this.files = files;
        this.audit = audit;
        this.clock = clock;
    }

    public List<PlanPreview> dryRun() {
        Instant now = clock.instant();
        List<PlanPreview> previews = new ArrayList<>();
        for (PlannedCleanup plan : files.plannedCleanups()) {
            String decision;
            if (plan.executeAfter().isAfter(now)) {
                decision = "WAITING";
            } else if (files.referenced(plan.resourceId())) {
                decision = "BLOCKED_BY_REFERENCE";
            } else {
                decision = "WOULD_DELETE";
            }
            previews.add(new PlanPreview(plan.id(), plan.resourceId(), plan.reason(),
                    plan.executeAfter(), decision));
        }
        return previews;
    }

    @Transactional
    public void cancel(String planId, String reason, String actorId) {
        if (planId == null || planId.isBlank()) {
            throw new IllegalArgumentException("缺少清理计划编号");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("取消原因必须填写且不超过500字符");
        }
        int updated = files.cancelPlan(planId, clock.instant());
        if (updated != 1) {
            throw new IllegalArgumentException("清理计划不存在或已不处于可取消状态");
        }
        audit.record(actorId, "CLEANUP_CANCEL", "CLEANUP_PLAN", planId, null, null, reason);
    }

    public record PlanPreview(String id, String resourceId, String reason, Instant executeAfter, String decision) {
        public String line() {
            return "id=" + id + " resource=" + resourceId + " reason=" + reason
                    + " executeAfter=" + executeAfter + " decision=" + decision;
        }
    }
}
