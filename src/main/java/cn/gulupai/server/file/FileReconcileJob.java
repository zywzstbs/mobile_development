package cn.gulupai.server.file;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发文件核对。过期上传在请求路径上已经拒绝，不依赖这次触发才失效。
 */
@Component
public class FileReconcileJob {

    private final FileService files;

    public FileReconcileJob(FileService files) {
        this.files = files;
    }

    @Scheduled(
            initialDelayString = "${app.files.reconcile-initial-delay-ms:3600000}",
            fixedDelayString = "${app.files.reconcile-delay-ms:3600000}")
    public void reconcile() {
        files.reconcile();
    }
}
