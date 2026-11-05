package cn.gulupai.server.identity.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.api.FieldErrorDetail;
import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.ratelimit.RateLimitService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.identity.RegistrationProperties;
import cn.gulupai.server.identity.api.RegistrationOptions;
import cn.gulupai.server.identity.api.RegistrationReceipt;
import cn.gulupai.server.identity.api.RegistrationRequest;
import cn.gulupai.server.identity.persistence.RegistrationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationService {

    static final String ENDPOINT = "POST /auth/registrations";
    private static final int SCHOOL_LIMIT = 1000;
    private static final String LOGIN_PATTERN = "[A-Za-z][A-Za-z0-9_]{3,31}";

    private final RegistrationRepository repository;
    private final RegistrationProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RegistrationService(RegistrationRepository repository,
                               RegistrationProperties properties,
                               PasswordEncoder passwordEncoder,
                               RateLimitService rateLimitService,
                               AuditService auditService,
                               ObjectMapper objectMapper,
                               TransactionTemplate transactionTemplate,
                               Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public RegistrationOptions options() {
        int count = repository.countOpenSchools();
        if (count > SCHOOL_LIMIT) {
            throw new ApiException(ApiCode.COLLECTION_TOO_LARGE, "允许注册的学校超过1000所，需要先约定分页");
        }
        List<RegistrationOptions.SchoolOption> schools = repository.openSchools().stream()
                .map(row -> new RegistrationOptions.SchoolOption(row.id(), row.name()))
                .toList();
        return new RegistrationOptions(properties.isEnabled(), properties.isTeacherApplicationEnabled(), schools);
    }

    public RegistrationReceipt register(RegistrationRequest request, String idempotencyKey, String remoteIp) {
        String key = requireKey(idempotencyKey);
        Normalized normalized = normalize(request);
        String fingerprint = fingerprint(normalized);
        Optional<RegistrationReceipt> replay = transactionTemplate.execute(
                status -> readReplay(key, fingerprint));
        if (replay != null && replay.isPresent()) {
            return replay.get();
        }
        if (!properties.isEnabled()
                || ("TEACHER".equals(normalized.role()) && !properties.isTeacherApplicationEnabled())) {
            throw new ApiException(ApiCode.REGISTRATION_DISABLED);
        }
        rateLimitService.consumeWindow(Hashing.sha256Hex("reg-account|" + normalized.loginId()),
                "REGISTER_ACCOUNT", properties.getMaxPerAccount(), properties.getWindow());
        rateLimitService.consumeWindow(Hashing.sha256Hex("reg-ip|" + remoteIp),
                "REGISTER_IP", properties.getMaxPerIp(), properties.getWindow());
        try {
            RegistrationReceipt created = transactionTemplate.execute(status ->
                    createOnce(key, fingerprint, normalized));
            return java.util.Objects.requireNonNull(created);
        } catch (DataIntegrityViolationException ex) {
            String text = ex.getMostSpecificCause() == null ? "" : ex.getMostSpecificCause().getMessage();
            if (text != null && text.contains("uq_app_user_login")) {
                throw new ApiException(ApiCode.ACCOUNT_EXISTS);
            }
            throw ex;
        }
    }

    private Optional<RegistrationReceipt> readReplay(String key, String fingerprint) {
        repository.lockKey(Hashing.advisoryLockKey("REG|" + ENDPOINT + "|" + key));
        return matchReceipt(key, fingerprint);
    }

    private Optional<RegistrationReceipt> matchReceipt(String key, String fingerprint) {
        Optional<RegistrationRepository.ReceiptRow> found = repository.findReceipt(ENDPOINT, key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RegistrationRepository.ReceiptRow row = found.get();
        if (!row.expiresAt().isAfter(clock.instant())) {
            repository.deleteReceipt(ENDPOINT, key);
            return Optional.empty();
        }
        if (!row.keyId().equals(properties.getKeyId()) || !row.fingerprint().equals(fingerprint)) {
            throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REUSED);
        }
        return Optional.of(readReceipt(row.receiptJson()));
    }

    private RegistrationReceipt createOnce(String key, String fingerprint, Normalized normalized) {
        repository.lockKey(Hashing.advisoryLockKey("REG|" + ENDPOINT + "|" + key));
        Optional<RegistrationReceipt> replay = matchReceipt(key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!properties.isEnabled()
                || ("TEACHER".equals(normalized.role()) && !properties.isTeacherApplicationEnabled())) {
            throw new ApiException(ApiCode.REGISTRATION_DISABLED);
        }
        if (!repository.schoolOpen(normalized.schoolId())) {
            throw ApiException.fields(List.of(new FieldErrorDetail(
                    "schoolId", "INVALID_FORMAT", "学校不在允许注册的范围内")));
        }
        boolean teacher = "TEACHER".equals(normalized.role());
        String userId = UUID.randomUUID().toString();
        String applicationId = "reg_" + UUID.randomUUID().toString().replace("-", "");
        String status = teacher ? "PENDING_REVIEW" : "ACTIVE";
        String accountStatus = teacher ? "PENDING_REVIEW" : "ACTIVE";
        repository.insertUser(userId, normalized.schoolId(), normalized.loginId(),
                normalized.displayName(), passwordEncoder.encode(normalized.password()), accountStatus);
        if (!teacher) {
            repository.insertRole(userId, "STUDENT");
        }
        repository.insertApplication(applicationId, userId, normalized.role(), status,
                normalized.schoolId(), teacher ? normalized.teacherApplication() : null);
        RegistrationReceipt receipt = new RegistrationReceipt(applicationId, normalized.role(), status);
        Instant expiresAt = clock.instant().plus(properties.getReceiptTtl());
        repository.insertReceipt(ENDPOINT, key, fingerprint, properties.getKeyId(),
                write(receipt), expiresAt);
        auditService.record(userId, "REGISTRATION_SUBMIT", "REGISTRATION", applicationId,
                null, 1, null);
        return receipt;
    }

    private Normalized normalize(RegistrationRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        String loginId = request.loginId() == null ? "" : request.loginId().strip();
        if (!loginId.matches(LOGIN_PATTERN)) {
            errors.add(new FieldErrorDetail("loginId", "INVALID_FORMAT", "账号需为4至32位，以字母开头"));
        }
        String password = request.password() == null ? "" : request.password();
        if (password.length() < 8 || password.length() > 64) {
            errors.add(new FieldErrorDetail("password", "OUT_OF_RANGE", "密码长度需为8至64"));
        }
        String displayName = request.displayName() == null ? "" : request.displayName().strip();
        if (displayName.isEmpty() || displayName.length() > 24) {
            errors.add(new FieldErrorDetail("displayName",
                    displayName.isEmpty() ? "REQUIRED" : "TOO_LONG",
                    displayName.isEmpty() ? "请填写此项" : "超出长度限制"));
        }
        String schoolId = request.schoolId() == null ? "" : request.schoolId().strip();
        if (schoolId.isEmpty() || schoolId.length() > 120) {
            errors.add(new FieldErrorDetail("schoolId", "REQUIRED", "请填写此项"));
        }
        String role = request.requestedRole() == null ? "" : request.requestedRole().strip();
        if (!"STUDENT".equals(role) && !"TEACHER".equals(role)) {
            errors.add(new FieldErrorDetail("requestedRole", "INVALID_FORMAT", "只能申请学生或教师"));
        }
        String teacherApplication = request.teacherApplication();
        String teacherText = teacherApplication == null ? null : teacherApplication.strip();
        if ("STUDENT".equals(role) && teacherApplication != null) {
            errors.add(new FieldErrorDetail("teacherApplication", "INVALID_FORMAT", "学生注册不能填写核验说明"));
        }
        if ("TEACHER".equals(role) && (teacherText == null || teacherText.length() < 5 || teacherText.length() > 500)) {
            errors.add(new FieldErrorDetail("teacherApplication",
                    teacherText == null || teacherText.isEmpty() ? "REQUIRED" : "OUT_OF_RANGE",
                    "核验说明需为5至500字"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.fields(errors);
        }
        return new Normalized(loginId, password, displayName, schoolId, role,
                "TEACHER".equals(role) ? teacherText : null);
    }

    private String fingerprint(Normalized normalized) {
        String secret = properties.getFingerprintKey();
        if (secret == null || secret.isBlank() || properties.getKeyId() == null || properties.getKeyId().isBlank()) {
            throw new IllegalStateException("缺少 app.registration.fingerprint-key");
        }
        String canonical = lengthPrefixed(normalized.loginId())
                + lengthPrefixed(normalized.password())
                + lengthPrefixed(normalized.displayName())
                + lengthPrefixed(normalized.schoolId())
                + lengthPrefixed(normalized.role())
                + lengthPrefixed(normalized.teacherApplication() == null ? "" : normalized.teacherApplication());
        return Hashing.hmacSha256Hex(secret, properties.getKeyId() + "|" + canonical);
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value + "|";
    }

    private String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (idempotencyKey.length() > 128 || idempotencyKey.chars().anyMatch(ch -> ch < 0x20 || ch > 0x7E)) {
            throw new ApiException(ApiCode.IDEMPOTENCY_KEY_REQUIRED, "幂等键必须为1～128个可打印ASCII字符");
        }
        return idempotencyKey;
    }

    private String write(RegistrationReceipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存注册回执", ex);
        }
    }

    private RegistrationReceipt readReceipt(String json) {
        try {
            return objectMapper.readValue(json, RegistrationReceipt.class);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取注册回执", ex);
        }
    }

    private record Normalized(String loginId, String password, String displayName, String schoolId,
                              String role, String teacherApplication) {
    }
}
