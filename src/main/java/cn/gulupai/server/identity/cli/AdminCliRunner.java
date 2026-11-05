package cn.gulupai.server.identity.cli;

import cn.gulupai.server.file.CleanupAdminService;
import cn.gulupai.server.governance.MaterialResponseTimeRecoveryService;
import cn.gulupai.server.governance.MaterialTimeRecoveryAuditService;
import cn.gulupai.server.governance.PersonnelCaseGrantAdminService;
import cn.gulupai.server.support.HelpCatalogImportService;
import cn.gulupai.server.support.OperatorGrantAdminService;
import cn.gulupai.server.identity.application.RegistrationReviewService;
import cn.gulupai.server.teaching.application.TeacherClaimReviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 受控管理 CLI 入口：
 * {@code java -jar app.jar --spring.profiles.active=cli --app.cli.command=import-users|import-academic|review-teacher-claim|review-registration|list-registrations|school-registration|cleanup-plan|cleanup-cancel|help-import|operator-grant|personnel-case-grant|material-time-recovery|material-time-recovery-audit|demo-seed}
 */
@Component
@ConditionalOnProperty(prefix = "app.cli", name = "enabled", havingValue = "true")
public class AdminCliRunner implements ApplicationRunner {

    private final ApplicationContext context;
    private final AdminImportService importService;
    private final AcademicImportService academicImportService;
    private final TeacherClaimReviewService teacherClaimReviewService;
    private final CleanupAdminService cleanupAdminService;
    private final HelpCatalogImportService helpImportService;
    private final OperatorGrantAdminService operatorGrantAdminService;
    private final DemoSeedService demoSeedService;
    private final PersonnelCaseGrantAdminService personnelCaseGrantService;
    private final MaterialResponseTimeRecoveryService materialTimeRecoveryService;
    private final MaterialTimeRecoveryAuditService materialTimeRecoveryAuditService;
    private final RegistrationReviewService registrationReviewService;
    private final CliOperatorDirectory operatorDirectory;
    private final String command;
    private final String file;
    private final boolean dryRun;
    private final String actorId;

    public AdminCliRunner(ApplicationContext context,
                          AdminImportService importService,
                          AcademicImportService academicImportService,
                          TeacherClaimReviewService teacherClaimReviewService,
                          CleanupAdminService cleanupAdminService,
                          HelpCatalogImportService helpImportService,
                          OperatorGrantAdminService operatorGrantAdminService,
                          DemoSeedService demoSeedService,
                          PersonnelCaseGrantAdminService personnelCaseGrantService,
                          MaterialResponseTimeRecoveryService materialTimeRecoveryService,
                          MaterialTimeRecoveryAuditService materialTimeRecoveryAuditService,
                          RegistrationReviewService registrationReviewService,
                          CliOperatorDirectory operatorDirectory,
                          @Value("${app.cli.command:}") String command,
                          @Value("${app.cli.file:}") String file,
                          @Value("${app.cli.dry-run:false}") boolean dryRun,
                          @Value("${app.cli.actor:cli}") String actorId) {
        this.context = context;
        this.importService = importService;
        this.academicImportService = academicImportService;
        this.teacherClaimReviewService = teacherClaimReviewService;
        this.cleanupAdminService = cleanupAdminService;
        this.helpImportService = helpImportService;
        this.operatorGrantAdminService = operatorGrantAdminService;
        this.demoSeedService = demoSeedService;
        this.personnelCaseGrantService = personnelCaseGrantService;
        this.materialTimeRecoveryService = materialTimeRecoveryService;
        this.materialTimeRecoveryAuditService = materialTimeRecoveryAuditService;
        this.registrationReviewService = registrationReviewService;
        this.operatorDirectory = operatorDirectory;
        this.command = command;
        this.file = file;
        this.dryRun = dryRun;
        this.actorId = actorId;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(args);
        int code = SpringApplication.exit(context, () -> exitCode);
        System.exit(code);
    }

