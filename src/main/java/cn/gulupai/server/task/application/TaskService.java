package cn.gulupai.server.task.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.idempotency.IdempotencyRequest;
import cn.gulupai.server.common.idempotency.IdempotencyService;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.common.security.CurrentUserPolicy;
import cn.gulupai.server.course.persistence.SquareRepository;
import cn.gulupai.server.course.persistence.SquareRow;
import cn.gulupai.server.delivery.DeliveryService;
import cn.gulupai.server.dice.persistence.DiceRepository;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.task.api.TaskRequests;
import cn.gulupai.server.task.api.TaskViews;
import cn.gulupai.server.task.persistence.TaskRepository;
import cn.gulupai.server.task.persistence.TaskRepository.DependencyRow;
import cn.gulupai.server.task.persistence.TaskRepository.TaskRow;
import cn.gulupai.server.team.api.TeamViews;
import cn.gulupai.server.team.persistence.ProjectMemberRow;
import cn.gulupai.server.team.persistence.ProjectRow;
import cn.gulupai.server.team.persistence.StageRow;
import cn.gulupai.server.team.persistence.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 普通任务在这里维护。正式任务创建后进入待确认约定，组长发布不会自动签到。
 * 锁顺序：project，再 task id 升序。
 */
@Service
public class TaskService {

    static final int TASK_LIMIT = 200;
    private static final Set<String> PALETTE = Set.of(
            "#236B56", "#355F9B", "#79518C", "#9C4F2E",
            "#466E39", "#82565E", "#6A5C37", "#3F6C73");

    private final TaskRepository taskRepo;
    private final TeamRepository teamRepo;
    private final SquareRepository squareRepository;
    private final UserRepository userRepository;
    private final DeliveryService deliveryService;
    private final CurrentUserPolicy policy;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final Clock clock;

    private final DiceRepository diceRepository;

    public TaskService(TaskRepository taskRepo, TeamRepository teamRepo, SquareRepository squareRepository,
                       UserRepository userRepository, DeliveryService deliveryService, CurrentUserPolicy policy,
                       IdempotencyService idempotencyService, AuditService auditService, Clock clock,
                       DiceRepository diceRepository) {
        this.taskRepo = taskRepo;
        this.teamRepo = teamRepo;
        this.squareRepository = squareRepository;
        this.userRepository = userRepository;
        this.deliveryService = deliveryService;
        this.policy = policy;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.clock = clock;
        this.diceRepository = diceRepository;
    }

    @Transactional(readOnly = true)
    public TaskViews.BoardView board(AuthenticatedUser principal, String projectId) {
        ProjectRow project = requireMemberProject(principal, projectId);
        return new TaskViews.BoardView(project.revision(), stages(projectId), taskViews(principal, projectId),
                members(projectId));
    }

    @Transactional(readOnly = true)
    public List<TaskViews.TaskView> tasks(AuthenticatedUser principal, String projectId) {
        requireMemberProject(principal, projectId);
        return taskViews(principal, projectId);
    }

    @Transactional(readOnly = true)
    public TaskViews.TaskView task(AuthenticatedUser principal, String taskId) {
        policy.requireStudent(principal);
        TaskRow task = taskRepo.findTask(taskId).orElseThrow(ApiException::notFound);
        requireMemberProject(principal, task.projectId());
        return taskView(principal, task);
    }

