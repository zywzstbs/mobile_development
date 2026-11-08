package cn.gulupai.server.identity.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 受保护的凭据文件读写。
 *
 * <p>明文口令只写入“先建立、后回读校验、再写入秘密”的私有位置：
 *
 * <ul>
 *   <li><b>Linux</b>：私有目录 {@code 0700}、私有文件 {@code 0600}，写入后回读权限位。</li>
 *   <li><b>Windows</b>：用 {@link AclFileAttributeView} 把 DACL 替换成唯一一条 ALLOW 规则，
 *       主体只来自进程令牌的可信身份（{@link TrustedOsIdentity}），不保留
 *       {@code BUILTIN\Users}、{@code Authenticated Users}、{@code Everyone} 或任何继承
 *       授权；设置成功后必须回读，条目数量、主体、继承标志、权限集合全部符合策略才算成功。</li>
 *   <li>既无 POSIX 也无 ACL 能力的文件系统明确拒绝，不把“不支持”当成成功。</li>
 * </ul>
 *
 * <p>写入顺序固定为：在目标同目录建立私有暂存目录 → 校验目录权限 → 建立空文件 →
 * 校验文件权限 → 写入内容 → 回读内容 → 同文件系统发布 → 校验目标权限 → 清理暂存。
 * 任何一步失败都不会把明文留在宽权限目标里，也不会删除用户原本存在的凭据。
 *
 * <p>异常信息只包含阶段和错误类型，不包含口令正文。
 */
final class PrivateCredentialFiles implements AutoCloseable {

    private static final String STAGING_PREFIX = ".gulupai-private-";
    private static final String TEMP_DIRECTORY_PREFIX = "gulupai-private-";
    private static final String STAGED_FILE_NAME = "credentials";
    private static final String TEMP_JSON_NAME = "payload.json";

    private final PermissionOps permissions;
    private final List<Path> ownedTemporaryDirectories = new ArrayList<>();
    private boolean closed;

    private PrivateCredentialFiles(PermissionOps permissions) {
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    /**
     * 权限操作接口。默认实现操作真实文件系统；测试用注入实现模拟“建立权限失败”。
     */
    interface PermissionOps {
        void secureDirectory(Path directory);

        void secureFile(Path file);

        void verifyFile(Path file);
    }

    /** 受保护凭据操作的受控失败。 */
    static final class PrivateFileException extends IllegalArgumentException {
        PrivateFileException(String message) {
            super(message);
        }

        PrivateFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static PrivateCredentialFiles create() {
        return new PrivateCredentialFiles(nativePermissions());
    }

    static PrivateCredentialFiles withPermissions(PermissionOps permissions) {
        return new PrivateCredentialFiles(permissions);
    }

    /**
     * 按文件系统能力选择权限模型。两个视图都没有时明确失败，不能静默跳过权限保护。
     */
    static PermissionOps nativePermissions() {
        FileSystem fileSystem = FileSystems.getDefault();
        return selectPermissions(fileSystem.supportedFileAttributeViews());
    }

    static PermissionOps selectPermissions(Set<String> supportedViews) {
        Set<String> views = supportedViews == null ? Set.of() : supportedViews;
        if (views.contains("posix")) {
            return new PosixPermissions();
        }
        if (views.contains("acl")) {
            return new WindowsAclPermissions();
        }
        throw new PrivateFileException("文件系统既不支持 POSIX 权限也不支持 ACL，拒绝写入凭据");
    }

    /** 读取既有凭据；文件不存在时返回空。既有文件权限过宽时先收紧并回读校验。 */
    Optional<String> readExistingPrivateCredentials(Path file) {
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path target = existingRegularFile(file);
        try {
            permissions.verifyFile(target);
        } catch (PrivateFileException tooWide) {
            permissions.secureFile(target);
            permissions.verifyFile(target);
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new PrivateFileException("无法读取私有凭据文件", ex);
        }
    }

    /**
     * 安全写入凭据：明文只会出现在已校验权限的暂存文件里，发布后再回读目标内容与权限。
     */
    void writePrivateCredentials(Path file, String content) {
        Objects.requireNonNull(content, "content");
        Path target = absoluteTarget(file);
        rejectUnsafeTarget(target);
        Path parent = target.getParent();
        if (parent == null) {
            throw new PrivateFileException("凭据文件路径无效");
        }
        Path staging = null;
        try {
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, STAGING_PREFIX);
            permissions.secureDirectory(staging);
            Path staged = Files.createFile(staging.resolve(STAGED_FILE_NAME));
            permissions.secureFile(staged);
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            requireContent(staged, content, "凭据文件写入校验失败");
            publish(staged, target);
            permissions.verifyFile(target);
            requireContent(target, content, "凭据文件发布校验失败");
        } catch (IOException ex) {
            throw new PrivateFileException("无法写入私有凭据文件", ex);
        } finally {
            deleteRecursively(staging);
        }
    }

    /** 在私有临时目录里序列化中间 JSON；序列化发生在权限建立并校验之后。 */
    PrivateJson createPrivateJson(Object value, ObjectMapper mapper) {
        Path directory = null;
        try {
            Path base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            directory = Files.createTempDirectory(base, TEMP_DIRECTORY_PREFIX);
            permissions.secureDirectory(directory);
            Path file = Files.createFile(directory.resolve(TEMP_JSON_NAME));
            permissions.secureFile(file);
            mapper.writeValue(file.toFile(), value);
            ownedTemporaryDirectories.add(directory);
            return new PrivateJson(file, directory);
        } catch (IOException ex) {
            deleteRecursively(directory);
            throw new PrivateFileException("无法准备私有临时文件", ex);
        } catch (RuntimeException ex) {
            deleteRecursively(directory);
            throw ex;
        }
    }

    /** 校验既有私有文件权限；供测试与命令行自检复用。 */
    static void verifyPrivateFile(Path file) {
        nativePermissions().verifyFile(existingRegularFile(file));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Path directory : List.copyOf(ownedTemporaryDirectories)) {
            deleteRecursively(directory);
        }
        ownedTemporaryDirectories.clear();
    }

