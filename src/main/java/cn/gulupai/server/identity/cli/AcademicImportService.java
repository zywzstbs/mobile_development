package cn.gulupai.server.identity.cli;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.common.validation.Ids;
import cn.gulupai.server.course.api.CourseSlotRequest;
import cn.gulupai.server.course.domain.CourseSlot;
import cn.gulupai.server.course.domain.SlotsCanonicalizer;
import cn.gulupai.server.course.domain.WeekPattern;
import cn.gulupai.server.course.persistence.CourseDictionaryRepository;
import cn.gulupai.server.course.persistence.CourseNameRow;
import cn.gulupai.server.course.persistence.PeriodRow;
import cn.gulupai.server.course.persistence.SchoolRow;
import cn.gulupai.server.course.persistence.TermRow;
import cn.gulupai.server.course.persistence.VerifiedCourseRepository;
import cn.gulupai.server.course.persistence.VerifiedCourseRow;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.UserRepository;
import cn.gulupai.server.identity.persistence.UserRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 学期/节次/课程名/真实教学班/选课事实的受控导入。
 *
 * <p>P01 已导入学校和用户；本命令按“学期→节次→课程名→真实教学班→选课事实”顺序验证，
 * 未知外键、重复序号、时区/时间格式错误或规范课程重复都会列出并整批拒绝。
 */
