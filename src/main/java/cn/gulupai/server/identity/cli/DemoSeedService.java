package cn.gulupai.server.identity.cli;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.course.api.CourseSlotRequest;
import cn.gulupai.server.identity.cli.AcademicImportService.AcademicFile;
import cn.gulupai.server.identity.cli.AcademicImportService.CourseNameInput;
import cn.gulupai.server.identity.cli.AcademicImportService.EnrollmentInput;
import cn.gulupai.server.identity.cli.AcademicImportService.PeriodInput;
import cn.gulupai.server.identity.cli.AcademicImportService.TermInput;
import cn.gulupai.server.identity.cli.AcademicImportService.VerifiedCourseInput;
import cn.gulupai.server.identity.cli.AdminImportService.ImportFile;
import cn.gulupai.server.identity.cli.AdminImportService.SchoolInput;
import cn.gulupai.server.identity.cli.AdminImportService.UserInput;
import cn.gulupai.server.support.HelpCatalogImportService;
import cn.gulupai.server.support.OperatorGrantAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 演示场景构造器。口令只写入调用方指定的私有文件，不出现在摘要或仓库样例里。
 */
@Service
public class DemoSeedService {

    static final String SCENARIO_FULL = "full";
    private static final String SCHOOL = "school_demo_full";
    private static final String TERM = "term_demo_full";
    private static final String MOBILE = "vc_demo_full_mobile";
    private static final String DATABASE = "vc_demo_full_db";
    private static final String OPERATOR = "user_demo_full_operator";
    private static final Path HELP_FILE = Path.of("admin/fixtures/p16-help.json");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final AdminImportService userImport;
    private final AcademicImportService academicImport;
    private final HelpCatalogImportService helpImport;
    private final OperatorGrantAdminService grants;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final DemoScenarioService scenarios;

    public DemoSeedService(ObjectMapper objectMapper, AdminImportService userImport,
                           AcademicImportService academicImport, HelpCatalogImportService helpImport,
                           OperatorGrantAdminService grants, JdbcTemplate jdbc, AuditService audit,
                           DemoScenarioService scenarios) {
        this.objectMapper = objectMapper;
        this.userImport = userImport;
        this.academicImport = academicImport;
        this.helpImport = helpImport;
        this.grants = grants;
        this.jdbc = jdbc;
        this.audit = audit;
        this.scenarios = scenarios;
    }

