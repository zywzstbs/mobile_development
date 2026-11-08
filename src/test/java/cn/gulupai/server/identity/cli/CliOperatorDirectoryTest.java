package cn.gulupai.server.identity.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliOperatorDirectoryTest {

    private static final String SID_ONE = "sid:s-1-5-21-3458183512-1442337674-3814424059-500";
    private static final String SID_TWO = "sid:s-1-5-21-3458183512-1442337674-3814424059-501";

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
        assertThatThrownBy(() -> directory.require(null))
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
    void windowsSidMatchingIsCaseInsensitiveAndNamespaceExact() {
        CliOperatorDirectory directory = new CliOperatorDirectory();
        directory.setOperators(List.of(operator(SID_TWO, "user_demo_teacher_02", "乙")));

        assertThat(directory.require(SID_TWO).subjectId()).isEqualTo("user_demo_teacher_02");
        assertThat(directory.require(SID_TWO.toUpperCase(Locale.ROOT)).subjectId())
                .isEqualTo("user_demo_teacher_02");
        assertThat(directory.require(" sid:" + SID_TWO.substring("sid:".length()) + " ").subjectId())
                .isEqualTo("user_demo_teacher_02");

        assertThatThrownBy(() -> directory.require(SID_ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
        assertThatThrownBy(() -> directory.require("Administrator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
        assertThatThrownBy(() -> directory.require("X99\\Administrator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
    }

    @Test
    void displayFormNeverWorksAsTheProtectedIdentity() {
        CliOperatorDirectory real = directory(CliOperatorDirectory.currentOsUser(), "user_demo_teacher_01", "甲");
        assertThat(real.requireCurrent().subjectId()).isEqualTo("user_demo_teacher_01");

        CliOperatorDirectory displayOnly = directory("P62FORGED\\p62_forged_operator", "user_demo_teacher_02", "乙");
        assertThatThrownBy(displayOnly::requireCurrent)
                .as("域\\账户名只是显示形式，不能当作受保护身份")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未获注册审核授权");
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
    void currentIdentityComesFromNativeTokenNotFromUserNameProperty() {
        String previous = System.setProperty("user.name", "p62_forged_operator");
        try {
            String identity = CliOperatorDirectory.currentOsUser();

            assertThat(identity).isNotBlank().isNotEqualTo("p62_forged_operator");
            assertThat(identity).isEqualTo(TrustedOsIdentity.normalize(identity));
        } finally {
            System.setProperty("user.name", previous);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsIdentityIsTheProcessTokenSid() throws Exception {
        String identity = CliOperatorDirectory.currentOsUser();
        com.sun.security.auth.module.NTSystem token = new com.sun.security.auth.module.NTSystem();

        assertThat(identity).startsWith(TrustedOsIdentity.WINDOWS_PREFIX);
        assertThat(identity).matches("sid:s-1-[0-9]+(-[0-9]+)+");
        assertThat(identity).isEqualTo(
                TrustedOsIdentity.WINDOWS_PREFIX + token.getUserSID().toLowerCase(Locale.ROOT));
        assertThat(identity).isNotEqualTo(token.getName());
        assertThat(identity).isNotEqualTo(token.getDomain() + "\\" + token.getName());
        assertThat(identity).isNotEqualTo(System.getenv("USERNAME"));
        assertThat(identity).isNotEqualTo(System.getenv("USERDOMAIN") + "\\" + System.getenv("USERNAME"));
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
