package cn.gulupai.server.it;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从迁移目录读取版本，避免每加一个脚本就改多处硬编码。 */
public final class MigrationVersions {

    private static final Pattern NAME = Pattern.compile("V(\\d+)__");
    private static final List<Integer> VERSIONS = load();

    private MigrationVersions() {
    }

    public static int latestNumber() {
        return VERSIONS.getLast();
    }

    public static String latest() {
        return String.format("%03d", latestNumber());
    }

    public static List<String> all() {
        return VERSIONS.stream().map(version -> String.format("%03d", version)).toList();
    }

    public static int countAfter(int version) {
        int count = 0;
        for (int current : VERSIONS) {
            if (current > version) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
            List<Integer> versions = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = NAME.matcher(filename);
                if (matcher.find()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
            versions.sort(Integer::compareTo);
            if (versions.isEmpty()) {
                throw new IllegalStateException("没有找到 Flyway 迁移");
            }
            return List.copyOf(versions);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取 Flyway 迁移版本", ex);
        }
    }
}