@Service
public class AcademicImportService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CourseDictionaryRepository dictionaryRepository;
    private final VerifiedCourseRepository verifiedCourseRepository;
    private final UserRepository userRepository;
    private final SlotsCanonicalizer canonicalizer;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    public AcademicImportService(ObjectMapper objectMapper,
                                 JdbcTemplate jdbcTemplate,
                                 CourseDictionaryRepository dictionaryRepository,
                                 VerifiedCourseRepository verifiedCourseRepository,
                                 UserRepository userRepository,
                                 SlotsCanonicalizer canonicalizer,
                                 TransactionTemplate transactionTemplate,
                                 AuditService auditService) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.dictionaryRepository = dictionaryRepository;
        this.verifiedCourseRepository = verifiedCourseRepository;
        this.userRepository = userRepository;
        this.canonicalizer = canonicalizer;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    public ImportSummary importAcademic(Path file, boolean dryRun) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取导入文件: " + file, ex);
        }
        AcademicFile input;
        try {
            input = objectMapper.readValue(bytes, AcademicFile.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("导入文件不是合法JSON或包含未知字段: " + ex.getMessage(), ex);
        }
        String inputHash = Hashing.sha256Hex(bytes);
        if (!dryRun) {
            ImportSummary reused = findReusableBatch(inputHash);
            if (reused != null) {
                return reused;
            }
        }
        Plan plan = validate(input);
        ImportSummary summary = new ImportSummary(plan.terms.size(), plan.periods.size(),
                plan.courseNames.size(), plan.verifiedCourses.size(), plan.enrollments.size(),
                dryRun, inputHash, false);
        if (dryRun) {
            return summary;
        }
        ImportSummary applied = transactionTemplate.execute(status -> {
            for (TermPlan term : plan.terms) {
                jdbcTemplate.update("""
                        INSERT INTO term (id, school_id, name, start_date, end_date, ordinal, revision)
                        VALUES (?, ?, ?, ?, ?, ?, 1)
                        ON CONFLICT (id) DO UPDATE
                        SET school_id = EXCLUDED.school_id, name = EXCLUDED.name,
                            start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date,
                            ordinal = EXCLUDED.ordinal, revision = term.revision + 1,
                            updated_at = CURRENT_TIMESTAMP
                        """, term.id(), term.schoolId(), term.name(), term.startDate(), term.endDate(),
                        term.ordinal());
            }
            for (PeriodPlan period : plan.periods) {
                jdbcTemplate.update("""
                        INSERT INTO period
                            (id, school_id, term_id, label, start_time, end_time, ordinal, revision)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                        ON CONFLICT (id) DO UPDATE
                        SET school_id = EXCLUDED.school_id, term_id = EXCLUDED.term_id,
                            label = EXCLUDED.label, start_time = EXCLUDED.start_time,
                            end_time = EXCLUDED.end_time, ordinal = EXCLUDED.ordinal,
                            revision = period.revision + 1, updated_at = CURRENT_TIMESTAMP
                        """, period.id(), period.schoolId(), period.termId(), period.label(),
                        period.startTime(), period.endTime(), period.ordinal());
            }
            for (CourseNamePlan courseName : plan.courseNames) {
                jdbcTemplate.update("""
                        INSERT INTO course_name
                            (id, school_id, full_name, normalized_name, aliases_json, revision)
                        VALUES (?, ?, ?, ?, ?::jsonb, 1)
                        ON CONFLICT (id) DO UPDATE
                        SET school_id = EXCLUDED.school_id, full_name = EXCLUDED.full_name,
                            normalized_name = EXCLUDED.normalized_name,
                            aliases_json = EXCLUDED.aliases_json,
                            revision = course_name.revision + 1, updated_at = CURRENT_TIMESTAMP
                        """, courseName.id(), courseName.schoolId(), courseName.fullName(),
                        courseName.normalizedName(), writeJson(courseName.aliases()));
            }
            for (VerifiedCoursePlan course : plan.verifiedCourses) {
                jdbcTemplate.update("""
                        INSERT INTO verified_course
                            (id, school_id, term_id, external_key, course_name_id,
                             teacher_user_id, slots_json, canonical_key, revision)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1)
                        ON CONFLICT (id) DO UPDATE
                        SET school_id = EXCLUDED.school_id, term_id = EXCLUDED.term_id,
                            external_key = EXCLUDED.external_key,
                            course_name_id = EXCLUDED.course_name_id,
                            teacher_user_id = EXCLUDED.teacher_user_id,
                            slots_json = EXCLUDED.slots_json,
                            canonical_key = EXCLUDED.canonical_key,
                            revision = verified_course.revision + 1, updated_at = CURRENT_TIMESTAMP
                        """, course.id(), course.schoolId(), course.termId(), course.externalKey(),
                        course.courseNameId(), course.teacherUserId(), writeJson(course.slots()),
                        course.canonicalKey());
            }
            String batchId = UUID.randomUUID().toString();
            for (EnrollmentPlan enrollment : plan.enrollments) {
                jdbcTemplate.update("""
                        INSERT INTO verified_enrollment
                            (id, verified_course_id, user_id, source_batch_id, valid, revision)
                        VALUES (?, ?, ?, ?, ?, 1)
                        ON CONFLICT (verified_course_id, user_id) DO UPDATE
                        SET source_batch_id = EXCLUDED.source_batch_id, valid = EXCLUDED.valid,
                            revision = verified_enrollment.revision + 1,
                            updated_at = CURRENT_TIMESTAMP
                        """, UUID.randomUUID().toString(), enrollment.verifiedCourseId(),
                        enrollment.userId(), batchId, enrollment.valid());
            }
            Map<String, Object> summaryJson = new LinkedHashMap<>();
            summaryJson.put("terms", plan.terms.size());
            summaryJson.put("periods", plan.periods.size());
            summaryJson.put("courseNames", plan.courseNames.size());
            summaryJson.put("verifiedCourses", plan.verifiedCourses.size());
            summaryJson.put("enrollments", plan.enrollments.size());
            jdbcTemplate.update("""
                    INSERT INTO import_batch
                        (id, kind, input_hash, actor_id, status, summary_json, revision)
                    VALUES (?, 'ACADEMIC', ?, 'cli', 'APPLIED', ?::jsonb, 1)
                    """, batchId, inputHash, writeJson(summaryJson));
            auditService.record("cli", "ADMIN_IMPORT_ACADEMIC", "IMPORT_BATCH", batchId,
                    null, null, "terms=" + plan.terms.size() + ",courses=" + plan.verifiedCourses.size()
                            + ",enrollments=" + plan.enrollments.size());
            return summary;
        });
        return applied == null ? summary : applied;
    }

    private ImportSummary findReusableBatch(String inputHash) {
        List<String> summaries = jdbcTemplate.queryForList("""
                SELECT summary_json::text FROM import_batch
                WHERE kind = 'ACADEMIC' AND input_hash = ? AND status = 'APPLIED'
                ORDER BY created_at DESC LIMIT 1
                """, String.class, inputHash);
        if (summaries.isEmpty()) {
            return null;
        }
        try {
            Map<?, ?> summary = objectMapper.readValue(summaries.getFirst(), Map.class);
            return new ImportSummary(
                    count(summary, "terms"), count(summary, "periods"),
                    count(summary, "courseNames"), count(summary, "verifiedCourses"),
                    count(summary, "enrollments"), false, inputHash, true);
        } catch (Exception ex) {
            return null;
        }
    }

    private Plan validate(AcademicFile input) {
        List<String> errors = new ArrayList<>();
        if (input == null) {
            throw new IllegalArgumentException("导入文件为空");
        }

        Map<String, SchoolRow> schools = new HashMap<>();
        Map<String, TermPlan> terms = new LinkedHashMap<>();
        Map<String, PeriodPlan> periods = new LinkedHashMap<>();
        Map<String, CourseNamePlan> courseNames = new LinkedHashMap<>();
        Map<String, VerifiedCoursePlan> verifiedCourses = new LinkedHashMap<>();
        List<EnrollmentPlan> enrollments = new ArrayList<>();
        Set<String> termOrdinals = new HashSet<>();
        Set<String> periodOrdinals = new HashSet<>();
        Set<String> courseNormalized = new HashSet<>();
        Set<String> verifiedCanonical = new HashSet<>();
        Set<String> verifiedExternal = new HashSet<>();
        Set<String> enrollmentKeys = new HashSet<>();

        if (input.terms() != null) {
            for (TermInput term : input.terms()) {
                String id = requireId(term.id(), "学期ID", errors);
                String schoolId = requireId(term.schoolId(), "学校ID", errors);
                if (id == null || schoolId == null) {
                    continue;
                }
                if (terms.containsKey(id)) {
                    errors.add("学期ID重复: " + id);
                    continue;
                }
                SchoolRow school = school(schools, schoolId);
                if (school == null) {
                    errors.add("学期 " + id + " 引用未知学校: " + schoolId);
                    continue;
                }
                String name = requireText(term.name(), "学期名称", 80, errors);
                LocalDate start = term.startDate();
                LocalDate end = term.endDate();
                if (start == null || end == null || start.isAfter(end)) {
                    errors.add("学期 " + id + " 日期无效");
                    continue;
                }
                int ordinal = term.ordinal() == null ? 0 : term.ordinal();
                if (ordinal < 0) {
                    errors.add("学期 " + id + " 序号无效");
                    continue;
                }
                if (!termOrdinals.add(schoolId + "|" + ordinal)) {
                    errors.add("同一学校学期序号重复: " + ordinal);
                    continue;
                }
                TermRow existingTerm = dictionaryRepository.findTerm(id).orElse(null);
                if (existingTerm != null && !existingTerm.schoolId().equals(schoolId)) {
                    errors.add("学期 " + id + " 已属于其他学校");
                    continue;
                }
                Integer conflict = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM term
                        WHERE school_id = ? AND ordinal = ? AND id <> ?
                        """, Integer.class, schoolId, ordinal, id);
                if (conflict != null && conflict > 0) {
                    errors.add("学期 " + id + " 与已有学校学期序号冲突: " + ordinal);
                    continue;
                }
                terms.put(id, new TermPlan(id, schoolId, name == null ? "" : name, start, end, ordinal));
            }
        }

        if (input.periods() != null) {
            for (PeriodInput period : input.periods()) {
                String id = requireId(period.id(), "节次ID", errors);
                String schoolId = requireId(period.schoolId(), "学校ID", errors);
                String termId = requireId(period.termId(), "学期ID", errors);
                if (id == null || schoolId == null || termId == null) {
                    continue;
                }
                if (periods.containsKey(id)) {
                    errors.add("节次ID重复: " + id);
                    continue;
                }
                TermPlan term = terms.get(termId);
                if (term == null || !term.schoolId().equals(schoolId)) {
                    errors.add("节次 " + id + " 引用未知或跨校学期: " + termId);
                    continue;
                }
                String label = requireText(period.label(), "节次名称", 40, errors);
                LocalTime start = period.startTime();
                LocalTime end = period.endTime();
                if (start == null || end == null || !start.isBefore(end)) {
                    errors.add("节次 " + id + " 时间无效");
                    continue;
                }
                int ordinal = period.ordinal() == null ? 0 : period.ordinal();
                if (ordinal < 0) {
                    errors.add("节次 " + id + " 序号无效");
                    continue;
                }
                if (!periodOrdinals.add(termId + "|" + ordinal)) {
                    errors.add("同一学期节次序号重复: " + ordinal);
                    continue;
                }
                PeriodRow existingPeriod = dictionaryRepository.findPeriod(id).orElse(null);
                if (existingPeriod != null
                        && (!existingPeriod.schoolId().equals(schoolId)
                        || !existingPeriod.termId().equals(termId))) {
                    errors.add("节次 " + id + " 已属于其他学校或学期");
                    continue;
                }
                Integer conflict = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM period
                        WHERE school_id = ? AND term_id = ? AND ordinal = ? AND id <> ?
                        """, Integer.class, schoolId, termId, ordinal, id);
                if (conflict != null && conflict > 0) {
                    errors.add("节次 " + id + " 与已有学期节次序号冲突: " + ordinal);
                    continue;
                }
                periods.put(id, new PeriodPlan(id, schoolId, termId,
                        label == null ? "" : label, start, end, ordinal));
            }
        }

        if (input.courseNames() != null) {
            for (CourseNameInput courseName : input.courseNames()) {
                String id = requireId(courseName.id(), "课程名ID", errors);
                String schoolId = requireId(courseName.schoolId(), "学校ID", errors);
                if (id == null || schoolId == null) {
                    continue;
                }
                if (courseNames.containsKey(id)) {
                    errors.add("课程名ID重复: " + id);
                    continue;
                }
                if (school(schools, schoolId) == null) {
                    errors.add("课程名 " + id + " 引用未知学校: " + schoolId);
                    continue;
                }
                String fullName = requireText(courseName.fullName(), "课程名", 160, errors);
                if (fullName == null) {
                    continue;
                }
                String normalized = canonicalizer.normalizedKeyText(fullName);
                CourseNameRow existingCourseName = dictionaryRepository.findCourseName(id).orElse(null);
                if (existingCourseName != null && !existingCourseName.schoolId().equals(schoolId)) {
                    errors.add("课程名 " + id + " 已属于其他学校");
                    continue;
                }
                if (!courseNormalized.add(schoolId + "|" + normalized)) {
                    errors.add("同一学校课程名规范重复: " + fullName);
                    continue;
                }
                Integer conflict = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM course_name
                        WHERE school_id = ? AND normalized_name = ? AND id <> ?
                        """, Integer.class, schoolId, normalized, id);
                if (conflict != null && conflict > 0) {
                    errors.add("课程名 " + id + " 与已有规范课程名冲突: " + fullName);
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                if (courseName.aliases() != null) {
                    if (courseName.aliases().size() > 20) {
                        errors.add("课程名 " + id + " 别名超过20项");
                    }
                    for (String alias : courseName.aliases()) {
                        String value = requireText(alias, "课程别名", 160, errors);
                        if (value != null) {
                            aliases.add(value);
                        }
                    }
                }
                courseNames.put(id, new CourseNamePlan(id, schoolId, fullName, normalized,
                        List.copyOf(aliases)));
            }
        }

        Map<String, List<PeriodRow>> dbPeriods = new HashMap<>();
        if (input.verifiedCourses() != null) {
            for (VerifiedCourseInput course : input.verifiedCourses()) {
                String id = requireId(course.id(), "真实课程ID", errors);
                String schoolId = requireId(course.schoolId(), "学校ID", errors);
                String termId = requireId(course.termId(), "学期ID", errors);
                String courseNameId = requireId(course.courseNameId(), "课程名ID", errors);
                String teacherUserId = requireId(course.teacherUserId(), "教师用户ID", errors);
                if (id == null || schoolId == null || termId == null
                        || courseNameId == null || teacherUserId == null) {
                    continue;
                }
                if (verifiedCourses.containsKey(id)) {
                    errors.add("真实课程ID重复: " + id);
                    continue;
                }
                TermPlan term = terms.get(termId);
                if (term == null || !term.schoolId().equals(schoolId)) {
                    // 允许引用此前已导入的学期
                    TermRow existing = dictionaryRepository.findTerm(termId).orElse(null);
                    if (existing == null || !existing.schoolId().equals(schoolId)) {
                        errors.add("真实课程 " + id + " 引用未知或跨校学期: " + termId);
                        continue;
                    }
                }
                CourseNamePlan courseName = courseNames.get(courseNameId);
                String fullName;
                if (courseName != null) {
                    fullName = courseName.fullName();
                } else {
                    CourseNameRow existing = dictionaryRepository.findCourseName(courseNameId).orElse(null);
                    if (existing == null || !existing.schoolId().equals(schoolId)) {
                        errors.add("真实课程 " + id + " 引用未知或跨校课程名: " + courseNameId);
                        continue;
                    }
                    fullName = existing.fullName();
                }
                UserRow teacher = userRepository.findById(teacherUserId).orElse(null);
                if (teacher == null || !teacher.schoolId().equals(schoolId)) {
                    errors.add("真实课程 " + id + " 引用未知或跨校教师: " + teacherUserId);
                    continue;
                }
                if (!userRepository.findRoles(teacherUserId).contains(Role.TEACHER)) {
                    errors.add("真实课程 " + id + " 的教师缺少TEACHER角色: " + teacherUserId);
                    continue;
                }
                String externalKey = requireText(course.externalKey(), "外部课程键", 120, errors);
                if (externalKey == null) {
                    continue;
                }
                Set<String> periodIds = new HashSet<>();
                List<PeriodRow> filePeriods = periods.values().stream()
                        .filter(period -> period.termId().equals(termId))
                        .map(period -> new PeriodRow(period.id(), period.schoolId(), period.termId(),
                                period.label(), period.startTime(), period.endTime(), period.ordinal()))
                        .toList();
                filePeriods.forEach(period -> periodIds.add(period.id()));
                List<PeriodRow> existingPeriods = dbPeriods.computeIfAbsent(termId,
                        key -> dictionaryRepository.listPeriods(schoolId, termId));
                existingPeriods.forEach(period -> periodIds.add(period.id()));
                if (!verifiedExternal.add(schoolId + "|" + termId + "|" + externalKey)) {
                    errors.add("真实课程外部键重复: " + externalKey);
                    continue;
                }
                List<CourseSlot> slots = toSlots(course.slots(), periodIds, id, errors);
                String teacherName = canonicalizer.normalizeDisplayName(teacher.displayName());
                String canonicalKey = slots == null ? "" : canonicalizer.canonicalKey(
                        schoolId, termId, fullName, teacherName, slots);
                if (slots != null) {
                    if (!verifiedCanonical.add(canonicalKey)) {
                        errors.add("真实课程规范输入重复: " + id);
                        continue;
                    }
                    VerifiedCourseRow existingCanonical = verifiedCourseRepository
                            .findByCanonicalKey(canonicalKey).orElse(null);
                    if (existingCanonical != null && !existingCanonical.id().equals(id)) {
                        errors.add("真实课程 " + id + " 与已有规范课程重复: " + existingCanonical.id());
                        continue;
                    }
                    VerifiedCourseRow existingExternal = verifiedCourseRepository
                            .findByExternalKey(schoolId, termId, externalKey).orElse(null);
                    if (existingExternal != null && !existingExternal.id().equals(id)) {
                        errors.add("真实课程外部键已被占用: " + externalKey);
                        continue;
                    }
                    verifiedCourses.put(id, new VerifiedCoursePlan(id, schoolId, termId,
                            externalKey, courseNameId, teacherUserId, slots, canonicalKey));
                }
            }
        }

        if (input.enrollments() != null) {
            for (EnrollmentInput enrollment : input.enrollments()) {
                String verifiedCourseId = requireId(enrollment.verifiedCourseId(), "真实课程ID", errors);
                String userId = requireId(enrollment.userId(), "用户ID", errors);
                if (verifiedCourseId == null || userId == null) {
                    continue;
                }
                VerifiedCoursePlan course = verifiedCourses.get(verifiedCourseId);
                String courseSchool;
                if (course != null) {
                    courseSchool = course.schoolId();
                } else {
                    VerifiedCourseRow existing = verifiedCourseRepository.findById(verifiedCourseId)
                            .orElse(null);
                    if (existing == null) {
                        errors.add("选课事实引用未知真实课程: " + verifiedCourseId);
                        continue;
                    }
                    courseSchool = existing.schoolId();
                }
                UserRow user = userRepository.findById(userId).orElse(null);
                if (user == null || !user.schoolId().equals(courseSchool)) {
                    errors.add("选课事实引用未知或跨校用户: " + userId);
                    continue;
                }
                if (!enrollmentKeys.add(verifiedCourseId + "|" + userId)) {
                    errors.add("同一真实课程选课事实重复: " + userId);
                    continue;
                }
                enrollments.add(new EnrollmentPlan(verifiedCourseId, userId,
                        enrollment.valid() == null || enrollment.valid()));
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("导入校验失败:\n- " + String.join("\n- ", errors));
        }
        return new Plan(List.copyOf(terms.values()), List.copyOf(periods.values()),
                List.copyOf(courseNames.values()), List.copyOf(verifiedCourses.values()),
                List.copyOf(enrollments));
    }

    private List<CourseSlot> toSlots(List<CourseSlotRequest> slots, Set<String> periodIds,
                                     String courseId, List<String> errors) {
        if (slots == null || slots.isEmpty()) {
            errors.add("真实课程 " + courseId + " 至少需要一个课表时段");
            return null;
        }
        List<CourseSlot> domain = new ArrayList<>();
        try {
            for (CourseSlotRequest slot : slots) {
                domain.add(new CourseSlot(slot.weekday(), slot.periodId(),
                        WeekPattern.valueOf(slot.weekPattern()), slot.startWeek(), slot.endWeek(),
                        slot.customWeeks() == null ? List.of() : slot.customWeeks()));
            }
            return canonicalizer.normalize(domain, periodIds);
        } catch (Exception ex) {
            errors.add("真实课程 " + courseId + " 课表无效: " + ex.getMessage());
            return null;
        }
    }

    private SchoolRow school(Map<String, SchoolRow> cache, String schoolId) {
        if (cache.containsKey(schoolId)) {
            return cache.get(schoolId);
        }
        SchoolRow school = dictionaryRepository.findSchool(schoolId).orElse(null);
        cache.put(schoolId, school);
        return school;
    }

    private String requireId(String value, String field, List<String> errors) {
        String normalized = value == null ? "" : value.strip();
        if (!Ids.isValid(normalized)) {
            errors.add(field + "无效: " + value);
            return null;
        }
        return normalized;
    }

    private String requireText(String value, String field, int max, List<String> errors) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) {
            errors.add(field + "必须为1～" + max + "个字符");
            return null;
        }
        return normalized;
    }

    private static int count(Map<?, ?> summary, String key) {
        Object value = summary.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化导入数据", ex);
        }
    }

    public record ImportSummary(int terms, int periods, int courseNames, int verifiedCourses,
                                int enrollments, boolean dryRun, String inputHash, boolean reused) {
        public String describe() {
            return (dryRun ? "[dry-run] " : reused ? "[reused] " : "") + "学期 " + terms
                    + "，节次 " + periods + "，课程名 " + courseNames + "，真实课程 " + verifiedCourses
                    + "，选课事实 " + enrollments + "，输入摘要 " + inputHash;
        }
    }

    private record Plan(List<TermPlan> terms, List<PeriodPlan> periods,
                        List<CourseNamePlan> courseNames, List<VerifiedCoursePlan> verifiedCourses,
                        List<EnrollmentPlan> enrollments) {
    }

    private record TermPlan(String id, String schoolId, String name, LocalDate startDate,
                            LocalDate endDate, int ordinal) {
    }

    private record PeriodPlan(String id, String schoolId, String termId, String label,
                              LocalTime startTime, LocalTime endTime, int ordinal) {
    }

    private record CourseNamePlan(String id, String schoolId, String fullName,
                                  String normalizedName, List<String> aliases) {
    }

    private record VerifiedCoursePlan(String id, String schoolId, String termId, String externalKey,
                                      String courseNameId, String teacherUserId,
                                      List<CourseSlot> slots, String canonicalKey) {
    }

    private record EnrollmentPlan(String verifiedCourseId, String userId, boolean valid) {
    }

    public record AcademicFile(List<TermInput> terms, List<PeriodInput> periods,
                               List<CourseNameInput> courseNames,
                               List<VerifiedCourseInput> verifiedCourses,
                               List<EnrollmentInput> enrollments) {
    }

    public record TermInput(String id, String schoolId, String name, LocalDate startDate,
                            LocalDate endDate, Integer ordinal) {
    }

    public record PeriodInput(String id, String schoolId, String termId, String label,
                              LocalTime startTime, LocalTime endTime, Integer ordinal) {
    }

    public record CourseNameInput(String id, String schoolId, String fullName, List<String> aliases) {
    }

    public record VerifiedCourseInput(String id, String schoolId, String termId, String externalKey,
                                      String courseNameId, String teacherUserId,
                                      List<CourseSlotRequest> slots) {
    }

    public record EnrollmentInput(String verifiedCourseId, String userId, Boolean valid) {
    }
}
