package cn.gulupai.server.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 认证与会话参数。P00 已确认 access 30 分钟、refresh 固定 7 天；其余为登录限流。
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private Duration accessTtl = Duration.ofMinutes(30);
    private Duration refreshTtl = Duration.ofDays(7);
    private int bcryptCost = 10;
    private int loginMaxFailures = 5;
    private Duration loginWindow = Duration.ofMinutes(15);
    private Duration loginBlock = Duration.ofMinutes(15);

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public int getBcryptCost() {
        return bcryptCost;
    }

    public void setBcryptCost(int bcryptCost) {
        this.bcryptCost = bcryptCost;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public Duration getLoginWindow() {
        return loginWindow;
    }

    public void setLoginWindow(Duration loginWindow) {
        this.loginWindow = loginWindow;
    }

    public Duration getLoginBlock() {
        return loginBlock;
    }

    public void setLoginBlock(Duration loginBlock) {
        this.loginBlock = loginBlock;
    }
}
