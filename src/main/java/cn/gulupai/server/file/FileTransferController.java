package cn.gulupai.server.file;

import cn.gulupai.server.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * 短期上传和下载字节端点。不接收业务 Bearer，每次访问都重新核对当前权限。
 */
@RestController
public class FileTransferController {

    private final FileService files;

    public FileTransferController(FileService files) {
        this.files = files;
    }

    @PutMapping("/internal-file-transfer/{token}")
    public ResponseEntity<Void> upload(@PathVariable String token, HttpServletRequest request) throws Exception {
        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw ApiException.validation("缺少上传内容类型");
        }
        files.receive(token, contentType, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/internal-file-transfer/{token}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String token) {
        FileService.DownloadPayload payload = files.openDownload(token);
        String fallback = payload.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (fallback.isBlank() || ".".equals(fallback) || "..".equals(fallback)) {
            fallback = "file";
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(fallback, StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try (var input = payload.body()) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(body);
    }
}
