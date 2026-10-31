package com.dujiteng.ragentlearning.system.vo;

public record LoginVO(long userId, String username, String token, long expiresIn) { }
