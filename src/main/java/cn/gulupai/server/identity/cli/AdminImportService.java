package cn.gulupai.server.identity.cli;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.common.validation.Ids;
import cn.gulupai.server.identity.domain.IdentityStatus;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 受控管理 CLI 的用户导入：先整文件校验，再整批事务写入；坏行不会悄悄略过。
 */
@Service
public class AdminImportService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;
    private final PlatformRepository platformRepository;

    public AdminImportService(ObjectMapper objectMapper,
                              JdbcTemplate jdbcTemplate,
                              UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              TransactionTemplate transactionTemplate,
                              AuditService auditService,
                              PlatformRepository platformRepository) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
        this.platformRepository = platformRepository;
    }

    public ImportSummary importUsers(Path file, boolean dryRun) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取导入文件: " + file, ex);
        }
        ImportFile input;
        try {
            input = objectMapper.readValue(bytes, ImportFile.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("导入文件不是合法JSON或包含未知字段: " + ex.getMessage(), ex);
        }

        String inputHash = Hashing.sha256Hex(bytes);
        Plan plan = validate(input);
        ImportSummary summary = new ImportSummary(plan.schools.size(), plan.created,
                plan.updated, dryRun, inputHash);
        if (dryRun) {
            preflightPlatformAccounts(plan);
            return summary;
        }

        ImportSummary applied = transactionTemplate.execute(status -> {
            platformRepository.lockAdminConstraint();
            int recoverableBefore = platformRepository.countPlatformRecoveryAdmins();
            applyBatch(plan);
            int recoverableAfter = platformRepository.countPlatformRecoveryAdmins();
            if (recoverableBefore > 0 && recoverableAfter == 0) {
                throw new IllegalStateException("本次导入会让平台失去最后一位可恢复管理员，已整批回滚");
            }
            String batchId = UUID.randomUUID().toString();
            Map<String, Object> summaryJson = new LinkedHashMap<>();
            summaryJson.put("schools", plan.schools.size());
            summaryJson.put("created", plan.created);
            summaryJson.put("updated", plan.updated);
            jdbcTemplate.update("""
                    INSERT INTO import_batch
                        (id, kind, input_hash, actor_id, status, summary_json, revision)
                    VALUES (?, 'USERS', ?, 'cli', 'APPLIED', ?::jsonb, 1)
                    """, batchId, inputHash, writeJson(summaryJson));
            auditService.record("cli", "ADMIN_IMPORT_USERS", "IMPORT_BATCH", batchId,
                    null, null, "users=" + (plan.created + plan.updated));
            return summary;
        });
        return applied == null ? summary : applied;
    }

    /**
     * 整批写入：先按 id 稳定顺序锁住受影响的现有账号并读出平台事实，全部校验通过后才落库。
     * 逐行校验会漏掉“一批次里彼此都是最后一位管理员”的情况，因此恢复能力在批次结束后整体复核。
     */
    private void applyBatch(Plan plan) {
        for (SchoolPlan school : plan.schools) {
            upsertSchool(school);
        }
        List<UserPlan> ordered = new ArrayList<>(plan.users);
        ordered.sort(Comparator.comparing(UserPlan::id));
        Map<String, PlatformAccount> accounts = new LinkedHashMap<>();
        for (UserPlan user : ordered) {
            if (user.isNew()) {
                continue;
            }
            accounts.put(user.id(), readAccount(user.id(), true));
        }
        for (UserPlan user : ordered) {
            PlatformAccount account = accounts.get(user.id());
            if (account != null) {
                requireSensitiveFieldsUnchanged(user, account);
            }
        }
        for (UserPlan user : ordered) {
            String userId = upsertUser(user, accounts.get(user.id()));
            // 只重建普通业务角色：SUPER_ADMIN 由平台授权服务管理，导入不得删除有效平台角色。
            jdbcTemplate.update("DELETE FROM user_role WHERE user_id = ? AND role <> 'SUPER_ADMIN'", userId);
            for (Role role : user.roles) {
                jdbcTemplate.update("INSERT INTO user_role (user_id, role) VALUES (?, ?)",
                        userId, role.name());
            }
        }
    }

    /** dry-run 不做任何写入，但必须按真实规则报出会被拒绝的敏感变更。 */
    private void preflightPlatformAccounts(Plan plan) {
        for (UserPlan user : plan.users) {
            if (user.isNew()) {
                continue;
            }
            requireSensitiveFieldsUnchanged(user, readAccount(user.id(), false));
        }
    }

    private PlatformAccount readAccount(String userId, boolean lock) {
        var found = lock ? platformRepository.lockUser(userId) : platformRepository.findUser(userId);
        PlatformRepository.ManagedUser user = found
                .orElseThrow(() -> new IllegalStateException("导入目标账号在事务内消失: " + userId));
        boolean roleHeld = platformRepository.rolesOf(userId).contains(Role.SUPER_ADMIN.name());
        boolean grantActive = platformRepository.findActiveGrant(userId).isPresent();
        String passwordHash = jdbcTemplate.query("SELECT password_hash FROM app_user WHERE id = ?",
                (rs, row) -> rs.getString("password_hash"), userId).stream().findFirst().orElse(null);
        return new PlatformAccount(user, roleHeld, grantActive, passwordHash);
    }

    /**
     * 已有平台管理员的停用、身份资格和凭据只能由受控管理操作改变。普通导入若试图修改这些字段
     * 就明确拒绝并指出替代路径，既不静默忽略输入，也不让批量导入成为绕过停用或隐式换密的入口。
     */
    private void requireSensitiveFieldsUnchanged(UserPlan plan, PlatformAccount account) {
        if (!account.platformAdmin()) {
            return;
        }
        if (account.roleHeld() != account.grantActive()) {
            throw new IllegalArgumentException("账号 " + plan.loginId()
                    + " 的平台角色与有效授权不一致，普通导入不能修复；请先用受控授权修复流程恢复");
        }
        if (plan.disabled() != account.user().disabled()) {
            throw new IllegalArgumentException("账号 " + plan.loginId()
                    + " 是平台管理员，普通导入不能改变停用状态；请改用受控管理操作");
        }
        if (!plan.status().name().equals(account.user().identityStatus())) {
            throw new IllegalArgumentException("账号 " + plan.loginId()
                    + " 是平台管理员，普通导入不能改变身份核验状态；请改用受控管理操作");
        }
        if (account.passwordHash() == null || !passwordEncoder.matches(plan.rawPassword(), account.passwordHash())) {
            throw new IllegalArgumentException("账号 " + plan.loginId()
                    + " 是平台管理员，普通导入不能修改口令；请改用受控口令重置流程");
        }
    }

    private record PlatformAccount(PlatformRepository.ManagedUser user, boolean roleHeld, boolean grantActive,
                                   String passwordHash) {
        boolean platformAdmin() {
            return roleHeld || grantActive;
        }
    }

    private Plan validate(ImportFile input) {
        if (input == null) {
            throw new IllegalArgumentException("导入文件为空");
        }
        List<SchoolPlan> schools = new ArrayList<>();
        Map<String, SchoolPlan> schoolById = new LinkedHashMap<>();
        Set<String> schoolIds = new HashSet<>();
        if (input.schools() != null) {
            for (SchoolInput school : input.schools()) {
                String id = requireId(school.id(), "学校ID");
                if (!schoolIds.add(id)) {
                    throw new IllegalArgumentException("学校ID重复: " + id);
                }
                String name = requireText(school.name(), "学校名称", 160);
                String timezone = requireText(school.timezone(), "学校时区", 64);
                try {
                    ZoneId.of(timezone);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("学校时区无效: " + timezone);
                }
                SchoolPlan plan = new SchoolPlan(id, name, timezone);
                schools.add(plan);
                schoolById.put(id, plan);
            }
        }
        if (input.users() == null || input.users().isEmpty()) {
            throw new IllegalArgumentException("导入文件没有用户");
        }

        Set<String> loginIds = new HashSet<>();
        Set<String> userIds = new HashSet<>();
        Set<String> studentKeys = new HashSet<>();
        List<UserPlan> users = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (UserInput user : input.users()) {
            String loginId = requireLoginId(user.loginId());
            if (!loginIds.add(loginId)) {
                throw new IllegalArgumentException("登录名重复: " + loginId);
            }
            String schoolId = requireId(user.schoolId(), "学校ID");
            if (!schoolById.containsKey(schoolId) && !schoolExists(schoolId)) {
                throw new IllegalArgumentException("用户 " + loginId + " 引用了不存在的学校: " + schoolId);
            }
            String studentNo = optionalText(user.studentNo(), "学号", 64, true);
            if (studentNo != null && !studentKeys.add(schoolId + "|" + studentNo)) {
                throw new IllegalArgumentException("同一学校学号重复: " + studentNo);
            }
            String displayName = requireText(user.displayName(), "显示名", 80);
            if (user.password() == null || user.password().isEmpty() || user.password().length() > 256) {
                throw new IllegalArgumentException("用户 " + loginId + " 的口令必须为1～256个字符");
            }
            String passwordHash = passwordEncoder.encode(user.password());
            IdentityStatus status = user.identityStatus() == null
                    ? IdentityStatus.VERIFIED
                    : parseStatus(user.identityStatus(), loginId);
            boolean disabled = Boolean.TRUE.equals(user.disabled());
            List<Role> roles = parseRoles(user.roles(), loginId);
            List<String> skills = parseSkills(user.skills());

            String requestedId = optionalText(user.id(), "用户ID", 120, false);
            if (requestedId != null && (!Ids.isValid(requestedId) || !userIds.add(requestedId))) {
                throw new IllegalArgumentException("用户ID无效或重复: " + requestedId);
            }
            UserRow existing = userRepository.findByLoginId(loginId).orElse(null);
            String finalId;
            boolean isNew;
            if (existing == null) {
                finalId = requestedId == null ? UUID.randomUUID().toString() : requestedId;
                if (userRepository.findById(finalId).isPresent()) {
                    throw new IllegalArgumentException("用户ID已被其他账号占用: " + finalId);
                }
                created++;
                isNew = true;
            } else {
                if (requestedId != null && !requestedId.equals(existing.id())) {
                    throw new IllegalArgumentException("登录名 " + loginId + " 已存在且ID不一致");
                }
                if (!existing.schoolId().equals(schoolId)) {
                    throw new IllegalArgumentException("登录名 " + loginId + " 已属于其他学校，不能改归属");
                }
                finalId = existing.id();
                updated++;
                isNew = false;
            }
            UserRow existingByStudentNo = studentNo == null ? null
                    : userRepository.findBySchoolAndStudentNo(schoolId, studentNo).orElse(null);
            if (existingByStudentNo != null && !existingByStudentNo.loginId().equals(loginId)) {
                throw new IllegalArgumentException("同一学校学号已被其他账号使用: " + studentNo);
            }
            users.add(new UserPlan(finalId, schoolId, loginId, studentNo, displayName,
                    user.password(), passwordHash, status, disabled, roles, skills, isNew));
        }
        return new Plan(schools, users, created, updated);
    }

    private boolean schoolExists(String schoolId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM school WHERE id = ?", Integer.class, schoolId);
        return count != null && count > 0;
    }

    private void upsertSchool(SchoolPlan school) {
        jdbcTemplate.update("""
                INSERT INTO school (id, name, timezone, revision)
                VALUES (?, ?, ?, 1)
                ON CONFLICT (id) DO UPDATE
                SET name = EXCLUDED.name, timezone = EXCLUDED.timezone,
                    revision = school.revision + 1, updated_at = CURRENT_TIMESTAMP
                """, school.id(), school.name(), school.timezone());
    }

    private String upsertUser(UserPlan user, PlatformAccount account) {
        if (account != null && account.platformAdmin()) {
            // 管理员只随导入更新展示性字段：角色、能力、范围、凭据与可用性由受控管理操作负责。
            jdbcTemplate.update("""
                    UPDATE app_user
                    SET student_no = ?, display_name = ?, skills_json = ?::jsonb,
                        revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, user.studentNo(), user.displayName(), writeJson(user.skills()), user.id());
            return user.id();
        }
        String accountStatus = user.disabled() ? "DISABLED" : "ACTIVE";
        String eligibility = user.disabled() ? "IMPORT_DISABLED" : "VERIFIED_ACTIVE";
        return jdbcTemplate.queryForObject("""
                INSERT INTO app_user
                    (id, school_id, login_id, student_no, display_name, password_hash,
                     identity_status, disabled, account_status, eligibility_source, skills_json, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1)
                ON CONFLICT (login_id) DO UPDATE
                SET school_id = EXCLUDED.school_id,
                    student_no = EXCLUDED.student_no,
                    display_name = EXCLUDED.display_name,
                    password_hash = EXCLUDED.password_hash,
                    identity_status = EXCLUDED.identity_status,
                    disabled = EXCLUDED.disabled,
                    account_status = EXCLUDED.account_status,
                    eligibility_source = EXCLUDED.eligibility_source,
                    skills_json = EXCLUDED.skills_json,
                    revision = app_user.revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """, String.class,
                user.id(), user.schoolId(), user.loginId(), user.studentNo(), user.displayName(),
                user.passwordHash(), user.status().name(), user.disabled(), accountStatus, eligibility,
                writeJson(user.skills()));
    }

    private List<Role> parseRoles(List<String> roles, String loginId) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("用户 " + loginId + " 至少需要一个角色");
        }
        Set<Role> result = new HashSet<>();
        for (String raw : roles) {
            Role parsed;
            try {
                parsed = Role.valueOf(raw);
            } catch (Exception ex) {
                throw new IllegalArgumentException("用户 " + loginId + " 的角色无效: " + raw);
            }
            if (parsed == Role.SUPER_ADMIN) {
                throw new IllegalArgumentException("用户 " + loginId + " 不能通过导入获得 SUPER_ADMIN");
            }
            result.add(parsed);
        }
        List<Role> sorted = new ArrayList<>(result);
        sorted.sort(java.util.Comparator.comparingInt(Enum::ordinal));
        return sorted;
    }

    private IdentityStatus parseStatus(String raw, String loginId) {
        try {
            return IdentityStatus.valueOf(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException("用户 " + loginId + " 的身份状态无效: " + raw);
        }
    }

    private List<String> parseSkills(List<String> skills) {
        if (skills == null) {
            return List.of();
        }
        if (skills.size() > 12) {
            throw new IllegalArgumentException("技能最多12项");
        }
        List<String> result = new ArrayList<>();
        for (String skill : skills) {
            String value = requireText(skill, "技能", 80);
            result.add(value);
        }
        return result;
    }

    private String requireId(String raw, String field) {
        String value = raw == null ? "" : raw.strip();
        if (!Ids.isValid(value)) {
            throw new IllegalArgumentException(field + "无效: " + raw);
        }
        return value;
    }

    private String requireLoginId(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > 120
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("登录名必须为1～120个非空白字符");
        }
        return value;
    }

    private String requireText(String raw, String field, int max) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(field + "必须为1～" + max + "个字符");
        }
        return value;
    }

    private String optionalText(String raw, String field, int max, boolean allowWhitespace) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max || (!allowWhitespace && value.chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException(field + "格式无效: " + raw);
        }
        return value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化导入数据", ex);
        }
    }

    public record ImportSummary(int schools, int created, int updated, boolean dryRun, String inputHash) {
        public String describe() {
            return (dryRun ? "[dry-run] " : "") + "学校 " + schools + " 所，新增用户 " + created
                    + "，更新用户 " + updated + "，输入摘要 " + inputHash;
        }
    }

    private record Plan(List<SchoolPlan> schools, List<UserPlan> users, int created, int updated) {
    }

    private record SchoolPlan(String id, String name, String timezone) {
    }

    /**
     * @param rawPassword 仅用于与现有哈希做 {@code PasswordEncoder.matches} 核对，不落库、不输出。
     */
    private record UserPlan(String id, String schoolId, String loginId, String studentNo,
                            String displayName, String rawPassword, String passwordHash, IdentityStatus status,
                            boolean disabled, List<Role> roles, List<String> skills, boolean isNew) {
    }

    public record ImportFile(List<SchoolInput> schools, List<UserInput> users) {
    }

    public record SchoolInput(String id, String name, String timezone) {
    }

    public record UserInput(String id, String schoolId, String loginId, String studentNo,
                            String displayName, String password, String identityStatus,
                            Boolean disabled, List<String> roles, List<String> skills) {
    }
}
