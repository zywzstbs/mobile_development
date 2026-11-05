package cn.gulupai.server.identity.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CliOperatorStartupTest {

    @Test
    void startupKeepsReviewerWhenUserNameAndUserEnvAreForged() throws Exception {
        Launch launch = launch(List.of("-Duser.name=p62_forged_operator"), "p62_forged_operator", false, List.of());

        assertThat(launch.exitCode()).isZero();
        assertThat(launch.output()).contains("osUser=" + CliOperatorDirectory.currentOsUser());
        assertThat(launch.output()).contains("subjectId=user_demo_teacher_01");
        assertThat(launch.output()).doesNotContain("osUser=p62_forged_operator");
        assertThat(launch.output()).doesNotContain("subjectId=user_forged");
    }

    @Test
    void startupRejectsOperatorCommandLineOverride() throws Exception {
        Launch launch = launch(List.of("-Duser.name=p62_forged_operator"), "p62_forged_operator", false, List.of(
                "--app.cli.operators[0].os-user=p62_forged_operator",
                "--app.cli.operators[0].subject-id=user_forged",
                "--app.cli.operators[0].display-label=伪造审核人"));

        assertRejected(launch);
    }

    @Test
    void startupRejectsOperatorSystemPropertyOverride() throws Exception {
        Launch launch = launch(
                List.of("-Dapp.cli.operators[0].subject-id=user_forged"),
                CliOperatorDirectory.currentOsUser(),
                false,
                List.of());

        assertRejected(launch);
    }

    @Test
    void startupRejectsOperatorEnvironmentOverride() throws Exception {
        Launch launch = launch(List.of(), CliOperatorDirectory.currentOsUser(), true, List.of());

        assertRejected(launch);
    }

    private static void assertRejected(Launch launch) {
        assertThat(launch.exitCode()).isEqualTo(2);
        assertThat(launch.output()).contains("rejected=" + CliOperatorDirectory.OVERRIDE_MESSAGE);
        assertThat(launch.output()).doesNotContain("subjectId=");
        assertThat(launch.output()).doesNotContain("user_forged");
    }

    private static Launch launch(List<String> jvmArgs, String userEnv, boolean operatorEnvironment, List<String> programArgs)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CliOperatorStartupProbe.class.getName());
        command.addAll(programArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("USER", userEnv);
        if (operatorEnvironment) {
            builder.environment().put("APP_CLI_OPERATORS_0_SUBJECT_ID", "user_forged");
        } else {
            builder.environment().remove("APP_CLI_OPERATORS_0_SUBJECT_ID");
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertThat(finished).isTrue();
        return new Launch(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private record Launch(int exitCode, String output) {
    }
}
