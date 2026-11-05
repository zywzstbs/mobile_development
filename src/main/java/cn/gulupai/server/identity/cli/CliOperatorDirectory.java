package cn.gulupai.server.identity.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.security.auth.module.UnixSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 受控运维身份到审核主体的映射。
 *
 * <p>审核人的操作系统身份取自内核有效用户（{@code /proc/self/status} 的 euid，
 * 再用 {@code getpwuid} 解析用户名），不读取 {@code user.name} 或 {@code USER}。
 * 这两处都能在启动命令里改掉，不能当作审核人。
 *
 * <p>审核映射只来自类路径上的 {@code cli-operators.yml}。命令行、JVM 系统属性、
 * 环境变量和 Spring 配置里的 {@code app.cli.operators} 一律拒绝启动，避免一次
 * 命令临时换成另一个 subjectId。{@code java -jar} 的类路径由制品本身决定；
 * 若同时出现多份映射则拒绝，避免前面的 jar 盖住制品里的文件。
 * {@code subjectId} 写入 reviewed_by 和审计，并与申请人 userId 比较以拒绝自审。
 * {@code displayLabel} 只用于命令输出，不能当作身份。
 */
@Component
public class CliOperatorDirectory {

    static final String TRUSTED_OS_USER = "__TRUSTED_OS_USER__";
    static final String OVERRIDE_MESSAGE = "不能通过命令行、系统属性或环境变量覆盖审核映射";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> OPERATOR_FIELDS = Set.of("os-user", "subject-id", "display-label");

    private List<Operator> operators = List.of();

    public CliOperatorDirectory() {
    }

    @Autowired
    public CliOperatorDirectory(Environment environment) {
        rejectUntrustedOverrides(environment);
        this.operators = List.copyOf(readProtectedOperators());
    }

    public Reviewer requireCurrent() {
        return require(currentOsUser());
    }

    public Reviewer require(String osUser) {
        String current = osUser == null ? "" : osUser.strip();
        if (current.isEmpty()) {
            throw new IllegalArgumentException("无法确认当前操作系统用户");
        }
        List<Operator> matched = new ArrayList<>();
        for (Operator operator : operators) {
            if (operator != null && current.equals(text(operator.getOsUser()))) {
                matched.add(operator);
            }
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("当前操作系统用户未获注册审核授权");
        }
        if (matched.size() != 1) {
            throw new IllegalStateException("审核主体映射无效");
        }
        Operator operator = matched.getFirst();
        String subjectId = text(operator.getSubjectId());
        String displayLabel = text(operator.getDisplayLabel());
        if (!subjectId.matches("[A-Za-z0-9_-]{1,120}")) {
            throw new IllegalStateException("审核主体映射无效");
        }
        if (displayLabel.isEmpty() || displayLabel.equals(subjectId) || displayLabel.length() > 80) {
            throw new IllegalStateException("审核展示名必须与审计身份分开");
        }
        return new Reviewer(current, subjectId, displayLabel);
    }

    public void setOperators(List<Operator> operators) {
        this.operators = operators == null ? List.of() : List.copyOf(operators);
    }

    public List<Operator> getOperators() {
        return operators;
    }

