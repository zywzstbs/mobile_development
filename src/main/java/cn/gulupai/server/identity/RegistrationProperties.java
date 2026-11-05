package cn.gulupai.server.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 注册开关与匿名幂等指纹密钥。学校是否允许注册看 school.registration_enabled，不在这里列学校。
 */
@Component
@ConfigurationProperties(prefix = "app.registration")
public class RegistrationProperties {

    private boolean enabled = false;
    private boolean teacherApplicationEnabled = false;
    private String fingerprintKey = "";
    private String keyId = "local-dev-v1";
    private Duration receiptTtl = Duration.ofDays(7);
    private int maxPerAccount = 5;
    private int maxPerIp = 20;
    private Duration window = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTeacherApplicationEnabled() {
        return teacherApplicationEnabled;
    }

    public void setTeacherApplicationEnabled(boolean teacherApplicationEnabled) {
        this.teacherApplicationEnabled = teacherApplicationEnabled;
    }

    public String getFingerprintKey() {
        return fingerprintKey;
    }

    public void setFingerprintKey(String fingerprintKey) {
        this.fingerprintKey = fingerprintKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Duration getReceiptTtl() {
        return receiptTtl;
    }

    public void setReceiptTtl(Duration receiptTtl) {
        this.receiptTtl = receiptTtl;
    }

    public int getMaxPerAccount() {
        return maxPerAccount;
    }

    public void setMaxPerAccount(int maxPerAccount) {
        this.maxPerAccount = maxPerAccount;
    }

    public int getMaxPerIp() {
        return maxPerIp;
    }

    public void setMaxPerIp(int maxPerIp) {
        this.maxPerIp = maxPerIp;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
