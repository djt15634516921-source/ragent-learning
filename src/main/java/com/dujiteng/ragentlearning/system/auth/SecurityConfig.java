package com.dujiteng.ragentlearning.system.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.json.JsonMapper;

/** 框架负责密码验证、Session、登录时更换 Session ID、CSRF 校验和退出。 */
@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService,
                                                   PasswordEncoder encoder, JsonMapper json) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(authService);
        provider.setPasswordEncoder(encoder);
        http.authenticationProvider(provider)
                .authorizeHttpRequests(auth -> auth
                        // 首次请求已校验登录；允许容器完成 SSE 和错误分派。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/login-test.html", "/stream-test.html", "/favicon.ico", "/api/hello",
                                "/api/auth/csrf", "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                write(json, response, 401, Map.of("code", "AUTH_REQUIRED", "message", "请先登录")))
                        .accessDeniedHandler((request, response, error) -> {
                            boolean csrf = error instanceof CsrfException;
                            write(json, response, 403, Map.of("code", csrf ? "CSRF_INVALID" : "ACCESS_DENIED",
                                    "message", csrf ? "请求凭证已失效，请刷新页面后重试" : "当前用户没有访问权限"));
                        }))
                .formLogin(form -> form
                        .loginPage("/login-test.html")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) ->
                                write(json, response, 200, AuthService.UserView.from((LoginUser) authentication.getPrincipal())))
                        .failureHandler((request, response, error) -> {
                            boolean unavailable = error instanceof AuthenticationServiceException;
                            write(json, response, unavailable ? 503 : 401, Map.of(
                                    "code", unavailable ? "AUTH_DATABASE_UNAVAILABLE" : "AUTH_INVALID_CREDENTIALS",
                                    "message", unavailable ? "登录服务暂时不可用，请检查数据库连接" : "用户名或密码错误"));
                        }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                write(json, response, 200, Map.of("message", "已退出登录"))));
        // 保留默认 CSRF 防护；POST 请求需带 /api/auth/csrf 返回的凭证。
        return http.build();
    }

    private static void write(JsonMapper json, HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
