package cn.gulupai.server.support;

import cn.gulupai.server.common.audit.AuditService;
import cn.gulupai.server.common.security.Hashing;
import cn.gulupai.server.common.validation.Ids;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 帮助文章受控导入。先校验整份文件，dry-run 不写库；apply 整批成功或整批回滚。
 */
@Service
public class HelpCatalogImportService {

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.(\\d+|\\*)");
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AuditService audit;
    private final Clock clock;

    public HelpCatalogImportService(ObjectMapper objectMapper, JdbcTemplate jdbc,
                                    TransactionTemplate transactions, AuditService audit, Clock clock) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
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
            for (Article article : input.articles()) {
                upsert(article, now);
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
        if (!Ids.isValid(article.id())) {
            errors.add("第" + row + "行：文章编号无效");
        } else if (!seen.add(article.id())) {
            errors.add("第" + row + "行：文章编号重复 " + article.id());
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
        if (article.summary() != null && article.summary().length() > 500) {
            errors.add("第" + row + "行：摘要不超过500字符");
        }
        if (article.body() == null || article.body().isBlank() || article.body().length() > 10000) {
            errors.add("第" + row + "行：正文必填且不超过10000字符");
        }
        if (article.category() == null || article.category().isBlank() || article.category().length() > 40) {
            errors.add("第" + row + "行：分类必填且不超过40字符");
        }
        if (article.appVersionRange() == null || !VERSION.matcher(article.appVersionRange()).matches()) {
            errors.add("第" + row + "行：版本范围必须是三段版本或 MAJOR.MINOR.*");
        }
    }

    private String classify(Article article) {
        ArticleRow current = find(article.id());
        if (current == null) {
            return "CREATED";
        }
        return same(current, article) ? "UNCHANGED" : "UPDATED";
    }

    private void upsert(Article article, OffsetDateTime now) {
        String action = classify(article);
        String summary = article.summary() == null ? "" : article.summary();
        String squareId = blankToNull(article.squareId());
        if ("CREATED".equals(action)) {
            jdbc.update("""
                    INSERT INTO help_article
                        (id, audience, square_id, title, summary, body, category, app_version_range,
                         published, revision, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, article.id(), article.audience(), squareId, article.title(), summary, article.body(),
                    article.category(), article.appVersionRange(), article.published(), now);
            return;
        }
        if ("UPDATED".equals(action)) {
            jdbc.update("""
                    UPDATE help_article
                    SET audience=?, square_id=?, title=?, summary=?, body=?, category=?,
                        app_version_range=?, published=?, revision=revision+1, updated_at=?
                    WHERE id=?
                    """, article.audience(), squareId, article.title(), summary, article.body(),
                    article.category(), article.appVersionRange(), article.published(), now, article.id());
        }
    }

    private boolean squareExists(String squareId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM square WHERE id=?", Integer.class, squareId);
        return count != null && count > 0;
    }

    private ArticleRow find(String id) {
        List<ArticleRow> rows = jdbc.query("""
                SELECT audience, square_id, title, summary, body, category, app_version_range, published
                FROM help_article WHERE id=?
                """, (rs, n) -> new ArticleRow(rs.getString("audience"), rs.getString("square_id"),
                rs.getString("title"), rs.getString("summary"), rs.getString("body"),
                rs.getString("category"), rs.getString("app_version_range"), rs.getBoolean("published")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean same(ArticleRow current, Article article) {
        return current.audience().equals(article.audience())
                && java.util.Objects.equals(current.squareId(), blankToNull(article.squareId()))
                && current.title().equals(article.title())
                && current.summary().equals(article.summary() == null ? "" : article.summary())
                && current.body().equals(article.body())
                && current.category().equals(article.category())
                && current.appVersionRange().equals(article.appVersionRange())
                && current.published() == article.published();
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

    public record HelpFile(List<Article> articles) {
    }

    public record Article(String id, String audience, String squareId, String title, String summary,
                          String body, String category, String appVersionRange, boolean published) {
    }

    private record ArticleRow(String audience, String squareId, String title, String summary, String body,
                              String category, String appVersionRange, boolean published) {
    }

    public record ImportSummary(int total, int created, int updated, int unchanged, boolean dryRun, String inputHash) {
        public String describe() {
            return "帮助导入：共" + total + " 新增" + created + " 更新" + updated + " 未改" + unchanged
                    + " dryRun=" + dryRun;
        }
    }
}
