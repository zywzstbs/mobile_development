package cn.gulupai.server.file;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class FileSignatures {

    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final String WEBP = "image/webp";
    static final String PDF = "application/pdf";
    static final String TEXT = "text/plain";
    static final String MARKDOWN = "text/markdown";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final int DOCX_ENTRY_LIMIT = 64;
    private static final long DOCX_UNCOMPRESSED_LIMIT = 8L * 1024L * 1024L;

    private FileSignatures() {
    }

    static boolean matches(String declaredType, String fileName, Path path) {
        String extensionType = extensionType(fileName);
        if (extensionType != null && !extensionType.equals(declaredType)) {
            return false;
        }
        String detected = detect(path);
        if (TEXT.equals(declaredType) || MARKDOWN.equals(declaredType)) {
            return TEXT.equals(detected);
        }
        return declaredType.equals(detected);
    }

    private static String extensionType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return JPEG;
        }
        if (lower.endsWith(".webp")) {
            return WEBP;
        }
        if (lower.endsWith(".pdf")) {
            return PDF;
        }
        if (lower.endsWith(".txt")) {
            return TEXT;
        }
        if (lower.endsWith(".md")) {
            return MARKDOWN;
        }
        if (lower.endsWith(".docx")) {
            return DOCX;
        }
        return null;
    }

    private static String detect(Path path) {
        try {
            byte[] prefix = Files.readAllBytes(path).length > 64
                    ? read(path, 64) : Files.readAllBytes(path);
            if (startsWith(prefix, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
                return PNG;
            }
            if (startsWith(prefix, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
                return JPEG;
            }
            if (prefix.length >= 12 && startsWith(prefix, new byte[]{'R', 'I', 'F', 'F'})
                    && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P') {
                return WEBP;
            }
            if (startsWith(prefix, new byte[]{'%', 'P', 'D', 'F'})) {
                return PDF;
            }
            if (isDocx(path)) {
                return DOCX;
            }
            if (isUtf8(path)) {
                return TEXT;
            }
            return "";
        } catch (Exception ex) {
            return "";
        }
    }

    private static byte[] read(Path path, int length) throws java.io.IOException {
        try (var input = Files.newInputStream(path)) {
            return input.readNBytes(length);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUtf8(Path path) throws java.io.IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path)));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private static boolean isDocx(Path path) {
        boolean types = false;
        boolean word = false;
        int entries = 0;
        long uncompressed = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > DOCX_ENTRY_LIMIT) {
                    return false;
                }
                String name = entry.getName();
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    return false;
                }
                if ("[Content_Types].xml".equals(name)) {
                    types = true;
                }
                if (name.startsWith("word/")) {
                    word = true;
                }
                long counted = 0;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    counted += read;
                    uncompressed += read;
                    if (uncompressed > DOCX_UNCOMPRESSED_LIMIT) {
                        return false;
                    }
                }
                if (entry.getSize() >= 0 && counted > entry.getSize() + 1024) {
                    return false;
                }
            }
        } catch (Exception ex) {
            return false;
        }
        return types && word;
    }
}
