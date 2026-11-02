package cn.gulupai.server.common.security;

import cn.gulupai.server.identity.persistence.SessionRepository;
import cn.gulupai.server.identity.persistence.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * 无状态安全链：统一 401/403 信封，关闭表单登录、CSRF 与本地会话。
 *
 * <p>公开路径仅限登录、刷新、健康检查和帮助读取白名单；其余 {@code /api/v1/**}
 * 必须通过 Bearer 访问令牌过滤器建立身份。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public AccessTokenAuthenticationFilter accessTokenAuthenticationFilter(SessionRepository sessionRepository,
                                                                           UserRepository userRepository,
                                                                           Clock clock) {
        return new AccessTokenAuthenticationFilter(sessionRepository, userRepository, clock);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   AccessTokenAuthenticationFilter tokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health", "/health/**", "/error").permitAll()
                        .requestMatchers("/internal-file-transfer/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/sessions", "/api/v1/auth/sessions/refresh").permitAll()
                        // 公共帮助读取白名单；写作接口仍要求登录与独立能力。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/help/content-pack",
                                "/api/v1/help/articles",
                                "/api/v1/help/articles/*").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