    public SeedSummary seed(String scenario, boolean dryRun, Path credentialsFile, String actorId) {
        if (!SCENARIO_FULL.equals(scenario)) {
            throw new IllegalArgumentException("只支持演示场景 full");
        }
        if (!dryRun && (credentialsFile == null || credentialsFile.toString().isBlank())) {
            throw new IllegalArgumentException("demo-seed 写入时需要 --app.cli.credentials-file");
        }
        List<Person> people = roster();
        Map<String, String> passwords = dryRun ? generated(people) : loadOrCreatePasswords(people, credentialsFile);
        Path userFile = null;
        Path academicFile = null;
        try {
            userFile = writeJson(new ImportFile(List.of(new SchoolInput(SCHOOL, "演示大学", "Asia/Shanghai")),
                    userInputs(people, passwords)));
            userImport.importUsers(userFile, dryRun);
            if (!dryRun) {
                academicFile = writeJson(academicFile());
                academicImport.importAcademic(academicFile, false);
                helpImport.importArticles(HELP_FILE, false, actorId);
                for (String capability : List.of(
                        "HELP_ADMIN", "HELP_CONTENT_WRITE", "SUPPORT_HANDLE", "CONTENT_REVIEW")) {
                    grants.grant(OPERATOR, capability, "GLOBAL", null, true, false, actorId);
                }
                ensureSquares();
                scenarios.seed();
                audit.record(actorId, "DEMO_SEED", "SCHOOL", SCHOOL, null, null, "scenario=full");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法准备演示导入文件", ex);
        } finally {
            deleteIfExists(userFile);
            deleteIfExists(academicFile);
        }
        return new SeedSummary(scenario, people.size(), 2, 18, 4, dryRun);
    }

    private List<Person> roster() {
        List<Person> people = new ArrayList<>();
        people.add(new Person("user_demo_full_teacher_01", "teacher_full_01", null, "演示教师甲", List.of("TEACHER")));
        people.add(new Person("user_demo_full_teacher_02", "teacher_full_02", null, "演示教师乙", List.of("TEACHER")));
        people.add(new Person("user_demo_full_assistant_01", "assistant_full_01", "9001", "演示助理甲",
                List.of("STUDENT", "CLASS_ADMIN")));
        people.add(new Person("user_demo_full_assistant_02", "assistant_full_02", "9002", "演示助理乙",
                List.of("STUDENT", "CLASS_ADMIN")));
        for (int i = 1; i <= 16; i++) {
            String suffix = String.format("%02d", i);
            people.add(new Person("user_demo_full_student_" + suffix, "student_full_" + suffix,
                    String.valueOf(9100 + i), "演示学生" + suffix, List.of("STUDENT")));
        }
        people.add(new Person(OPERATOR, "operator_full_01", null, "演示受理员", List.of("STUDENT")));
        return people;
    }

    private AcademicFile academicFile() {
        List<EnrollmentInput> enrollments = new ArrayList<>();
        enrollments.add(new EnrollmentInput(MOBILE, "user_demo_full_assistant_01", true));
        enrollments.add(new EnrollmentInput(DATABASE, "user_demo_full_assistant_02", true));
        for (int i = 1; i <= 8; i++) {
            enrollments.add(new EnrollmentInput(MOBILE, "user_demo_full_student_" + String.format("%02d", i), true));
        }
        for (int i = 9; i <= 16; i++) {
            enrollments.add(new EnrollmentInput(DATABASE, "user_demo_full_student_" + String.format("%02d", i), true));
        }
        return new AcademicFile(
                List.of(new TermInput(TERM, SCHOOL, "演示学期", LocalDate.of(2026, 9, 1),
                        LocalDate.of(2027, 1, 15), 1)),
                List.of(
                        new PeriodInput("period_demo_full_1", SCHOOL, TERM, "第1—2节",
                                LocalTime.of(8, 0), LocalTime.of(9, 40), 1),
                        new PeriodInput("period_demo_full_2", SCHOOL, TERM, "第3—4节",
                                LocalTime.of(10, 0), LocalTime.of(11, 40), 2)),
                List.of(
                        new CourseNameInput("course_name_demo_full_mobile", SCHOOL, "移动应用开发实践",
                                List.of("移动应用开发")),
                        new CourseNameInput("course_name_demo_full_db", SCHOOL, "数据库系统原理",
                                List.of("数据库"))),
                List.of(
                        new VerifiedCourseInput(MOBILE, SCHOOL, TERM, "DEMO-MOBILE-01",
                                "course_name_demo_full_mobile", "user_demo_full_teacher_01",
                                List.of(slot(3, "period_demo_full_1"))),
                        new VerifiedCourseInput(DATABASE, SCHOOL, TERM, "DEMO-DB-01",
                                "course_name_demo_full_db", "user_demo_full_teacher_02",
                                List.of(slot(5, "period_demo_full_2")))),
                enrollments);
    }

    private static CourseSlotRequest slot(int weekday, String periodId) {
        return new CourseSlotRequest(weekday, periodId, "ALL", 1, 16, List.of());
    }

    private List<UserInput> userInputs(List<Person> people, Map<String, String> passwords) {
        List<UserInput> users = new ArrayList<>();
        for (Person person : people) {
            users.add(new UserInput(person.id(), SCHOOL, person.loginId(), person.studentNo(),
                    person.displayName(), passwords.get(person.loginId()), "VERIFIED", false,
                    person.roles(), List.of()));
        }
        return users;
    }

    private Map<String, String> generated(List<Person> people) {
        Map<String, String> passwords = new LinkedHashMap<>();
        for (Person person : people) {
            passwords.put(person.loginId(), password());
        }
        return passwords;
    }

    private Map<String, String> loadOrCreatePasswords(List<Person> people, Path file) {
        Map<String, String> existing = readPasswords(file);
        Map<String, String> passwords = new LinkedHashMap<>();
        for (Person person : people) {
            passwords.put(person.loginId(), existing.getOrDefault(person.loginId(), password()));
        }
        writePrivate(file, passwords);
        return passwords;
    }

    private Map<String, String> readPasswords(Path file) {
        Map<String, String> passwords = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return passwords;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int split = line.indexOf(' ');
                if (split <= 0) {
                    throw new IllegalArgumentException("凭据文件格式无效");
                }
                passwords.put(line.substring(0, split), line.substring(split + 1));
            }
            return passwords;
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取凭据文件", ex);
        }
    }

    private void writePrivate(Path file, Map<String, String> passwords) {
        StringBuilder body = new StringBuilder("# 演示登录凭据。不要提交到仓库，不要写入接口文档。\n");
        passwords.forEach((login, password) -> body.append(login).append(' ').append(password).append('\n'));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法写入凭据文件", ex);
        }
    }

    private void ensureSquares() {
        insertSquare("square_demo_full_mobile_a", MOBILE, "移动应用开发实践·历史甲", "演示教师甲", "DF-M-A",
                "demo-full-mobile-a");
        insertSquare("square_demo_full_mobile_b", MOBILE, "移动应用开发实践·历史乙", "演示教师甲", "DF-M-B",
                "demo-full-mobile-b");
        insertSquare("square_demo_full_db", DATABASE, "数据库系统原理", "演示教师乙", "DF-DB",
                "demo-full-db");
        insertSquare("square_demo_full_db_b", DATABASE, "数据库系统原理·历史乙", "演示教师乙", "DF-DB-B",
                "demo-full-db-b");
    }

    private void insertSquare(String id, String courseId, String name, String teacherName, String number, String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM square WHERE id=?", Integer.class, id);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO square
                    (id, verified_course_id, school_id, term_id, name, teacher_name, slots_json,
                     canonical_key, square_no, status)
                VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb, ?, ?, 'ACTIVE')
                """, id, courseId, SCHOOL, TERM, name, teacherName, Hashing.sha256Hex(key), number);
    }

    private Path writeJson(Object value) throws IOException {
        Path file = Files.createTempFile("gulupai-demo-", ".json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        objectMapper.writeValue(file.toFile(), value);
        return file;
    }

    private static void deleteIfExists(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // 临时导入文件删除失败不改变已经提交的业务数据。
            }
        }
    }

    private static String password() {
        StringBuilder value = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    private record Person(String id, String loginId, String studentNo, String displayName, List<String> roles) {
    }

    public record SeedSummary(String scenario, int users, int verifiedCourses, int enrollments, int squares,
                              boolean dryRun) {
        public String describe() {
            return "演示种子：" + scenario + " 用户" + users + " 真实班" + verifiedCourses
                    + " 选课" + enrollments + " 广场" + squares + " dryRun=" + dryRun;
        }
    }
}
