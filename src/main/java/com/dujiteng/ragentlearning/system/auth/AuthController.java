package com.dujiteng.ragentlearning.system.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.UserView register(@RequestBody RegisterRequest request) {
        return authService.register(request == null ? null : request.username(),
                request == null ? null : request.password());
    }

    @GetMapping("/me")
    public AuthService.UserView me(@AuthenticationPrincipal LoginUser user) {
        return AuthService.UserView.from(user);
    }

    // 登录和退出由 SecurityConfig 中的框架过滤器处理，不在 Controller 手写认证流程。
    public record RegisterRequest(String username, String password) { }
    public record CsrfView(String headerName, String token) { }
}
