package com.dujiteng.ragentlearning.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.system.entity.UserDO;
import com.dujiteng.ragentlearning.system.mapper.UserMapper;
import com.dujiteng.ragentlearning.system.service.AuthService;
import com.dujiteng.ragentlearning.system.vo.LoginVO;
import com.dujiteng.ragentlearning.system.vo.UserVO;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper users;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public AuthServiceImpl(UserMapper users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("invalid-account-timing-placeholder");
    }

    @Override
    public UserVO register(String username, String password) {
        String normalized = normalizeUsername(username);
        if (!normalized.matches("[a-z][a-z0-9_]{2,31}")) {
            throw new ChatApiException(400, "USERNAME_INVALID",
                    "用户名使用 3～32 位英文字母、数字或下划线，以字母开头");
        }
        // BCrypt 有 72 字节限制；密码按原样处理，保留大小写和空格。
        if (password == null || password.length() < 8 || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ChatApiException(400, "PASSWORD_INVALID", "密码至少 8 个字符，UTF-8 长度最多 72 字节");
        }
        String hash = passwordEncoder.encode(password);
        try {
            UserDO user = new UserDO();
            user.setUsername(normalized);
            user.setPasswordHash(hash);
            user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            users.insert(user);
            return new UserVO(user.getId(), normalized);
        } catch (DuplicateKeyException error) {
            throw new ChatApiException(409, "USERNAME_TAKEN", "用户名已存在，请直接登录或换一个用户名");
        } catch (DataAccessException error) {
            throw new ChatApiException(503, "AUTH_DATABASE_UNAVAILABLE", "用户数据库暂时不可用，请检查数据库连接", error);
        }
    }

    @Override
    public LoginVO login(String username, String password) {
        UserDO user;
        try {
            user = users.selectOne(new QueryWrapper<UserDO>().eq("username", normalizeUsername(username)));
        } catch (DataAccessException error) {
            throw new ChatApiException(503, "AUTH_DATABASE_UNAVAILABLE", "用户数据库暂时不可用，请检查数据库连接", error);
        }
        // 未知账号也进行一次 BCrypt 比对；认证成功之前绝不建立 Redis 登录会话。
        boolean valid = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwordEncoder.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !valid) {
            throw new ChatApiException(401, "AUTH_INVALID_CREDENTIALS", "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return new LoginVO(user.getId(), user.getUsername(), StpUtil.getTokenValue(), StpUtil.getTokenTimeout());
    }

    @Override
    public UserVO currentUser(long userId) {
        UserDO user = users.selectById(userId);
        if (user == null) {
            StpUtil.logout();
            throw new ChatApiException(401, "AUTH_REQUIRED", "账号已不存在，请重新登录");
        }
        return new UserVO(user.getId(), user.getUsername());
    }

    @Override
    public void logout() {
        // 默认只撤销本次请求携带的 Token；同账号其他终端继续有效。
        StpUtil.logout();
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

}
