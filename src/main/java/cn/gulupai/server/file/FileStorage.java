package cn.gulupai.server.file;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * 私有目录里的临时文件和已完成文件。路径只由文件 ID 组成，不使用用户提供的文件名。
 */
@Component
public class FileStorage {

    static final long MAX_BYTES = 8L * 1024L * 1024L;

    private final Path root;
    private final long minimumFreeBytes;

    public FileStorage(FileProperties properties) {
        this.root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        this.minimumFreeBytes = properties.getMinimumFreeBytes();
    }

    public void ensureSpace(long bytes) {
        try {
            Files.createDirectories(incomingDir());
            long usable = Files.getFileStore(incomingDir()).getUsableSpace();
            if (usable < bytes + minimumFreeBytes) {
                throw new ApiException(ApiCode.FILE_TOO_LARGE, "磁盘剩余空间不足，已拒绝新的上传");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件目录暂时不可用");
        }
    }

    public StoredBytes writeIncoming(String fileId, InputStream body, long limit) {
        Path partial = incoming(fileId).resolveSibling(fileId + ".partial");
        Path target = incoming(fileId);
        try {
            Files.createDirectories(incomingDir());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (DigestInputStream input = new DigestInputStream(body, digest);
                 OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > limit || size > MAX_BYTES) {
                        throw new ApiException(ApiCode.FILE_TOO_LARGE);
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (Files.exists(target)) {
                Files.deleteIfExists(partial);
                throw new ApiException(ApiCode.RESOURCE_CHANGED, "该上传已经接收过字节，不能覆盖");
            }
            move(partial, target);
            return new StoredBytes(size, HexFormat.of().formatHex(digest.digest()));
        } catch (ApiException ex) {
            deleteQuietly(partial);
            throw ex;
        } catch (Exception ex) {
            deleteQuietly(partial);
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件暂时无法写入");
        }
    }

    public StoredBytes hashStream(InputStream body, long limit) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (DigestInputStream input = new DigestInputStream(body, digest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    size += read;
                    if (size > limit || size > MAX_BYTES) {
                        throw new ApiException(ApiCode.FILE_TOO_LARGE);
                    }
                }
            }
            return new StoredBytes(size, HexFormat.of().formatHex(digest.digest()));
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件暂时无法读取");
        }
    }

    public byte[] readPrefix(Path path, int length) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(length);
        } catch (IOException ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件暂时无法读取");
        }
    }

    public Path incoming(String fileId) {
        return incomingDir().resolve(safeId(fileId));
    }

    public Path ready(String fileId) {
        return readyDir().resolve(safeId(fileId));
    }

    public boolean incomingExists(String fileId) {
        return Files.isRegularFile(incoming(fileId));
    }

    public boolean readyExists(String fileId) {
        return Files.isRegularFile(ready(fileId));
    }

    public void promote(String fileId) {
        try {
            Files.createDirectories(readyDir());
            Path target = ready(fileId);
            if (Files.exists(target)) {
                return;
            }
            move(incoming(fileId), target);
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件暂时无法完成保存");
        }
    }

    public void deleteIncoming(String fileId) {
        deleteQuietly(incoming(fileId));
    }

    public void deleteReady(String fileId) {
        deleteQuietly(ready(fileId));
    }

    public void deleteUnexpectedIncoming(java.util.Set<String> knownIds) {
        deleteUnknown(incomingDir(), knownIds);
    }

    public InputStream openReady(String fileId) {
        try {
            return Files.newInputStream(ready(fileId));
        } catch (IOException ex) {
            throw new ApiException(ApiCode.NOT_FOUND, "文件内容不存在");
        }
    }

    private void deleteUnknown(Path directory, java.util.Set<String> knownIds) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".partial") || !knownIds.contains(name)) {
                    deleteQuietly(path);
                }
            });
        } catch (IOException ex) {
            throw new ApiException(ApiCode.SERVICE_UNAVAILABLE, "文件目录暂时不可用");
        }
    }

    private Path incomingDir() {
        return root.resolve("incoming");
    }

    private Path readyDir() {
        return root.resolve("ready");
    }

    private static String safeId(String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9_-]{1,120}")) {
            throw new ApiException(ApiCode.NOT_FOUND);
        }
        return fileId;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败留到下一次恢复任务。
        }
    }

    public record StoredBytes(long size, String sha256) {
    }
}
