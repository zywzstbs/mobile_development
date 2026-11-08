package cn.gulupai.server.identity.cli;

import com.sun.security.auth.module.NTSystem;
import com.sun.security.auth.module.UnixSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 受保护的操作系统身份解析。
 *
 * <p>身份只取自操作系统内核/进程令牌，不读取 {@code user.name}、{@code USER}、
 * {@code USERNAME}、{@code USERDOMAIN} 等可设置的值，也不读任何配置文件：
 *
 * <ul>
 *   <li><b>Linux</b>：{@code /proc/self/status} 的有效 uid 必须与 {@code getpwuid}
 *       解析到的一致，身份为用户名字符串。</li>
 *   <li><b>Windows</b>：进程令牌里的用户 SID（{@code NTSystem#getUserSID}），规范形式
 *       {@code sid:s-1-5-21-...}。域名与账户名只用于人工显示，不参与匹配；SID 不随
 *       账户改名变化，也不会把同名本地账号与域账号混为一谈。</li>
 * </ul>
 *
 * <p>两个分支的身份字符串命名空间互斥（Linux 用户名不能包含冒号），所以即使
 * {@code os.name} 之类的平台提示被篡改，也只能得到“本机当前账号在另一个平台表示法下
 * 的身份”，不会变成别人的有效身份。解析时两种原生来源都会尝试，恰好成功一种才接受；
 * 都不可用（例如原生库缺失）或同时可用（平台异常）一律受控失败，不降级到可伪造来源。
 */
final class TrustedOsIdentity {

    /** Windows 身份的命名空间前缀，小写规范形式。 */
    static final String WINDOWS_PREFIX = "sid:";

    static final String UNKNOWN_MESSAGE = "无法确认当前操作系统用户";

    private static final Pattern WINDOWS_SID = Pattern.compile("s-1-[0-9]+(-[0-9]+)+");

    private TrustedOsIdentity() {
    }

    /**
     * 当前进程受保护身份。返回值可直接与 {@code cli-operators.yml} 的 {@code os-user}
     * 比较（Windows 上形如 {@code sid:s-1-5-21-...}，Linux 上为用户名）。
     */
    static String current() {
        List<String> identities = new ArrayList<>(2);
        String windows = windowsIdentity();
        if (windows != null) {
            identities.add(windows);
        }
        String linux = linuxIdentity();
        if (linux != null) {
            identities.add(linux);
        }
        if (identities.size() != 1) {
            throw new IllegalArgumentException(UNKNOWN_MESSAGE);
        }
        return identities.getFirst();
    }

    /**
     * Windows 上当前可信 SID 对应的“域\账户名”，只用于把已校验的 SID 解析为文件系统主体。
     *
     * <p>账户名是 {@code lookupPrincipalByName} 的输入而不是身份来源：这里先确认账户名与
     * {@link #current()} 的 SID 出自同一进程令牌，解析出的主体再由调用方按 ACL 条目核对。
     * 不读 {@code USERNAME}/{@code USERDOMAIN} 环境变量。
     */
    static String windowsAccountName() {
        try {
            NTSystem system = new NTSystem();
            String sid = normalizeSid(system.getUserSID());
            if (sid == null || !(WINDOWS_PREFIX + sid).equals(current())) {
                throw new IllegalArgumentException(UNKNOWN_MESSAGE);
            }
            String name = system.getName() == null ? "" : system.getName().strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException(UNKNOWN_MESSAGE);
            }
            String domain = system.getDomain() == null ? "" : system.getDomain().strip();
            return domain.isEmpty() ? name : domain + "\\" + name;
        } catch (LinkageError | RuntimeException ex) {
            throw new IllegalArgumentException(UNKNOWN_MESSAGE);
        }
    }

    /** 规范形式：去掉首尾空白；{@code sid:} 命名空间不区分大小写。 */
    static String normalize(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.regionMatches(true, 0, WINDOWS_PREFIX, 0, WINDOWS_PREFIX.length())) {
            return stripped.toLowerCase(Locale.ROOT);
        }
        return stripped;
    }

    private static String windowsIdentity() {
        try {
            String sid = normalizeSid(new NTSystem().getUserSID());
            return sid == null ? null : WINDOWS_PREFIX + sid;
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeSid(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.strip().toLowerCase(Locale.ROOT);
        if (candidate.isEmpty() || !WINDOWS_SID.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }

    private static String linuxIdentity() {
        try {
            long effectiveUid = effectiveUid();
            UnixSystem unix = new UnixSystem();
            String username = unix.getUsername();
            if (unix.getUid() != effectiveUid || username == null || username.isBlank()) {
                return null;
            }
            return username.strip();
        } catch (LinkageError | RuntimeException ex) {
            return null;
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
            throw new IllegalArgumentException(UNKNOWN_MESSAGE);
        }
        throw new IllegalArgumentException(UNKNOWN_MESSAGE);
    }
}
