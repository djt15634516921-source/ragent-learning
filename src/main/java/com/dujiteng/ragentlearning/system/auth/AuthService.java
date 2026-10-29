package com.dujiteng.ragentlearning.system.auth;

import com.dujiteng.ragentlearning.common.ChatApiException;
import com.dujiteng.ragentlearning.system.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService implements UserDetailsService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserView register(String username, String password) {
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
            return new UserView(userRepository.create(normalized, hash), normalized);
        } catch (DuplicateKeyException error) {
            throw new ChatApiException(409, "USERNAME_TAKEN", "用户名已存在，请直接登录或换一个用户名");
        } catch (DataAccessException error) {
            throw new ChatApiException(503, "AUTH_DATABASE_UNAVAILABLE", "用户数据库暂时不可用，请检查数据库连接", error);
        }
    }

    /** 框架在登录时调用；密码比对交给 DaoAuthenticationProvider 和 PasswordEncoder。 */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = userRepository.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
        return new LoginUser(user.id(), user.username(), user.passwordHash());
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    /** 只返回允许前端看到的身份字段。 */
    public record UserView(long userId, String username) {
        public static UserView from(LoginUser user) {
            return new UserView(user.userId(), user.getUsername());
        }
    }
}
