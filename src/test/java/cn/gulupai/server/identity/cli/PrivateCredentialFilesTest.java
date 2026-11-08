package cn.gulupai.server.identity.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateCredentialFilesTest {

    private static final String SECRET = "student_full_01 S3cretPassw0rdValue";

    @Test
    void writesPrivateFileUnderWideParentAndVerifiesContent(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("nested/dir/demo-credentials.txt");
        Path parent = target.getParent();
        Files.createDirectories(parent);
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            files.writePrivateCredentials(target, SECRET);
        }

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(SECRET);
        PrivateCredentialFiles.verifyPrivateFile(target);
        assertThat(stagingDirectories(parent)).isEmpty();
        // 父目录本身可以保持宽权限：私有性来自文件自己的 ACL/权限位，而不是父目录。
        assertThat(Files.isDirectory(parent)).isTrue();
    }

    @Test
    void rewritesExistingPrivateFileWithoutLosingPrivacy(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            files.writePrivateCredentials(target, SECRET);
            files.writePrivateCredentials(target, SECRET + "\nstudent_full_02 AnotherPassw0rd");
        }

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).contains("student_full_02 AnotherPassw0rd");
        PrivateCredentialFiles.verifyPrivateFile(target);
        assertThat(stagingDirectories(temp)).isEmpty();
    }

    @Test
    void readTightensExistingWideFileBeforeReuse(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("legacy-credentials.txt");
        Files.writeString(target, SECRET, StandardCharsets.UTF_8);
        widen(target, temp);

        String content;
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            content = files.readExistingPrivateCredentials(target).orElseThrow();
        }

        assertThat(content).isEqualTo(SECRET);
        PrivateCredentialFiles.verifyPrivateFile(target);
        assertThat(readableByForeignPrincipals(target)).isFalse();
    }

    @Test
    void readReturnsEmptyWhenFileIsAbsent(@TempDir Path temp) {
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            assertThat(files.readExistingPrivateCredentials(temp.resolve("missing.txt"))).isEmpty();
        }
    }

    @Test
    void failedPermissionSetupLeavesNoPlaintext(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        PrivateCredentialFiles.PermissionOps failing = new DelegatingPermissions() {
            @Override
            public void secureFile(Path file) {
                throw new PrivateCredentialFiles.PrivateFileException("模拟 ACL 建立失败");
            }
        };

        try (PrivateCredentialFiles files = PrivateCredentialFiles.withPermissions(failing)) {
            assertThatThrownBy(() -> files.writePrivateCredentials(target, SECRET))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("模拟 ACL 建立失败");
        }

        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(stagingDirectories(temp)).isEmpty();
        assertThat(filesContaining(temp, SECRET)).isEmpty();
    }

    @Test
    void failedPublishKeepsTargetUntouchedAndLeavesNoPlaintext(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        Files.createDirectory(target);

        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            assertThatThrownBy(() -> files.writePrivateCredentials(target, SECRET))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("目录");
        }

        assertThat(Files.isDirectory(target)).isTrue();
        assertThat(stagingDirectories(temp)).isEmpty();
        assertThat(filesContaining(temp, SECRET)).isEmpty();
    }

    @Test
    void rejectsSymbolicLinkTarget(@TempDir Path temp) throws IOException {
        Path real = temp.resolve("real-credentials.txt");
        Files.writeString(real, "placeholder", StandardCharsets.UTF_8);
        Path link = temp.resolve("linked-credentials.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException ex) {
            Assumptions.assumeTrue(false, "当前环境不允许创建符号链接，跳过链接拒绝用例");
        }

        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            assertThatThrownBy(() -> files.writePrivateCredentials(link, SECRET))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("链接");
            assertThatThrownBy(() -> files.readExistingPrivateCredentials(link))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("链接");
        }
        assertThat(Files.readString(real, StandardCharsets.UTF_8)).isEqualTo("placeholder");
    }

    @Test
    void temporaryJsonIsPrivateAndRemovedOnClose(@TempDir Path temp) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = temporaryDirectories(base);

        PrivateCredentialFiles.PrivateJson json;
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            json = files.createPrivateJson(Map.of("loginId", "student_full_01", "password", "x"),
                    new ObjectMapper());
            assertThat(Files.readString(json.path(), StandardCharsets.UTF_8)).contains("student_full_01");
            PrivateCredentialFiles.verifyPrivateFile(json.path());
            assertThat(json.path().getParent().getFileName().toString()).startsWith("gulupai-private-");
            json.close();
            assertThat(Files.exists(json.path().getParent(), LinkOption.NOFOLLOW_LINKS)).isFalse();
        }

        assertThat(temporaryDirectories(base)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(stagingDirectories(temp)).isEmpty();
    }

    @Test
    void closeDeletesOwnedTemporaryDirectories(@TempDir Path temp) throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = temporaryDirectories(base);

        PrivateCredentialFiles files = PrivateCredentialFiles.create();
        files.createPrivateJson(Map.of("loginId", "student_full_01"), new ObjectMapper());
        files.close();

        assertThat(temporaryDirectories(base)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void rejectsFileSystemWithoutAnyPermissionModel() {
        assertThatThrownBy(() -> PrivateCredentialFiles.selectPermissions(Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝写入");
        assertThatThrownBy(() -> PrivateCredentialFiles.selectPermissions(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefersPosixWhenBothModelsAreReported() {
        assertThat(PrivateCredentialFiles.selectPermissions(Set.of("posix", "acl")).getClass().getSimpleName())
                .isEqualTo("PosixPermissions");
        assertThat(PrivateCredentialFiles.selectPermissions(Set.of("acl")).getClass().getSimpleName())
                .isEqualTo("WindowsAclPermissions");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void posixModelFailsLoudlyWhenFilesystemHasNoPosixView(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        Files.writeString(target, "placeholder", StandardCharsets.UTF_8);
        PrivateCredentialFiles.PermissionOps posix = PrivateCredentialFiles.selectPermissions(Set.of("posix"));

        assertThatThrownBy(() -> posix.secureFile(target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("POSIX");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAclModelReplacesDaclWithTrustedPrincipal(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        Files.writeString(target, "placeholder", StandardCharsets.UTF_8);
        widen(target, temp);

        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            files.writePrivateCredentials(target, SECRET);
        }

        AclFileAttributeView view = Files.getFileAttributeView(target, AclFileAttributeView.class);
        assertThat(view).isNotNull();
        List<AclEntry> acl = view.getAcl();
        assertThat(acl).hasSize(1);
        AclEntry entry = acl.getFirst();
        assertThat(entry.type()).isEqualTo(AclEntryType.ALLOW);
        assertThat(entry.flags()).isEmpty();
        assertThat(entry.principal()).isEqualTo(trustedPrincipal());
        assertThat(entry.permissions())
                .contains(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAclModelDoesNotDependOnEnglishGroupNames(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("demo-credentials.txt");
        try (PrivateCredentialFiles files = PrivateCredentialFiles.create()) {
            files.writePrivateCredentials(target, SECRET);
        }

        // 主体来自进程令牌的 SID 解析，断言只比较主体对象本身，不比较任何本地化组名文本。
        UserPrincipal principal = Files.getFileAttributeView(target, AclFileAttributeView.class)
                .getAcl().getFirst().principal();
        assertThat(principal.getName()).isEqualTo(trustedPrincipal().getName());
        assertThat(principal).isEqualTo(trustedPrincipal());
    }

    private static UserPrincipal trustedPrincipal() throws IOException {
        return FileSystems.getDefault().getUserPrincipalLookupService()
                .lookupPrincipalByName(TrustedOsIdentity.windowsAccountName());
    }

    /** 把文件权限放宽到“父目录继承的样子”，模拟上一轮遗留的宽权限凭据文件。 */
    private static void widen(Path file, Path parent) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
            return;
        }
        AclFileAttributeView fileView = Files.getFileAttributeView(file, AclFileAttributeView.class);
        AclFileAttributeView parentView = Files.getFileAttributeView(parent, AclFileAttributeView.class);
        fileView.setAcl(parentView.getAcl());
        Assumptions.assumeTrue(readableByForeignPrincipals(file), "父目录 ACL 未授予其他主体读取权限");
    }

    /** 是否存在“非当前可信主体”的允许规则可读该文件。 */
    private static boolean readableByForeignPrincipals(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            return Files.getPosixFilePermissions(file).stream()
                    .anyMatch(permission -> permission == PosixFilePermission.GROUP_READ
                            || permission == PosixFilePermission.OTHERS_READ);
        }
        UserPrincipal trusted = trustedPrincipal();
        return Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl().stream()
                .filter(entry -> entry.type() == AclEntryType.ALLOW)
                .filter(entry -> !entry.principal().equals(trusted))
                .anyMatch(entry -> entry.permissions().contains(AclEntryPermission.READ_DATA));
    }

    private static List<Path> stagingDirectories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(path -> path.getFileName().toString().startsWith(".gulupai-private-"))
                    .toList();
        }
    }

    private static Set<Path> temporaryDirectories(Path base) throws IOException {
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(path -> path.getFileName().toString().startsWith("gulupai-private-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static List<Path> filesContaining(Path root, String secret) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> contains(path, secret))
                    .toList();
        }
    }

    private static boolean contains(Path file, String secret) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(secret);
        } catch (IOException ex) {
            return false;
        }
    }

    /** 只委托真实实现的测试替身，便于只替换一个阶段。 */
    private static class DelegatingPermissions implements PrivateCredentialFiles.PermissionOps {
        private final PrivateCredentialFiles.PermissionOps delegate = PrivateCredentialFiles.nativePermissions();

        @Override
        public void secureDirectory(Path directory) {
            delegate.secureDirectory(directory);
        }

        @Override
        public void secureFile(Path file) {
            delegate.secureFile(file);
        }

        @Override
        public void verifyFile(Path file) {
            delegate.verifyFile(file);
        }
    }
}
