package cn.gulupai.server.identity.cli;

import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 单独启动的探针：用真实 JVM 参数复现审核身份，不连接数据库。
 */
public final class CliOperatorStartupProbe {

    private CliOperatorStartupProbe() {
    }

    public static void main(String[] args) {
        try {
            StandardEnvironment environment = new StandardEnvironment();
            if (args.length > 0) {
                environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
            }
            CliOperatorDirectory.Reviewer reviewer = new CliOperatorDirectory(environment).requireCurrent();
            System.out.println("osUser=" + reviewer.osUser());
            System.out.println("subjectId=" + reviewer.subjectId());
        } catch (RuntimeException ex) {
            System.out.println("rejected=" + ex.getMessage());
            System.exit(2);
        }
    }
}