    /**
     * 执行命令并返回进程退出码。独立出来便于测试真实参数解析与拒绝输出，
     * 同时保持 ApplicationRunner 的成功/失败约定不变。
     */
    public int execute(ApplicationArguments args) {
        int computedExitCode;
        try {
            if (command == null || command.isBlank()) {
                throw new IllegalArgumentException(
                        "缺少 --app.cli.command；支持 import-users、import-academic、review-teacher-claim、review-registration、list-registrations、school-registration、cleanup-plan、cleanup-cancel、help-import、operator-grant、personnel-case-grant、material-time-recovery、material-time-recovery-audit、demo-seed");
            }
            switch (command) {
                case "import-users" -> {
                    if (file == null || file.isBlank()) {
                        throw new IllegalArgumentException("import-users 需要 --app.cli.file");
                    }
                    AdminImportService.ImportSummary summary =
                            importService.importUsers(Path.of(file), dryRun);
                    System.out.println(summary.describe());
                }
                case "import-academic" -> {
                    if (file == null || file.isBlank()) {
                        throw new IllegalArgumentException("import-academic 需要 --app.cli.file");
                    }
                    AcademicImportService.ImportSummary summary =
                            academicImportService.importAcademic(Path.of(file), dryRun);
                    System.out.println(summary.describe());
                }
                case "list-registrations" -> registrationReviewService.pendingLines()
                        .forEach(System.out::println);
                case "review-registration" -> {
                    rejectClaimedActor(args);
                    String applicationId = option(args, "app.cli.application-id");
                    String decision = option(args, "app.cli.decision");
                    String reason = option(args, "app.cli.reason");
                    String revisionText = option(args, "app.cli.expected-revision");
                    if (applicationId.isBlank() || decision.isBlank() || revisionText.isBlank()) {
                        throw new IllegalArgumentException(
                                "review-registration 需要 --app.cli.application-id、--app.cli.decision 和 --app.cli.expected-revision");
                    }
                    int revision = Integer.parseInt(revisionText);
                    CliOperatorDirectory.Reviewer reviewer = operatorDirectory.requireCurrent();
                    System.out.println(registrationReviewService.review(
                            applicationId, revision, decision, reason)
                            + " reviewer=" + reviewer.displayLabel());
                }
                case "school-registration" -> {
                    rejectClaimedActor(args);
                    String schoolId = option(args, "app.cli.school-id");
                    String enabledText = option(args, "app.cli.enabled");
                    if (schoolId.isBlank() || (!"true".equals(enabledText) && !"false".equals(enabledText))) {
                        throw new IllegalArgumentException(
                                "school-registration 需要 --app.cli.school-id 和 --app.cli.enabled=true|false");
                    }
                    CliOperatorDirectory.Reviewer reviewer = operatorDirectory.requireCurrent();
                    System.out.println(registrationReviewService.setSchoolOpen(
                            schoolId, Boolean.parseBoolean(enabledText))
                            + " reviewer=" + reviewer.displayLabel());
                }
                case "review-teacher-claim" -> {
                    String claimId = args.getOptionValues("app.cli.claim-id") == null
                            ? "" : args.getOptionValues("app.cli.claim-id").getFirst();
                    String decision = args.getOptionValues("app.cli.decision") == null
                            ? "" : args.getOptionValues("app.cli.decision").getFirst();
                    String reason = args.getOptionValues("app.cli.reason") == null
                            ? null : args.getOptionValues("app.cli.reason").getFirst();
                    if (claimId.isBlank() || decision.isBlank()) {
                        throw new IllegalArgumentException(
                                "review-teacher-claim 需要 --app.cli.claim-id 和 --app.cli.decision");
                    }
                    System.out.println(teacherClaimReviewService.review(claimId, decision, reason).summary());
                }
                case "cleanup-plan" -> {
                    if (!dryRun) {
                        throw new IllegalArgumentException("cleanup-plan 只允许 --app.cli.dry-run=true，不会删除数据");
                    }
                    var plans = cleanupAdminService.dryRun();
                    System.out.println("清理计划：" + plans.size());
                    plans.forEach(plan -> System.out.println(plan.line()));
                }
                case "help-import" -> {
                    if (file == null || file.isBlank()) {
                        throw new IllegalArgumentException("help-import 需要 --app.cli.file");
                    }
                    System.out.println(helpImportService.importArticles(Path.of(file), dryRun, actorId).describe());
                }
                case "operator-grant" -> {
                    String userId = option(args, "app.cli.user-id");
                    String capability = option(args, "app.cli.capability");
                    String scopeType = option(args, "app.cli.scope-type");
                    if (scopeType.isBlank()) {
                        scopeType = "GLOBAL";
                    }
                    String scopeId = option(args, "app.cli.scope-id");
                    String activeText = option(args, "app.cli.active");
                    boolean active = activeText.isBlank() || Boolean.parseBoolean(activeText);
                    if (!activeText.isBlank() && !"true".equals(activeText) && !"false".equals(activeText)) {
                        throw new IllegalArgumentException("app.cli.active 只能是 true 或 false");
                    }
                    System.out.println(operatorGrantAdminService.grant(
                            userId, capability, scopeType, scopeId, active, dryRun, actorId).describe());
                }
                case "personnel-case-grant" -> {
                    String action = option(args, "app.cli.action");
                    String caseId = option(args, "app.cli.case-id");
                    String teacherUserId = option(args, "app.cli.teacher-user-id");
                    String reason = option(args, "app.cli.reason");
                    String grantId = option(args, "app.cli.grant-id");
                    String revisionText = option(args, "app.cli.expected-revision");
                    String expiresText = option(args, "app.cli.expires-at");
                    switch (action) {
                        case "grant" -> {
                            java.time.Instant expiresAt = expiresText.isBlank()
                                    ? null : java.time.Instant.parse(expiresText);
                            System.out.println(personnelCaseGrantService.grant(caseId, teacherUserId,
                                    reason, expiresAt, dryRun, actorId).describe());
                        }
                        case "revoke" -> {
                            if (revisionText.isBlank()) {
                                throw new IllegalArgumentException("revoke 需要 --app.cli.expected-revision");
                            }
                            int revision = Integer.parseInt(revisionText);
                            System.out.println(personnelCaseGrantService.revoke(grantId, revision,
                                    reason, dryRun, actorId).describe());
                        }
                        case "list" -> personnelCaseGrantService.list(caseId).stream()
                                .map(PersonnelCaseGrantAdminService.GrantResult::describe)
                                .forEach(System.out::println);
                        default -> throw new IllegalArgumentException(
                                "personnel-case-grant 的 action 必须是 grant、revoke 或 list");
                    }
                }
                case "material-time-recovery" -> {
                    String materialId = option(args, "app.cli.material-id");
                    String versionText = option(args, "app.cli.response-version");
                    String receiptId = option(args, "app.cli.receipt-id");
                    if (materialId.isBlank() || versionText.isBlank() || receiptId.isBlank()) {
                        throw new IllegalArgumentException("material-time-recovery 需要 "
                                + "--app.cli.material-id、--app.cli.response-version 和 --app.cli.receipt-id");
                    }
                    int version;
                    try {
                        version = Integer.parseInt(versionText);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("--app.cli.response-version 必须是正整数");
                    }
                    System.out.println(materialTimeRecoveryService.recover(materialId, version, receiptId,
                            dryRun, actorId).describe());
                }
                case "material-time-recovery-audit" -> {
                    MaterialTimeRecoveryAuditService.AuditReport report =
                            materialTimeRecoveryAuditService.audit();
                    System.out.println(report.describe());
                    report.items().forEach(item -> System.out.println(item.describe()));
                }
                case "demo-seed" -> {
                    String scenario = option(args, "app.cli.scenario");
                    if (scenario.isBlank()) {
                        scenario = DemoSeedService.SCENARIO_FULL;
                    }
                    String credentials = option(args, "app.cli.credentials-file");
                    Path credentialsFile = credentials.isBlank() ? null : Path.of(credentials);
                    System.out.println(demoSeedService.seed(scenario, dryRun, credentialsFile, actorId).describe());
                }
                case "cleanup-cancel" -> {
                    String planId = option(args, "app.cli.plan-id");
                    String reason = option(args, "app.cli.reason");
                    cleanupAdminService.cancel(planId, reason, actorId);
                    System.out.println("已取消清理计划 " + planId);
                }
                default -> throw new IllegalArgumentException("不支持的CLI命令: " + command);
            }
            computedExitCode = 0;
        } catch (Exception ex) {
            System.err.println("CLI失败: " + ex.getMessage());
            computedExitCode = 1;
        }
        return computedExitCode;
    }

    private static String option(ApplicationArguments args, String name) {
        return args.getOptionValues(name) == null ? "" : args.getOptionValues(name).getFirst();
    }

    private static void rejectClaimedActor(ApplicationArguments args) {
        if (args.containsOption("app.cli.actor")) {
            throw new IllegalArgumentException("不能通过 app.cli.actor 指定审核人");
        }
    }
}
