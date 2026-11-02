package cn.gulupai.server.identity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 口令编码：先对完整 UTF-8 口令做 SHA-256 并 Base64，再交给 Spring BCrypt（成本10）。
 *
 * <p>BCrypt 本身只使用前 72 字节；预处理后仍使用成熟 BCrypt 校验，同时支持契约允许的
 * 1～256 字符，避免长口令被静默截断。数据库仍只保存 BCrypt 结果，不保存明文。
 */
public class GulupaiPasswordEncoder implements PasswordEncoder {

    private final BCryptPasswordEncoder delegate;

    public GulupaiPasswordEncoder(int cost) {
        this.delegate = new BCryptPasswordEncoder(cost);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(prehash(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return delegate.matches(prehash(rawPassword), encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private String prehash(CharSequence rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
