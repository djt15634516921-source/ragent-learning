package com.dujiteng.ragentlearning.system.config;

import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.common.ChatApiException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthConfig implements WebMvcConfigurer {
    private static final Set<String> PUBLIC = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/csrf");

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    @Primary
    public SaTokenDaoForRedisTemplate saTokenDao(@Value("${auth.redis-prefix}") String prefix) {
        if (prefix.isBlank() || !prefix.startsWith("ragent-learning:")) {
            throw new IllegalArgumentException("auth.redis-prefix 必须以 ragent-learning: 开头，避免与其他工程共享会话");
        }
        // 显式提供 Redis DAO，Redis 出错时异常向上传递；不使用内存回退。
        return new SaTokenDaoForRedisTemplate() {
            @Override public String wrapKey(String key) { return prefix + key; }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
                checkOrigin(request);
                if (!PUBLIC.contains(request.getServletPath())) StpUtil.checkLogin();
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    private static void checkOrigin(HttpServletRequest request) {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return;
        String origin = request.getHeader("Origin");
        if (origin == null) return; // IDEA/curl 不使用浏览器的自动凭证；仍须提供有效 Bearer。
        URI expected = URI.create(request.getRequestURL().toString());
        try {
            URI actual = URI.create(origin);
            if (expected.getScheme().equalsIgnoreCase(actual.getScheme())
                    && expected.getRawAuthority().equalsIgnoreCase(actual.getRawAuthority())
                    && (actual.getRawPath() == null || actual.getRawPath().isEmpty())) return;
        } catch (IllegalArgumentException ignored) { /* 非法 Origin 与其他跨域来源一致拒绝。 */ }
        throw new ChatApiException(403, "ORIGIN_DENIED", "请从当前服务的页面发送请求");
    }
}
