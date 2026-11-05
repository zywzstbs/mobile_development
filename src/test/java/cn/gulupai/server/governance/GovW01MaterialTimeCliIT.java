package cn.gulupai.server.governance;

import cn.gulupai.server.identity.cli.AdminCliRunner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W01-13：直接调用真实 AdminCliRunner 的参数解析/输出/退出码路径。
 *
 * <p>本用例不启动独立 JVM 或 ApplicationRunner 进程（run() 会 System.exit），
 * 但使用的是真实 Spring ApplicationArguments 解析、真实恢复服务和真实 PG。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GovMaterialScenarioSupport.TestClockConfig.class)
class GovW01MaterialTimeCliIT extends W01MaterialTimeSupport {

    @Test
    void w01_13CliParsesArgumentsAndOnlyPrintsSuccessAfterVerifiedRecovery() throws Exception {
        clock.setInstant(BASE.plusSeconds(10 * 3600));
        Scenario s = materialScenario("w01-13");
        respondOk(s.member(), s.materialId(), "CLI正文", 1, "w01-13-v1");
        makeUnknown(s.materialId(), 1);
        String receipt = receiptId(MEMBER, responsePath(s.materialId()), "w01-13-v1");

        Scenario other = materialScenario("w01-13-other");
        respondOk(other.member(), other.materialId(), "其他正文", 1, "w01-13-other-v1");
        makeUnknown(other.materialId(), 1);
        String otherReceipt = receiptId(MEMBER, responsePath(other.materialId()), "w01-13-other-v1");
        RowState before = rowState(s.materialId(), 1);

        // 缺参数：退出码 1，不能打印恢复成功。
        Output missing = runCli(cli("material-time-recovery", false));
        assertThat(missing.code()).isEqualTo(1);
        assertThat(missing.out()).doesNotContain("材料提交时间恢复");
        assertThat(missing.err()).contains("需要");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 版本不是整数。
        Output badVersion = runCli(cli("material-time-recovery", false),
                "--app.cli.material-id=" + s.materialId(),
                "--app.cli.response-version=abc",
                "--app.cli.receipt-id=" + receipt);
        assertThat(badVersion.code()).isEqualTo(1);
        assertThat(badVersion.out()).doesNotContain("材料提交时间恢复");
        assertThat(badVersion.err()).contains("正整数");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 版本为 0：参数能解析，但服务端校验拒绝，仍为 1。
        Output zeroVersion = runCli(cli("material-time-recovery", false),
                "--app.cli.material-id=" + s.materialId(),
                "--app.cli.response-version=0",
                "--app.cli.receipt-id=" + receipt);
        assertThat(zeroVersion.code()).isEqualTo(1);
        assertThat(zeroVersion.err()).contains("需要");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 错误回执：服务拒绝，CLI 不能打印 RECOVERED/DRY_RUN。
        Output rejected = runCli(cli("material-time-recovery", false),
                "--app.cli.material-id=" + s.materialId(),
                "--app.cli.response-version=1",
                "--app.cli.receipt-id=" + otherReceipt);
        assertThat(rejected.code()).isEqualTo(1);
        assertThat(rejected.out()).doesNotContain("RECOVERED", "DRY_RUN", "材料提交时间恢复");
        assertThat(rejected.err()).contains("CLI失败", "路径");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // dry-run：输出计划，但数据库不落恢复字段。
        Output dryRun = runCli(cli("material-time-recovery", true),
                "--app.cli.material-id=" + s.materialId(),
                "--app.cli.response-version=1",
                "--app.cli.receipt-id=" + receipt);
        assertThat(dryRun.code()).isZero();
        assertThat(dryRun.out()).contains("DRY_RUN", "2026-10-31T10:00:00Z");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(before);

        // 正式成功：退出码 0，输出 RECOVERED，数据库按证据时间与 actor 写入。
        Output success = runCli(cli("material-time-recovery", false),
                "--app.cli.material-id=" + s.materialId(),
                "--app.cli.response-version=1",
                "--app.cli.receipt-id=" + receipt);
        assertThat(success.code()).isZero();
        assertThat(success.out()).contains("RECOVERED", EVIDENCE_PREFIX + receipt);
        assertThat(success.err()).doesNotContain("CLI失败");
        RowState recovered = rowState(s.materialId(), 1);
        assertThat(recovered.confidence()).isEqualTo("EXACT");
        assertThat(recovered.source()).isEqualTo("RECOVERED_EVIDENCE");
        assertThat(recovered.createdAt()).isEqualTo(Instant.parse("2026-10-31T10:00:00Z"));
        assertThat(recovered.recoveredBy()).isEqualTo("cli-test");

        // 只读核查命令：复用同一 CLI 入口输出分类，不写业务数据。
        Output audit = runCli(cli("material-time-recovery-audit", false));
        assertThat(audit.code()).isZero();
        assertThat(audit.out()).contains("材料时间恢复核查", "候选=1", "VALID=1");
        assertThat(rowState(s.materialId(), 1)).isEqualTo(recovered);
    }

    private AdminCliRunner cli(String command, boolean dryRun) {
        return new AdminCliRunner(null, null, null, null, null, null, null, null, null,
                recoveryService, auditService, null, null, command, "", dryRun, "cli-test");
    }

    private Output runCli(AdminCliRunner runner, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = runner.execute(new DefaultApplicationArguments(args));
            return new Output(code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Output(int code, String out, String err) {
    }
}