    /** 本次创建的私有临时 JSON；关闭时只删除本次创建并已核对路径的目录。 */
    final class PrivateJson implements AutoCloseable {
        private final Path file;
        private final Path directory;
        private boolean resourceClosed;

        private PrivateJson(Path file, Path directory) {
            this.file = file;
            this.directory = directory;
        }

        Path path() {
            return file;
        }

        @Override
        public void close() {
            if (resourceClosed) {
                return;
            }
            resourceClosed = true;
            ownedTemporaryDirectories.remove(directory);
            deleteRecursively(directory);
        }
    }

    private static Path absoluteTarget(Path file) {
        if (file == null || file.toString().isBlank()) {
            throw new PrivateFileException("未指定凭据文件");
        }
        return file.toAbsolutePath().normalize();
    }

    private static void rejectUnsafeTarget(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes.isDirectory()) {
            throw new PrivateFileException("凭据文件路径是目录，拒绝写入");
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new PrivateFileException("凭据文件路径是链接或重解析目标，拒绝写入");
        }
        if (!attributes.isRegularFile()) {
            throw new PrivateFileException("凭据文件路径不是普通文件，拒绝写入");
        }
    }

    private static Path existingRegularFile(Path file) {
        Path target = absoluteTarget(file);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PrivateFileException("凭据文件不存在");
        }
        BasicFileAttributes attributes = readAttributes(target);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new PrivateFileException("凭据文件是链接或重解析目标，拒绝读取");
        }
        if (!attributes.isRegularFile()) {
            throw new PrivateFileException("凭据文件不是普通文件");
        }
        return target;
    }

    private static BasicFileAttributes readAttributes(Path target) {
        try {
            return Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new PrivateFileException("无法确认凭据文件类型", ex);
        }
    }

    private static void requireContent(Path file, String expected, String message) throws IOException {
        if (!Files.readString(file, StandardCharsets.UTF_8).equals(expected)) {
            throw new PrivateFileException(message);
        }
    }

    /**
     * 同文件系统发布：优先原子替换；不支持原子移动时仍用重命名替换，避免“复制到宽权限目标再补 ACL”。
     */
    private static void publish(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> children = Files.list(root)) {
                    for (Path child : children.toList()) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(root);
        } catch (IOException ignored) {
            // 清理失败不改变已经完成的业务结果；残留目录本身受私有权限保护。
        }
    }

    /** POSIX：目录 0700、文件 0600，写入后回读校验。 */
    private static final class PosixPermissions implements PermissionOps {
        private static final Set<PosixFilePermission> FILE_PERMISSIONS =
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

        @Override
        public void secureDirectory(Path directory) {
            apply(directory, DIRECTORY_PERMISSIONS);
            verify(directory, DIRECTORY_PERMISSIONS);
        }

        @Override
        public void secureFile(Path file) {
            apply(file, FILE_PERMISSIONS);
            verify(file, FILE_PERMISSIONS);
        }

        @Override
        public void verifyFile(Path file) {
            verify(file, FILE_PERMISSIONS);
        }

        private static void apply(Path path, Set<PosixFilePermission> permissions) {
            try {
                Files.setPosixFilePermissions(path, permissions);
            } catch (IOException | UnsupportedOperationException ex) {
                throw new PrivateFileException("无法建立 POSIX 私有权限", ex);
            }
        }

        private static void verify(Path path, Set<PosixFilePermission> expected) {
            Set<PosixFilePermission> actual;
            try {
                actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | UnsupportedOperationException ex) {
                throw new PrivateFileException("无法校验 POSIX 权限", ex);
            }
            if (!actual.equals(expected)) {
                throw new PrivateFileException("文件权限不符合私有策略");
            }
        }
    }

    /**
     * Windows：DACL 只保留当前可信主体一条 ALLOW 规则，并回读校验。
     *
     * <p>不保留 SYSTEM 等额外主体，也不依赖英文组名：主体来自进程令牌的域账户名，
     * 由 {@link TrustedOsIdentity} 校验 SID 命名空间后再解析。
     */
    private static final class WindowsAclPermissions implements PermissionOps {
        private static final EnumSet<AclEntryPermission> FILE_PERMISSIONS = EnumSet.of(
                AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.EXECUTE, AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.DELETE, AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL,
                AclEntryPermission.SYNCHRONIZE);
        private static final EnumSet<AclEntryPermission> DIRECTORY_PERMISSIONS =
                EnumSet.allOf(AclEntryPermission.class);
        private static final EnumSet<AclEntryFlag> INHERIT_FLAGS =
                EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);

        private final UserPrincipal trusted;

        private WindowsAclPermissions() {
            this.trusted = trustedPrincipal();
        }

        @Override
        public void secureDirectory(Path directory) {
            apply(directory, DIRECTORY_PERMISSIONS, INHERIT_FLAGS);
            verify(directory, DIRECTORY_PERMISSIONS, INHERIT_FLAGS);
        }

        @Override
        public void secureFile(Path file) {
            apply(file, FILE_PERMISSIONS, EnumSet.noneOf(AclEntryFlag.class));
            verify(file, FILE_PERMISSIONS, EnumSet.noneOf(AclEntryFlag.class));
        }

        @Override
        public void verifyFile(Path file) {
            verify(file, FILE_PERMISSIONS, EnumSet.noneOf(AclEntryFlag.class));
        }

        private void apply(Path path, EnumSet<AclEntryPermission> permissions, EnumSet<AclEntryFlag> flags) {
            AclFileAttributeView view = aclView(path);
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(trusted)
                    .setPermissions(permissions)
                    .setFlags(flags)
                    .build();
            try {
                // 替换整个 DACL：继承条目随受保护 DACL 一起被移除，而不是在原 ACL 上追加规则。
                view.setAcl(List.of(entry));
            } catch (IOException | UnsupportedOperationException ex) {
                throw new PrivateFileException("无法建立 Windows 私有 ACL", ex);
            }
        }

        private void verify(Path path, EnumSet<AclEntryPermission> permissions, EnumSet<AclEntryFlag> flags) {
            List<AclEntry> acl;
            try {
                acl = List.copyOf(aclView(path).getAcl());
            } catch (IOException | UnsupportedOperationException ex) {
                throw new PrivateFileException("无法校验 Windows ACL", ex);
            }
            if (acl.size() != 1) {
                throw new PrivateFileException("文件 ACL 仍包含额外主体或继承规则");
            }
            AclEntry entry = acl.getFirst();
            if (entry.type() != AclEntryType.ALLOW) {
                throw new PrivateFileException("文件 ACL 不是允许规则");
            }
            if (!trusted.equals(entry.principal())) {
                throw new PrivateFileException("文件 ACL 主体不是当前可信身份");
            }
            if (!entry.flags().equals(flags)) {
                throw new PrivateFileException("文件 ACL 继承标志不符合私有策略");
            }
            if (!entry.permissions().containsAll(permissions)) {
                throw new PrivateFileException("文件 ACL 权限不足");
            }
        }

        private static AclFileAttributeView aclView(Path path) {
            AclFileAttributeView view = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new PrivateFileException("文件系统没有可用的 ACL 视图");
            }
            return view;
        }

        private static UserPrincipal trustedPrincipal() {
            String identity = TrustedOsIdentity.current();
            if (!identity.startsWith(TrustedOsIdentity.WINDOWS_PREFIX)) {
                throw new PrivateFileException("Windows ACL 需要 Windows 可信身份");
            }
            String account = TrustedOsIdentity.windowsAccountName();
            try {
                return FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(account);
            } catch (IOException | RuntimeException ex) {
                throw new PrivateFileException("无法把当前可信 SID 解析为文件系统主体", ex);
            }
        }
    }
}
