package com.dujiteng.ragentlearning.system.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Spring Security 验证成功后的身份；userId 来自数据库，不接受前端指定。 */
public final class LoginUser extends User {
    private static final long serialVersionUID = 1L;
    private final long userId;

    public LoginUser(long userId, String username, String passwordHash) {
        super(username, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = userId;
    }

    public long userId() { return userId; }
    // 父类实现了 CredentialsContainer；框架认证成功后清除身份对象中的密码哈希。
}