    public TaskViews.TaskView create(AuthenticatedUser principal, String projectId,
                                    TaskRequests.CreateTaskRequest request,
                                    String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TaskViews.TaskView.class,
                () -> policy.requireStudent(principal),
                () -> createOnce(principal, projectId, request));
    }

    public TaskViews.TaskView moveStage(AuthenticatedUser principal, String taskId,
                                       TaskRequests.MoveStageRequest request,
                                       String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PATCH", path, idempotencyKey, request, List.of()),
                TaskViews.TaskView.class,
                () -> policy.requireStudent(principal),
                () -> moveStageOnce(principal, taskId, request));
    }

    public TaskViews.TaskView claim(AuthenticatedUser principal, String taskId,
                                   TaskRequests.ClaimRequest request,
                                   String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TaskViews.TaskView.class,
                () -> policy.requireStudent(principal),
                () -> claimOnce(principal, taskId, request));
    }

    public TaskViews.TaskView start(AuthenticatedUser principal, String taskId,
                                   TaskRequests.StartRequest request,
                                   String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "POST", path, idempotencyKey, request, List.of()),
                TaskViews.TaskView.class,
                () -> policy.requireStudent(principal),
                () -> startOnce(principal, taskId, request));
    }

    public TaskViews.ColorResult color(AuthenticatedUser principal, String projectId,
                                      TaskRequests.ColorRequest request,
                                      String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                TaskViews.ColorResult.class,
                () -> policy.requireStudent(principal),
                () -> colorOnce(principal, projectId, request));
    }

    public TaskViews.StagesResult replaceStages(AuthenticatedUser principal, String projectId,
                                               TaskRequests.ReplaceStagesRequest request,
                                               String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                TaskViews.StagesResult.class,
                () -> policy.requireStudent(principal),
                () -> replaceStagesOnce(principal, projectId, request));
    }

    public TaskViews.TaskView replaceDependencies(AuthenticatedUser principal, String taskId,
                                                 TaskRequests.ReplaceDependenciesRequest request,
                                                 String idempotencyKey, String path) {
        policy.requireStudent(principal);
        return idempotencyService.execute(
                new IdempotencyRequest(principal, "PUT", path, idempotencyKey, request, List.of()),
                TaskViews.TaskView.class,
                () -> policy.requireStudent(principal),
                () -> replaceDependenciesOnce(principal, taskId, request));
    }

    private TaskViews.TaskView createOnce(AuthenticatedUser principal, String projectId,
                                         TaskRequests.CreateTaskRequest request) {
        ProjectRow project = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireLeader(project, principal.userId());
        if ("ASSESSED".equals(request.mode())) {
            return createAssessed(principal, project, projectId, request);
        }
        if (!"ORDINARY".equals(request.mode())) {
            throw ApiException.validation("任务模式无效");
        }
        if (!"ACTIVE".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        if (request.assessedPlan() != null) {
            throw ApiException.validation("普通任务不能携带assessedPlan");
        }
        if (request.dueAt() == null) {
            throw ApiException.validation("普通任务必须提供dueAt");
        }
        if (request.criteria() == null || request.criteria().isEmpty()) {
            throw ApiException.validation("普通任务必须提供criteria");
        }
        if (request.dependencies() == null) {
            throw ApiException.validation("必须提供dependencies");
        }
        if (teamRepo.stages(projectId).stream().noneMatch(stage -> stage.id().equals(request.stageId()))) {
            throw ApiException.validation("阶段不属于当前项目");
        }
        if (taskRepo.countTasks(projectId) >= TASK_LIMIT) {
            throw ApiException.validation("项目任务不能超过200");
        }
        String taskId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        taskRepo.insertTask(new TaskRow(taskId, projectId, request.stageId(), request.title().strip(),
                request.description() == null ? "" : request.description().strip(), "ORDINARY", null,
                request.dueAt(), "TODO", 1, now), now);
        int position = 0;
        for (TaskRequests.CriterionInput criterion : request.criteria()) {
            taskRepo.insertCriterion(UUID.randomUUID().toString(), taskId, criterion.text().strip(), position++);
        }
        List<DependencyRow> dependencies = normalizeDependencies(projectId, taskId, request.dependencies(), List.of(),
                "TODO");
        taskRepo.replaceDependencies(taskId, dependencies);
        ensureAcyclic(projectId);
        teamRepo.bumpRevision(projectId);
        auditService.record(principal.userId(), "TASK_CREATE", "TASK", taskId, null, 1, null);
        return taskView(principal, taskRepo.findTask(taskId).orElseThrow());
    }

    private TaskViews.TaskView moveStageOnce(AuthenticatedUser principal, String taskId,
                                            TaskRequests.MoveStageRequest request) {
        TaskRow current = taskRepo.findTask(taskId).orElseThrow(ApiException::notFound);
        ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireLeader(project, principal.userId());
        if (project.revision() != request.expectedProjectRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        TaskRow locked = taskRepo.lockTask(taskId).orElseThrow(ApiException::notFound);
        if (locked.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (teamRepo.stages(project.id()).stream().noneMatch(stage -> stage.id().equals(request.stageId()))) {
            throw ApiException.validation("阶段不属于当前项目");
        }
        String previousStatus = locked.status();
        if (taskRepo.moveStage(taskId, locked.revision(), request.stageId(), clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        TaskRow updated = taskRepo.findTask(taskId).orElseThrow();
        if (!previousStatus.equals(updated.status())) {
            throw new IllegalStateException("移动阶段不能改变任务状态");
        }
        teamRepo.bumpRevision(project.id());
        auditService.record(principal.userId(), "TASK_MOVE_STAGE", "TASK", taskId,
                locked.revision(), locked.revision() + 1, null);
        return taskView(principal, updated);
    }

    private TaskViews.TaskView claimOnce(AuthenticatedUser principal, String taskId,
                                        TaskRequests.ClaimRequest request) {
        TaskRow current = taskRepo.findTask(taskId).orElseThrow(ApiException::notFound);
        ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireMember(project.id(), principal.userId());
        if (!"ACTIVE".equals(project.status()) && !"CLOSING".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        TaskRow locked = taskRepo.lockTask(taskId).orElseThrow(ApiException::notFound);
        if (locked.revision() != request.expectedRevision() || !"TODO".equals(locked.status())
                || !"ORDINARY".equals(locked.mode())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (locked.ownerId() != null) {
            throw new ApiException(ApiCode.TASK_ALREADY_CLAIMED);
        }
        requireBeforeStart(locked.id());
        boolean startNow = Boolean.TRUE.equals(request.startNow());
        String status = startNow ? "IN_PROGRESS" : "TODO";
        if (taskRepo.claim(taskId, locked.revision(), principal.userId(), status, clock.instant()) != 1) {
            throw new ApiException(ApiCode.TASK_ALREADY_CLAIMED);
        }
        auditService.record(principal.userId(), "TASK_CLAIM", "TASK", taskId,
                locked.revision(), locked.revision() + 1, null);
        diceRepository.markClaimOutcome(taskId, principal.userId());
        return taskView(principal, taskRepo.findTask(taskId).orElseThrow());
    }

    private TaskViews.TaskView startOnce(AuthenticatedUser principal, String taskId,
                                        TaskRequests.StartRequest request) {
        TaskRow current = taskRepo.findTask(taskId).orElseThrow(ApiException::notFound);
        ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireMember(project.id(), principal.userId());
        if (!"ACTIVE".equals(project.status()) && !"CLOSING".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        TaskRow locked = taskRepo.lockTask(taskId).orElseThrow(ApiException::notFound);
        if (locked.revision() != request.expectedRevision() || !"TODO".equals(locked.status())) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        if (taskRepo.handoverBlocked(locked.id())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY, "交接尚未重签，不能开始或提交");
        }
        requireBeforeStart(locked.id());
        if ("ASSESSED".equals(locked.mode())) {
            if (!deliveryService.canStart(principal.userId(), locked)) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            if (taskRepo.setStatus(taskId, locked.revision(), "IN_PROGRESS", clock.instant()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
        } else {
            if (!principal.userId().equals(locked.ownerId())) {
                throw new ApiException(ApiCode.FORBIDDEN);
            }
            if (taskRepo.start(taskId, locked.revision(), principal.userId(), clock.instant()) != 1) {
                throw new ApiException(ApiCode.RESOURCE_CHANGED);
            }
        }
        auditService.record(principal.userId(), "TASK_START", "TASK", taskId,
                locked.revision(), locked.revision() + 1, null);
        return taskView(principal, taskRepo.findTask(taskId).orElseThrow());
    }

    private TaskViews.ColorResult colorOnce(AuthenticatedUser principal, String projectId,
                                           TaskRequests.ColorRequest request) {
        ProjectRow project = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireMember(projectId, principal.userId());
        String color = request.color().trim().toUpperCase(Locale.ROOT);
        if (!PALETTE.contains(color)) {
            throw ApiException.validation("颜色不在固定调色板内");
        }
        if (taskRepo.updateColor(projectId, principal.userId(), color, request.expectedRevision(),
                clock.instant()) != 1) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        auditService.record(principal.userId(), "PROJECT_COLOR", "MEMBERSHIP", projectId,
                request.expectedRevision(), request.expectedRevision() + 1, null);
        return new TaskViews.ColorResult(color, request.expectedRevision() + 1);
    }

    private TaskViews.StagesResult replaceStagesOnce(AuthenticatedUser principal, String projectId,
                                                    TaskRequests.ReplaceStagesRequest request) {
        ProjectRow project = teamRepo.lockProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireLeader(project, principal.userId());
        if (!"ACTIVE".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        if (project.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        List<PreparedStage> prepared = prepareStages(projectId, request.stages());
        persistStages(projectId, prepared);
        teamRepo.bumpRevision(projectId);
        auditService.record(principal.userId(), "PROJECT_STAGES", "PROJECT", projectId,
                request.expectedRevision(), request.expectedRevision() + 1, null);
        ProjectRow updated = teamRepo.findProject(projectId).orElseThrow();
        return new TaskViews.StagesResult(updated.revision(), stages(projectId));
    }

    private TaskViews.TaskView replaceDependenciesOnce(AuthenticatedUser principal, String taskId,
                                                      TaskRequests.ReplaceDependenciesRequest request) {
        TaskRow current = taskRepo.findTask(taskId).orElseThrow(ApiException::notFound);
        ProjectRow project = teamRepo.lockProject(current.projectId()).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireLeader(project, principal.userId());
        TaskRow locked = taskRepo.lockTask(taskId).orElseThrow(ApiException::notFound);
        if (locked.revision() != request.expectedRevision()) {
            throw new ApiException(ApiCode.RESOURCE_CHANGED);
        }
        List<DependencyRow> existing = taskRepo.dependenciesOf(taskId);
        List<DependencyRow> next = normalizeDependencies(project.id(), taskId, request.dependencies(), existing,
                locked.status());
        taskRepo.replaceDependencies(taskId, next);
        ensureAcyclic(project.id());
        taskRepo.moveStage(taskId, locked.revision(), locked.stageId(), clock.instant());
        auditService.record(principal.userId(), "TASK_DEPENDENCIES", "TASK", taskId,
                locked.revision(), locked.revision() + 1, null);
        return taskView(principal, taskRepo.findTask(taskId).orElseThrow());
    }

    private List<DependencyRow> normalizeDependencies(String projectId, String taskId,
                                                     List<TaskRequests.DependencyInput> inputs,
                                                     List<DependencyRow> existing, String status) {
        Set<String> seen = new HashSet<>();
        Set<String> oldStartGates = new HashSet<>();
        for (DependencyRow row : existing) {
            if ("BEFORE_START".equals(row.gate())) {
                oldStartGates.add(row.dependsOnTaskId());
            }
        }
        List<DependencyRow> rows = new ArrayList<>();
        for (TaskRequests.DependencyInput input : inputs) {
            if (taskId.equals(input.taskId())) {
                throw ApiException.validation("任务不能依赖自己");
            }
            if (!seen.add(input.taskId())) {
                throw ApiException.validation("依赖任务不能重复");
            }
            TaskRow dependency = taskRepo.findTask(input.taskId()).orElseThrow(ApiException::notFound);
            if (!projectId.equals(dependency.projectId())) {
                throw ApiException.validation("不能依赖其他项目的任务");
            }
            if (!"TODO".equals(status) && "BEFORE_START".equals(input.gate())
                    && !oldStartGates.contains(input.taskId())) {
                throw ApiException.validation("开始后不能新增BEFORE_START门槛");
            }
            rows.add(new DependencyRow(taskId, input.taskId(), input.gate()));
        }
        return rows;
    }

    private void requireBeforeStart(String taskId) {
        for (DependencyRow dependency : taskRepo.dependenciesOf(taskId)) {
            if (!"BEFORE_START".equals(dependency.gate())) {
                continue;
            }
            TaskRow predecessor = taskRepo.findTask(dependency.dependsOnTaskId()).orElseThrow(ApiException::notFound);
            if (!"DONE".equals(predecessor.status())) {
                throw new ApiException(ApiCode.DEPENDENCY_BLOCKED);
            }
        }
    }

    private void ensureAcyclic(String projectId) {
        Map<String, List<String>> graph = new HashMap<>();
        for (DependencyRow dependency : taskRepo.dependenciesInProject(projectId)) {
            graph.computeIfAbsent(dependency.taskId(), key -> new ArrayList<>())
                    .add(dependency.dependsOnTaskId());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) {
            if (hasCycle(node, graph, visiting, visited)) {
                throw ApiException.validation("任务依赖不能成环");
            }
        }
    }

    private boolean hasCycle(String node, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (String next : graph.getOrDefault(node, List.of())) {
            if (hasCycle(next, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private List<PreparedStage> prepareStages(String projectId, List<TaskRequests.StageInput> inputs) {
        Map<String, StageRow> existing = new HashMap<>();
        for (StageRow stage : teamRepo.stages(projectId)) {
            existing.put(stage.id(), stage);
        }
        boolean anyOrder = inputs.stream().anyMatch(stage -> stage.order() != null);
        boolean allOrder = inputs.stream().allMatch(stage -> stage.order() != null);
        if (anyOrder && !allOrder) {
            throw ApiException.validation("使用order时每一行都必须提供");
        }
        List<TaskRequests.StageInput> ordered = new ArrayList<>(inputs);
        if (allOrder) {
            Set<Integer> orders = new HashSet<>();
            for (TaskRequests.StageInput stage : inputs) {
                if (!orders.add(stage.order())) {
                    throw ApiException.validation("order不能重复");
                }
            }
            for (int i = 0; i < inputs.size(); i++) {
                if (!orders.contains(i)) {
                    throw ApiException.validation("order必须是0到n-1的连续整数");
                }
            }
            ordered.sort((left, right) -> Integer.compare(left.order(), right.order()));
        }
        Set<String> seenIds = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        List<PreparedStage> prepared = new ArrayList<>();
        int position = 0;
        for (TaskRequests.StageInput input : ordered) {
            String id = input.id() == null ? "" : input.id().strip();
            String clientKey = input.clientKey() == null ? "" : input.clientKey().strip();
            if (!clientKey.isEmpty() && !seenKeys.add(clientKey)) {
                throw ApiException.validation("同一次请求的clientKey不能重复");
            }
            String name = normalizeStageName(input.name());
            if (!seenNames.add(name)) {
                throw ApiException.validation("阶段名称不能重复");
            }
            String stageId;
            boolean created;
            if (!id.isEmpty()) {
                if (!existing.containsKey(id) || !seenIds.add(id)) {
                    throw ApiException.validation("非空阶段id必须是当前项目已有阶段，不能当作新增");
                }
                stageId = id;
                created = false;
            } else {
                stageId = UUID.randomUUID().toString();
                created = true;
            }
            prepared.add(new PreparedStage(stageId, name, position++, created));
        }
        Set<String> kept = new HashSet<>();
        for (PreparedStage stage : prepared) {
            if (!stage.created()) {
                kept.add(stage.id());
            }
        }
        for (StageRow stage : existing.values()) {
            if (!kept.contains(stage.id()) && taskRepo.stageReferenced(stage.id())) {
                throw ApiException.validation("不能删除仍被任务引用的阶段");
            }
        }
        return prepared;
    }

    private void persistStages(String projectId, List<PreparedStage> prepared) {
        taskRepo.shiftStagePositions(projectId);
        int temp = 20_000;
        for (PreparedStage stage : prepared) {
            if (stage.created()) {
                taskRepo.insertStage(stage.id(), projectId, stage.name(), temp);
            } else {
                taskRepo.updateStage(stage.id(), projectId, stage.name(), temp);
            }
            temp++;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (PreparedStage stage : prepared) {
            kept.add(stage.id());
            taskRepo.updateStage(stage.id(), projectId, stage.name(), stage.position());
        }
        for (StageRow stage : teamRepo.stages(projectId)) {
            if (!kept.contains(stage.id())) {
                taskRepo.deleteStage(stage.id(), projectId);
            }
        }
    }

    private static String normalizeStageName(String name) {
        String normalized = name.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw ApiException.validation("阶段名称需为1～40个字");
        }
        return normalized;
    }

    private List<TaskViews.TaskView> taskViews(AuthenticatedUser principal, String projectId) {
        List<TaskViews.TaskView> views = new ArrayList<>();
        for (TaskRow task : taskRepo.tasksForProject(projectId)) {
            views.add(taskView(principal, task));
        }
        return views;
    }

    private TaskViews.TaskView taskView(AuthenticatedUser principal, TaskRow task) {
        String ownerName = "未认领";
        if (task.ownerId() != null) {
            UserRow owner = userRepository.findById(task.ownerId()).orElse(null);
            ownerName = owner == null ? "" : owner.displayName();
        }
        List<TaskViews.CriterionView> criteria = taskRepo.criteria(task.id()).stream()
                .map(row -> new TaskViews.CriterionView(row.id(), row.text())).toList();
        List<TaskViews.DependencyView> dependencies = taskRepo.dependenciesOf(task.id()).stream()
                .map(row -> new TaskViews.DependencyView(row.dependsOnTaskId(), row.gate())).toList();
        boolean claimable = "ORDINARY".equals(task.mode()) && "TODO".equals(task.status())
                && task.ownerId() == null
                && teamRepo.membership(task.projectId(), principal.userId()).isPresent();
        DeliveryService.TaskFacts facts = deliveryService.facts(task);
        return new TaskViews.TaskView(task.id(), task.projectId(), task.title(), task.description(),
                task.mode(), task.stageId(), task.ownerId(), ownerName, task.status(), task.dueAt(),
                criteria, facts.units(), dependencies, facts.reviewerAssignment(), task.revision(), claimable,
                facts.agreementStatus(), facts.agreementVersion());
    }

    private TaskViews.TaskView createAssessed(AuthenticatedUser principal, ProjectRow project, String projectId,
                                             TaskRequests.CreateTaskRequest request) {
        if (request.criteria() != null && !request.criteria().isEmpty()) {
            throw ApiException.validation("正式任务的标准写在assessedPlan里，顶层criteria必须为空");
        }
        if (request.assessedPlan() == null) {
            throw ApiException.validation("正式任务必须提供assessedPlan");
        }
        if (request.dueAt() != null) {
            throw ApiException.validation("正式任务的截止时间写在assessedPlan里");
        }
        if (request.dependencies() == null) {
            throw ApiException.validation("必须提供dependencies");
        }
        if (!"ACTIVE".equals(project.status())) {
            throw new ApiException(ApiCode.PROJECT_READ_ONLY);
        }
        if (teamRepo.stages(projectId).stream().noneMatch(stage -> stage.id().equals(request.stageId()))) {
            throw ApiException.validation("阶段不属于当前项目");
        }
        if (taskRepo.countTasks(projectId) >= TASK_LIMIT) {
            throw ApiException.validation("项目任务不能超过200");
        }
        java.util.Set<String> members = new java.util.LinkedHashSet<>();
        for (ProjectMemberRow member : teamRepo.members(projectId)) {
            if ("ACTIVE".equals(member.status())) {
                members.add(member.userId());
            }
        }
        deliveryService.validateAssessed(request.assessedPlan(), project.ownerId(), members);
        String taskId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        taskRepo.insertTask(new TaskRow(taskId, projectId, request.stageId(), request.title().strip(),
                request.description() == null ? "" : request.description().strip(), "ASSESSED",
                request.assessedPlan().ownerId(), request.assessedPlan().dueAt(), "TODO", 1, now), now);
        deliveryService.materialize(taskId, project.ownerId(), members, request.assessedPlan());
        List<DependencyRow> dependencies = normalizeDependencies(projectId, taskId, request.dependencies(), List.of(),
                "TODO");
        taskRepo.replaceDependencies(taskId, dependencies);
        ensureAcyclic(projectId);
        teamRepo.bumpRevision(projectId);
        auditService.record(principal.userId(), "TASK_CREATE", "TASK", taskId, null, 1, null);
        return taskView(principal, taskRepo.findTask(taskId).orElseThrow());
    }

    private List<TeamViews.StageView> stages(String projectId) {
        return teamRepo.stages(projectId).stream()
                .map(stage -> new TeamViews.StageView(stage.id(), stage.name())).toList();
    }

    private List<TeamViews.MembershipView> members(String projectId) {
        List<TeamViews.MembershipView> members = new ArrayList<>();
        for (ProjectMemberRow row : teamRepo.members(projectId)) {
            if (!"ACTIVE".equals(row.status())) {
                continue;
            }
            members.add(new TeamViews.MembershipView(row.id(), row.id(), row.projectId(), row.userId(),
                    row.displayName(), row.roleSlotId(), row.projectRole(), row.status(), row.color(),
                    row.colorRevision(), row.joinedAt(), row.revision()));
        }
        return members;
    }

    private ProjectRow requireMemberProject(AuthenticatedUser principal, String projectId) {
        policy.requireStudent(principal);
        ProjectRow project = teamRepo.findProject(projectId).orElseThrow(ApiException::notFound);
        requireSameSchool(principal, project);
        requireMember(projectId, principal.userId());
        return project;
    }

    private void requireSameSchool(AuthenticatedUser principal, ProjectRow project) {
        SquareRow square = squareRepository.findById(project.squareId()).orElseThrow(ApiException::notFound);
        if (!square.schoolId().equals(principal.schoolId())) {
            throw ApiException.notFound();
        }
    }

    private void requireMember(String projectId, String userId) {
        if (teamRepo.membership(projectId, userId).isEmpty()) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private void requireLeader(ProjectRow project, String userId) {
        if (!project.ownerId().equals(userId)) {
            throw new ApiException(ApiCode.FORBIDDEN);
        }
    }

    private record PreparedStage(String id, String name, int position, boolean created) {
    }
}