    static void rejectUntrustedOverrides(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            throw new IllegalStateException("无法确认审核映射来源");
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (isOperatorKey(name) || jsonDefinesOperators(name, enumerable.getProperty(name))) {
                        throw new IllegalStateException(OVERRIDE_MESSAGE);
                    }
                }
            }
        }
    }

    static boolean isOperatorKey(String name) {
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace('_', '.')
                .replace('-', '.')
                .replace("[", ".")
                .replace("]", "");
        return normalized.equals("app.cli.operators") || normalized.startsWith("app.cli.operators.");
    }

    static String currentOsUser() {
        try {
            long effectiveUid = effectiveUid();
            UnixSystem unix = new UnixSystem();
            String username = unix.getUsername();
            if (unix.getUid() != effectiveUid || username == null || username.isBlank()) {
                throw new IllegalArgumentException("无法确认当前操作系统用户");
            }
            return username.strip();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("无法确认当前操作系统用户");
        }
    }

    private static boolean jsonDefinesOperators(String name, Object value) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('_', '.');
        if (!normalized.equals("spring.application.json") || value == null) {
            return false;
        }
        try {
            JsonNode operators = JSON.readTree(String.valueOf(value)).path("app").path("cli").get("operators");
            return operators != null && !operators.isNull();
        } catch (IOException ex) {
            throw new IllegalStateException(OVERRIDE_MESSAGE);
        }
    }

    private static long effectiveUid() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (!line.startsWith("Uid:")) {
                    continue;
                }
                String[] fields = line.substring("Uid:".length()).trim().split("\\s+");
                if (fields.length < 2) {
                    break;
                }
                return Long.parseLong(fields[1]);
            }
        } catch (IOException | NumberFormatException ex) {
            throw new IllegalArgumentException("无法确认当前操作系统用户");
        }
        throw new IllegalArgumentException("无法确认当前操作系统用户");
    }

    private static List<Operator> readProtectedOperators() {
        URL resource = locateOperatorsResource();
        if (resource == null) {
            return List.of();
        }
        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> false);
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(64 * 1024);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (InputStream in = resource.openStream()) {
            Object loaded = yaml.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return parseOperators(loaded);
        } catch (IOException ex) {
            throw new IllegalStateException("审核主体映射无效");
        }
    }

    private static URL locateOperatorsResource() {
        Enumeration<URL> urls;
        try {
            urls = CliOperatorDirectory.class.getClassLoader().getResources("cli-operators.yml");
        } catch (IOException ex) {
            throw new IllegalStateException("审核主体映射无效");
        }
        List<URL> found = new ArrayList<>();
        while (urls.hasMoreElements()) {
            found.add(urls.nextElement());
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        URL testResource = null;
        URL packaged = null;
        for (URL url : found) {
            if (url.toString().contains("/test-classes/")) {
                if (testResource != null) {
                    throw new IllegalStateException("审核主体映射无效");
                }
                testResource = url;
            } else if (packaged != null) {
                throw new IllegalStateException("审核主体映射无效");
            } else {
                packaged = url;
            }
        }
        if (testResource != null && packaged != null && "file".equals(packaged.getProtocol())) {
            return testResource;
        }
        throw new IllegalStateException("审核主体映射无效");
    }

    private static List<Operator> parseOperators(Object loaded) {
        if (loaded == null) {
            return List.of();
        }
        if (!(loaded instanceof java.util.Map<?, ?> root) || root.size() != 1 || !root.containsKey("operators")) {
            throw new IllegalStateException("审核主体映射无效");
        }
        Object raw = root.get("operators");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("审核主体映射无效");
        }
        String trustedUser = null;
        List<Operator> parsed = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof java.util.Map<?, ?> map) || !OPERATOR_FIELDS.containsAll(stringKeys(map))) {
                throw new IllegalStateException("审核主体映射无效");
            }
            Operator operator = new Operator();
            String osUser = scalar(map.get("os-user"));
            if (TRUSTED_OS_USER.equals(osUser)) {
                if (trustedUser == null) {
                    trustedUser = currentOsUser();
                }
                osUser = trustedUser;
            }
            operator.setOsUser(osUser);
            operator.setSubjectId(scalar(map.get("subject-id")));
            operator.setDisplayLabel(scalar(map.get("display-label")));
            parsed.add(operator);
        }
        return parsed;
    }

    private static Set<String> stringKeys(java.util.Map<?, ?> map) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !OPERATOR_FIELDS.contains(text)) {
                throw new IllegalStateException("审核主体映射无效");
            }
            keys.add(text);
        }
        if (!keys.equals(OPERATOR_FIELDS)) {
            throw new IllegalStateException("审核主体映射无效");
        }
        return keys;
    }

    private static String scalar(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalStateException("审核主体映射无效");
        }
        return text;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    public record Reviewer(String osUser, String subjectId, String displayLabel) {
    }

    public static class Operator {
        private String osUser;
        private String subjectId;
        private String displayLabel;

        public String getOsUser() {
            return osUser;
        }

        public void setOsUser(String osUser) {
            this.osUser = osUser;
        }

        public String getSubjectId() {
            return subjectId;
        }

        public void setSubjectId(String subjectId) {
            this.subjectId = subjectId;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }

        public void setDisplayLabel(String displayLabel) {
            this.displayLabel = displayLabel;
        }
    }
}
