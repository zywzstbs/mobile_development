package cn.gulupai.server.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P16 第一步：Spring 实际映射与 07 索引、接口覆盖清单精确对齐。
 * 健康检查和文件字节端点不属于 281 个业务接口。
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteCoverageIT {

    private static final String API_PREFIX = "/api/v1";
    private static final Pattern INDEX_ROW = Pattern.compile(
            "^\\|([A-Z]+-\\d+)\\|`([A-Z]+) ([^`]+)`\\|");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void springMappingsMatchIndexAndChecklist() throws Exception {
        Map<String, String> checklist = readChecklist();
        Map<String, String> index = readIndexSummary();

        assertThat(checklist).hasSize(281);
        assertThat(index).hasSize(281);
        assertThat(index.keySet()).containsExactlyInAnyOrderElementsOf(checklist.keySet());

        List<String> documentMismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : checklist.entrySet()) {
            String indexed = index.get(entry.getKey());
            if (!entry.getValue().equals(indexed)) {
                documentMismatches.add(entry.getKey() + " 清单=" + entry.getValue() + " 索引=" + indexed);
            }
        }
        assertThat(documentMismatches).as("07 索引与 JSON 清单路由不一致").isEmpty();

        Set<String> expected = new TreeSet<>(checklist.values());
        assertThat(expected).as("方法+路径必须唯一").hasSize(281);

        Set<String> business = new TreeSet<>();
        Set<String> infrastructure = new TreeSet<>();
        Set<String> testFixtures = new TreeSet<>();
        Set<String> unexpected = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            assertThat(patterns).as(handler.toString()).isNotNull();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns.getPatternValues()) {
                if (methods.isEmpty()) {
                    classify(business, infrastructure, testFixtures, unexpected, handler, "*", pattern);
                } else {
                    for (RequestMethod method : methods) {
                        classify(business, infrastructure, testFixtures, unexpected, handler, method.name(), pattern);
                    }
                }
            }
        });

        assertThat(unexpected).as("未列入排除名单的非业务路由").isEmpty();
        assertThat(testFixtures).containsExactlyInAnyOrder(
                "GET /test/ok",
                "GET /test/boom",
                "GET /test/large-collection",
                "GET /test/large-response",
                "POST /test/echo");
        assertThat(infrastructure).contains(
                "GET /health",
                "PUT /internal-file-transfer/{token}",
                "GET /internal-file-transfer/{token}");
        assertThat(business).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void classify(Set<String> business, Set<String> infrastructure, Set<String> testFixtures,
                                 Set<String> unexpected, HandlerMethod handler, String method, String pattern) {
        String route = method + " " + pattern;
        if (isTestFixture(handler)) {
            if (pattern.startsWith(API_PREFIX + "/")) {
                testFixtures.add(method + " " + pattern.substring(API_PREFIX.length()));
            } else {
                testFixtures.add(route);
            }
            return;
        }
        if (pattern.startsWith(API_PREFIX + "/")) {
            business.add(method + " " + pattern.substring(API_PREFIX.length()));
            return;
        }
        if ("/health".equals(pattern)
                || pattern.startsWith("/internal-file-transfer/")
                || "/error".equals(pattern)) {
            infrastructure.add(route);
            return;
        }
        unexpected.add(route);
    }

    private static boolean isTestFixture(HandlerMethod handler) {
        var source = handler.getBeanType().getProtectionDomain().getCodeSource();
        return source != null && source.getLocation().getPath().contains("/test-classes/");
    }

    private static Map<String, String> readChecklist() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("docs/backend/接口覆盖清单.json").toFile());
        assertThat(root.path("interfaceCount").asInt()).isEqualTo(281);
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> seenRoutes = new TreeSet<>();
        for (JsonNode item : root.path("items")) {
            String id = item.path("id").asText();
            String route = item.path("route").asText();
            assertThat(routes.put(id, route)).as("重复接口编号 " + id).isNull();
            assertThat(seenRoutes.add(route)).as("重复路由 " + route).isTrue();
        }
        return routes;
    }

    private static Map<String, String> readIndexSummary() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("docs/backend/07-全部接口实施索引.md"))) {
            if (line.startsWith("## ")) {
                break;
            }
            Matcher matcher = INDEX_ROW.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String id = matcher.group(1);
            String route = matcher.group(2) + " " + matcher.group(3);
            assertThat(routes.put(id, route)).as("索引重复接口编号 " + id).isNull();
        }
        return routes;
    }
}
