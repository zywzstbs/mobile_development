package cn.gulupai.server.common.security;

import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.identity.persistence.AuthTokenRow;
import cn.gulupai.server.identity.persistence.SessionRepository;
import cn.gulupai.server.identity.persistence.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Bearer 访问令牌过滤器：只存/查 SHA-256 摘要，逐请求联查会话与用户状态。
 *
 * <p>注销接口的特殊重放：会话已注销时，旧 access 通常应 401；为满足
 * “重复注销同一已识别会话返回 revoked=true”，仅在 DELETE current 路径上允许
 * 已注销会话建立受限 principal，由控制器按幂等键重放。
 */
public class AccessTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGOUT_PATH = "/api/v1/auth/sessions/current";

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public AccessTokenAuthenticationFilter(SessionRepository sessionRepository,
                                           UserRepository userRepository,
                                           Clock clock) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/health")
                || ("POST".equals(request.getMethod())
                && (path.equals("/api/v1/auth/sessions") || path.equals("/api/v1/auth/sessions/refresh")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AuthTokenRow> found = sessionRepository.findAuthToken(Hashing.sha256Hex(token));
        if (found.isPresent()) {
            AuthTokenRow row = found.get();
            Instant now = clock.instant();
            boolean active = !row.userDisabled()
                    && row.sessionRevokedAt() == null
                    && row.sessionExpiresAt().isAfter(now)
                    && row.accessRevokedAt() == null
                    && row.accessExpiresAt().isAfter(now);
            boolean logoutReplay = isLogoutReplay(request, row, now);
            if (active || logoutReplay) {
                authenticate(request, row, logoutReplay);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLogoutReplay(HttpServletRequest request, AuthTokenRow row, Instant now) {
        return "DELETE".equals(request.getMethod())
                && LOGOUT_PATH.equals(request.getRequestURI())
                && !row.userDisabled()
                && row.sessionRevokedAt() != null
                && row.sessionExpiresAt().isAfter(now);
    }

    private void authenticate(HttpServletRequest request, AuthTokenRow row, boolean revokedSession) {
        List<Role> roles = userRepository.findRoles(row.userId());
        AuthenticatedUser principal = new AuthenticatedUser(
                row.userId(),
                row.sessionId(),
                row.loginId(),
                row.schoolId(),
                row.displayName(),
                row.identityStatus(),
                roles,
                revokedSession);
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length());
        if (token.isBlank() || token.length() > 256 || token.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return token;
    }
}
