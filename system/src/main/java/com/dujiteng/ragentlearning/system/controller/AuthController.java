package com.dujiteng.ragentlearning.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.system.dto.CredentialsRequest;
import com.dujiteng.ragentlearning.system.service.AuthService;
import com.dujiteng.ragentlearning.system.vo.LoginVO;
import com.dujiteng.ragentlearning.system.vo.UserVO;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserVO register(@RequestBody CredentialsRequest request) {
        return service.register(request == null ? null : request.username(), request == null ? null : request.password());
    }

    @PostMapping("/login")
    public LoginVO login(@RequestBody CredentialsRequest request) {
        return service.login(request == null ? null : request.username(), request == null ? null : request.password());
    }

    @GetMapping("/me")
    public UserVO me() { return service.currentUser(StpUtil.getLoginIdAsLong()); }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        service.logout();
        return Map.of("message", "已退出登录");
    }

    @GetMapping("/csrf")
    public void obsoleteCsrf() {
        throw new ChatApiException(410, "AUTH_PROTOCOL_CHANGED", "认证已改为 Authorization: Bearer，请更新页面或测试请求");
    }
}
