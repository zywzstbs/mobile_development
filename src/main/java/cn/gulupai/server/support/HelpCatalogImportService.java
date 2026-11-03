package cn.gulupai.server.support;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.common.validation.Ids;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 帮助文章受控导入。文件必须给出客户端实际读取的结构化内容。
 *
 * <p>映射：steps→steps_json 与 body（换行拼接）；failureAction→failure_action 与 summary；
 * outcomes/prerequisites/roles/entry 分别写入对应列。body 与 summary 不再作为内容来源。
 * 旧正文无法无损变成步骤时，dry-run 和 apply 都拒绝，不截断、不编造入口。
 *
 * <p>内容与库内不一致时必须带上当前 revision。相同内容不推进条目版本或包版本。
 * 写入与通用包推进在同一事务；冲突或失败整批回滚。
 */
@Service
public class HelpCatalogImportService {

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.(\\d+|\\*)");
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern ROLE = Pattern.compile("STUDENT|TEACHER|CLASS_ADMIN");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final int MAX_ITEMS = HelpArticleText.MAX_STEPS;
    private static final int MAX_TEXT = HelpArticleText.MAX_STEP_CHARS;
    private static final int MAX_BODY = HelpArticleText.MAX_DERIVED_BODY;

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuditService audit;
    private final Clock clock;
    private final HelpContentPack packs;

