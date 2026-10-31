package com.dujiteng.ragentlearning.system.service;

import com.dujiteng.ragentlearning.system.vo.LoginVO;
import com.dujiteng.ragentlearning.system.vo.UserVO;

public interface AuthService {
    UserVO register(String username, String password);
    LoginVO login(String username, String password);
    UserVO currentUser(long userId);
    void logout();
}
