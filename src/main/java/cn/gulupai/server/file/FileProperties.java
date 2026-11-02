package cn.gulupai.server.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.files")
public class FileProperties {

    private String root = System.getProperty("java.io.tmpdir") + "/gulupai-files";
    private String publicBaseUrl = "https://localhost";
    private long minimumFreeBytes;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public long getMinimumFreeBytes() {
        return minimumFreeBytes;
    }

    public void setMinimumFreeBytes(long minimumFreeBytes) {
        this.minimumFreeBytes = minimumFreeBytes;
    }
}