    public HelpCatalogImportService(ObjectMapper objectMapper, JdbcTemplate jdbc,
                                    TransactionTemplate transactions, AuditService audit, Clock clock,
                                    HelpContentPack packs) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
        this.packs = packs;
    }

    public ImportSummary importArticles(Path file, boolean dryRun, String actorId) {
        byte[] bytes = read(file);
        HelpFile input = parse(bytes);
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (input.articles() == null || input.articles().isEmpty()) {
            errors.add("articles 不能为空");
        } else {
            for (int i = 0; i < input.articles().size(); i++) {
                validate(input.articles().get(i), i + 1, seen, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Article article : input.articles()) {
            switch (classify(article)) {
                case "CREATED" -> created++;
                case "UPDATED" -> updated++;
                default -> unchanged++;
            }
        }
        ImportSummary summary = new ImportSummary(input.articles().size(), created, updated, unchanged,
                dryRun, Hashing.sha256Hex(bytes));
        if (dryRun) {
            return summary;
        }
        transactions.executeWithoutResult(status -> {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            boolean affectsPack = false;
            for (Article article : input.articles()) {
                if (applyLocked(article, now)) {
                    affectsPack = true;
                }
            }
            if (affectsPack) {
                packs.advance(now);
            }
            audit.record(actorId, "HELP_IMPORT", "HELP_ARTICLE", null, null, null,
                    "count=" + input.articles().size());
        });
        return summary;
    }

    private void validate(Article article, int row, Set<String> seen, List<String> errors) {
        if (article == null) {
            errors.add("第" + row + "行：文章不能为空");
            return;
        }
        int before = errors.size();
        String id = article.id();
        if (!Ids.isValid(id)) {
            errors.add("第" + row + "行：文章编号无效");
            id = "第" + row + "行";
        } else if (!seen.add(id)) {
            errors.add("第" + row + "行：文章编号重复 " + id);
        }
        if (!"GENERAL".equals(article.audience()) && !"COURSE".equals(article.audience())) {
            errors.add("第" + row + "行：audience 只能是 GENERAL 或 COURSE");
        }
        boolean hasSquare = article.squareId() != null && !article.squareId().isBlank();
        if ("GENERAL".equals(article.audience()) && hasSquare) {
            errors.add("第" + row + "行：通用帮助不能绑定广场");
        }
        if ("COURSE".equals(article.audience()) && !hasSquare) {
            errors.add("第" + row + "行：课程帮助必须填写 squareId");
        }
        if (hasSquare && !squareExists(article.squareId())) {
            errors.add("第" + row + "行：广场不存在 " + article.squareId());
        }
        if (article.title() == null || article.title().isBlank() || article.title().length() > 120) {
            errors.add("第" + row + "行：标题必填且不超过120字符");
        }
        if (article.category() == null || !TOKEN.matcher(article.category()).matches()) {
            errors.add("第" + row + "行：分类必须是大写标识且不超过40字符");
        }
        if (article.appVersionRange() == null || !VERSION.matcher(article.appVersionRange()).matches()) {
            errors.add("第" + row + "行：版本范围必须是三段版本或 MAJOR.MINOR.*");
        }
        if (article.published() == null) {
            errors.add("第" + row + "行：published 必须是布尔值");
        }
        if (article.expectedRevision() != null
                && (article.expectedRevision() < 1 || article.expectedRevision() > 999_999_999)) {
            errors.add("第" + row + "行：expectedRevision 必须是 1 到 999999999");
        }
        if (!structured(article)) {
            errors.add(legacyReason(article, row, id));
        } else {
            validateStructured(article, row, errors);
        }
        if (errors.size() == before && Ids.isValid(article.id())) {
            conflict(article, row, errors);
        }
    }

    private void validateStructured(Article article, int row, List<String> errors) {
        if (article.roles() == null || article.roles().size() > 8 || article.roles().stream().anyMatch(role ->
                role == null || !ROLE.matcher(role).matches())) {
            errors.add("第" + row + "行：roles 只能包含 STUDENT、TEACHER、CLASS_ADMIN，最多8个");
        }
        if (!validTexts(article.prerequisites(), true)) {
            errors.add("第" + row + "行：prerequisites 最多20条，每条不超过500字符");
        }
        if (article.steps() == null || article.steps().isEmpty() || article.steps().size() > MAX_ITEMS
                || article.steps().stream().anyMatch(step -> step == null || step.isBlank() || step.length() > MAX_TEXT)) {
            errors.add("第" + row + "行：steps 必须是1到20条非空步骤，每条不超过500字符");
        } else if (derivedBody(article.steps()).length() > MAX_BODY) {
            errors.add("第" + row + "行：steps 拼接后的正文超过" + MAX_BODY + "字符");
        }
        if (!validTexts(article.outcomes(), true)) {
            errors.add("第" + row + "行：outcomes 最多20条，每条不超过500字符");
        }
        if (article.failureAction() == null || article.failureAction().isBlank()
                || article.failureAction().length() > MAX_TEXT) {
            errors.add("第" + row + "行：failureAction 必填且不超过500字符");
        }
        if (article.entry() == null || article.entry().type() == null || !TOKEN.matcher(article.entry().type()).matches()) {
            errors.add("第" + row + "行：entry.type 必须是白名单页面标识");
        } else if (article.entry().id() != null && !article.entry().id().isBlank()
                && !Ids.isValid(article.entry().id())) {
            errors.add("第" + row + "行：entry.id 格式无效");
        }
        if (article.steps() != null && article.body() != null && !derivedBody(article.steps()).equals(article.body())) {
            errors.add("第" + row + "行：body 与 steps 不一致，不能同时保留两套正文");
        }
        if (article.failureAction() != null && article.summary() != null
                && !article.failureAction().equals(article.summary())) {
            errors.add("第" + row + "行：summary 与 failureAction 不一致");
        }
    }

    private static boolean validTexts(List<String> values, boolean allowEmptyItem) {
        if (values == null || values.size() > MAX_ITEMS) {
            return false;
        }
        for (String value : values) {
            if (value == null || value.length() > MAX_TEXT || (!allowEmptyItem && value.isBlank())) {
                return false;
            }
        }
        return true;
    }

    private static boolean structured(Article article) {
        return article.steps() != null && article.outcomes() != null && article.prerequisites() != null
                && article.roles() != null && article.failureAction() != null && article.entry() != null;
    }

    private static String legacyReason(Article article, int row, String id) {
        String body = article.body();
        if (body != null && !body.isBlank()) {
            if (body.length() > MAX_TEXT) {
                return "第" + row + "行 " + id + "：旧正文长度" + body.length()
                        + "超过单步500，不能截断成步骤，请改为 steps、outcomes、failureAction、entry";
            }
            return "第" + row + "行 " + id + "：仍是旧正文格式，不能从 body 生成步骤或页面入口，请提供 steps、outcomes、failureAction、entry";
        }
        return "第" + row + "行 " + id + "：缺少结构化字段 steps、outcomes、failureAction、entry、roles、prerequisites";
    }

    private void conflict(Article article, int row, List<String> errors) {
        ArticleRow current = find(article.id());
        if (current == null) {
            if (article.expectedRevision() != null) {
                errors.add("第" + row + "行 " + article.id() + "：新文章不能带 expectedRevision");
            }
            return;
        }
        if (same(current, article)) {
            return;
        }
        if (article.expectedRevision() == null) {
            errors.add("第" + row + "行 " + article.id() + "：内容与库内修订 " + current.revision()
                    + " 不一致，导入未带 expectedRevision，不会覆盖在线编辑");
            return;
        }
        if (article.expectedRevision() != current.revision()) {
            errors.add("第" + row + "行 " + article.id() + "：expectedRevision 为 "
                    + article.expectedRevision() + "，当前修订为 " + current.revision());
        }
    }

    private String classify(Article article) {
        ArticleRow current = find(article.id());
        if (current == null) {
            return "CREATED";
        }
        return same(current, article) ? "UNCHANGED" : "UPDATED";
    }

    /**
     * @return 这次写入是否改变通用包的可见输出
     */
    private boolean applyLocked(Article article, OffsetDateTime now) {
        ArticleRow locked = lock(article.id());
        if (locked == null) {
            if (article.expectedRevision() != null) {
                throw new IllegalArgumentException(article.id() + "：新文章不能带 expectedRevision");
            }
            insert(article, now);
            return HelpContentPack.visible(article.audience(), Boolean.TRUE.equals(article.published()));
        }
        if (same(locked, article)) {
            return false;
        }
        if (article.expectedRevision() == null) {
            throw new IllegalArgumentException(article.id() + "：内容与库内修订 " + locked.revision()
                    + " 不一致，导入未带 expectedRevision，不会覆盖在线编辑");
        }
        if (article.expectedRevision() != locked.revision()) {
            throw new IllegalArgumentException(article.id() + "：expectedRevision 为 "
                    + article.expectedRevision() + "，当前修订为 " + locked.revision());
        }
        String stepsText = derivedBody(article.steps());
        int updated = jdbc.update("""
                UPDATE help_article
                SET audience=?, scope=?, square_id=?, title=?, summary=?, body=?, category=?,
                    app_version_range=?, published=?, roles_json=?::jsonb, prerequisites_json=?::jsonb,
                    steps_json=?::jsonb, outcomes_json=?::jsonb, failure_action=?, entry_json=?::jsonb,
                    revision=revision+1, content_version=content_version+1, updated_at=?
                WHERE id=? AND revision=?
                """, article.audience(), article.audience(), blankToNull(article.squareId()), article.title(),
                article.failureAction(), stepsText, article.category(), article.appVersionRange(),
                article.published(), write(article.roles()), write(article.prerequisites()),
                write(article.steps()), write(article.outcomes()), article.failureAction(),
                write(article.entry()), now, article.id(), article.expectedRevision());
        if (updated != 1) {
            throw new IllegalArgumentException(article.id() + "：修订冲突，当前内容没有被覆盖");
        }
        boolean before = HelpContentPack.visible(locked.audience(), locked.published());
        boolean after = HelpContentPack.visible(article.audience(), Boolean.TRUE.equals(article.published()));
        return before || after;
    }

    private void insert(Article article, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO help_article
                    (id, audience, square_id, title, summary, body, category, app_version_range,
                     published, revision, updated_at, scope, roles_json, prerequisites_json,
                     steps_json, outcomes_json, failure_action, entry_json, content_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, 1)
                """, article.id(), article.audience(), blankToNull(article.squareId()), article.title(),
                article.failureAction(), derivedBody(article.steps()), article.category(),
                article.appVersionRange(), article.published(), now, article.audience(),
                write(article.roles()), write(article.prerequisites()), write(article.steps()),
                write(article.outcomes()), article.failureAction(), write(article.entry()));
    }

    private boolean squareExists(String squareId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM square WHERE id=?", Integer.class, squareId);
        return count != null && count > 0;
    }

    private ArticleRow find(String id) {
        List<ArticleRow> rows = jdbc.query(SELECT_ROW + " WHERE id=?", this::mapRow, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ArticleRow lock(String id) {
        List<ArticleRow> rows = jdbc.query(SELECT_ROW + " WHERE id=? FOR UPDATE", this::mapRow, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ArticleRow mapRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ArticleRow(rs.getString("audience"), rs.getString("square_id"), rs.getString("title"),
                rs.getString("summary"), rs.getString("body"), rs.getString("category"),
                rs.getString("app_version_range"), rs.getBoolean("published"), rs.getInt("revision"),
                readList(rs.getString("roles_json")), readList(rs.getString("prerequisites_json")),
                readList(rs.getString("steps_json")), readList(rs.getString("outcomes_json")),
                rs.getString("failure_action"), readEntry(rs.getString("entry_json")));
    }

    private boolean same(ArticleRow current, Article article) {
        String stepsText = derivedBody(article.steps());
        return current.audience().equals(article.audience())
                && Objects.equals(current.squareId(), blankToNull(article.squareId()))
                && current.title().equals(article.title())
                && current.summary().equals(article.failureAction())
                && current.body().equals(stepsText)
                && current.category().equals(article.category())
                && current.appVersionRange().equals(article.appVersionRange())
                && current.published() == Boolean.TRUE.equals(article.published())
                && current.roles().equals(article.roles())
                && current.prerequisites().equals(article.prerequisites())
                && current.steps().equals(article.steps())
                && current.outcomes().equals(article.outcomes())
                && current.failureAction().equals(article.failureAction())
                && current.entry().equals(normalizedEntry(article.entry()));
    }

    private static String derivedBody(List<String> steps) {
        return String.join("\n", steps);
    }

    private static Entry normalizedEntry(Entry entry) {
        if (entry == null) {
            return new Entry(null, null);
        }
        String id = entry.id() == null || entry.id().isBlank() ? null : entry.id();
        return new Entry(entry.type(), id);
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception ex) {
            throw new IllegalStateException("帮助结构化字段无法解析", ex);
        }
    }

    private Entry readEntry(String json) {
        if (json == null || json.isBlank()) {
            return new Entry(null, null);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String type = node.path("type").isMissingNode() || node.path("type").isNull()
                    || node.path("type").asText().isBlank() ? null : node.path("type").asText();
            String id = node.path("id").isMissingNode() || node.path("id").isNull()
                    || node.path("id").asText().isBlank() ? null : node.path("id").asText();
            return new Entry(type, id);
        } catch (Exception ex) {
            throw new IllegalStateException("帮助入口无法解析", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化帮助内容", ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取帮助文件: " + file, ex);
        }
    }

    private HelpFile parse(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, HelpFile.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("帮助文件不是合法JSON或包含未知字段: " + ex.getMessage(), ex);
        }
    }

    private static final String SELECT_ROW = """
            SELECT audience, square_id, title, summary, body, category, app_version_range, published, revision,
                   roles_json::text AS roles_json, prerequisites_json::text AS prerequisites_json,
                   steps_json::text AS steps_json, outcomes_json::text AS outcomes_json,
                   failure_action, entry_json::text AS entry_json
            FROM help_article
            """;

    public record HelpFile(List<Article> articles) {
    }

    public record Entry(String type, String id) {
    }

    public record Article(String id, String audience, String squareId, String title, String summary,
                          String body, String category, List<String> roles, String appVersionRange,
                          List<String> prerequisites, List<String> steps, List<String> outcomes,
                          String failureAction, Entry entry, Boolean published, Integer expectedRevision) {
    }

    private record ArticleRow(String audience, String squareId, String title, String summary, String body,
                              String category, String appVersionRange, boolean published, int revision,
                              List<String> roles, List<String> prerequisites, List<String> steps,
                              List<String> outcomes, String failureAction, Entry entry) {
    }

    public record ImportSummary(int total, int created, int updated, int unchanged, boolean dryRun, String inputHash) {
        public String describe() {
            return "帮助导入：共" + total + " 新增" + created + " 更新" + updated + " 未改" + unchanged
                    + " dryRun=" + dryRun;
        }
    }
}
