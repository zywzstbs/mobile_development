package cn.gulupai.server.identity.cli;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliOperatorDirectoryTest {

    @Test
    void mapsOsUserToSubjectAndKeepsDisplayLabelSeparate() {
        CliOperatorDirectory directory = directory("ops", "user_demo_teacher_01", "测试环境审核员");

        CliOperatorDirectory.Reviewer reviewer = directory.require("ops");

        assertThat(reviewer.osUser()).isEqualTo("ops");
        assertThat(reviewer.subjectId()).isEqualTo("user_demo_teacher_01");
        assertThat(reviewer.displayLabel()).isEqualTo("测试环境审核员");
        assertThat(reviewer.displayLabel()).isNotEqualTo(reviewer.subjectId());
    }

    @Test
    void rejectsUnknownAndBlankOperators() {
        CliOperatorDirectory directory = directory("ops", "user_demo_teacher_01", "测试环境审核员");

        assertThatThrownBy(() -> directory.require("intruder"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
        assertThatThrownBy(() -> directory.require("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法确认");
        assertThatThrownBy(() -> new CliOperatorDirectory().require("ops"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
    }

    @Test
    void rejectsDuplicateMappingAndLabelUsedAsIdentity() {
        CliOperatorDirectory duplicated = new CliOperatorDirectory();
        duplicated.setOperators(List.of(
                operator("ops", "user_demo_teacher_01", "甲"),
                operator("ops", "user_demo_teacher_02", "乙")));
        assertThatThrownBy(() -> duplicated.require("ops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("映射无效");

        CliOperatorDirectory sameLabel = directory("ops", "user_demo_teacher_01", "user_demo_teacher_01");
        assertThatThrownBy(() -> sameLabel.require("ops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("展示名必须与审计身份分开");
    }

    @Test
    void forgedUserNameDoesNotChangeProtectedReviewer() {
        String previous = System.setProperty("user.name", "p62_forged_operator");
        try {
            CliOperatorDirectory.Reviewer reviewer = new CliOperatorDirectory(new StandardEnvironment()).requireCurrent();

            assertThat(reviewer.osUser()).isEqualTo(CliOperatorDirectory.currentOsUser()).isNotEqualTo("p62_forged_operator");
            assertThat(reviewer.subjectId()).isEqualTo("user_demo_teacher_01");
        } finally {
            System.setProperty("user.name", previous);
        }
    }

    @Test
    void commandLineSystemPropertyAndEnvironmentCannotOverrideOperators() {
        StandardEnvironment commandLine = new StandardEnvironment();
        commandLine.getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--app.cli.operators[0].os-user=p62_forged_operator",
                "--app.cli.operators[0].subject-id=user_forged",
                "--app.cli.operators[0].display-label=伪造审核人"));
        assertRejected(commandLine);

        String previous = System.setProperty("app.cli.operators[0].subject-id", "user_forged");
        try {
            assertRejected(new StandardEnvironment());
        } finally {
            if (previous == null) {
                System.clearProperty("app.cli.operators[0].subject-id");
            } else {
                System.setProperty("app.cli.operators[0].subject-id", previous);
            }
        }

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "systemEnvironment",
                Map.of("APP_CLI_OPERATORS_0_SUBJECT_ID", "user_forged")));
        assertRejected(environment);

        StandardEnvironment json = new StandardEnvironment();
        json.getPropertySources().addFirst(new MapPropertySource(
                "springJson",
                Map.of("spring.application.json",
                        "{\"app\":{\"cli\":{\"operators\":[{\"subject-id\":\"user_forged\"}]}}}")));
        assertRejected(json);
    }

    private static void assertRejected(StandardEnvironment environment) {
        assertThatThrownBy(() -> new CliOperatorDirectory(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(CliOperatorDirectory.OVERRIDE_MESSAGE);
    }

    private static CliOperatorDirectory directory(String osUser, String subjectId, String label) {
        CliOperatorDirectory directory = new CliOperatorDirectory();
        directory.setOperators(List.of(operator(osUser, subjectId, label)));
        return directory;
    }

    private static CliOperatorDirectory.Operator operator(String osUser, String subjectId, String label) {
        CliOperatorDirectory.Operator operator = new CliOperatorDirectory.Operator();
        operator.setOsUser(osUser);
        operator.setSubjectId(subjectId);
        operator.setDisplayLabel(label);
        return operator;
    }
}
