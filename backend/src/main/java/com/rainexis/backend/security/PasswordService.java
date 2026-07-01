package com.rainexis.backend.security;

import com.rainexis.backend.common.BusinessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 密码服务
 * 使用 BCrypt 算法对密码进行加密和校验，并提供密码强度验证
 */
@Service
public class PasswordService {
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 对明文密码进行 BCrypt 加密 */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** 校验明文密码是否与已加密密码匹配 */
    public boolean matches(String rawPassword, String encodedPassword) {
        return encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }

    /** 校验密码强度：至少8位，且需同时包含字母和数字 */
    public void requireStrong(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw BusinessException.badRequest("密码至少 8 位，且需包含字母和数字");
        }
        boolean hasLetter = rawPassword.chars().anyMatch(Character::isLetter);
        boolean hasDigit = rawPassword.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw BusinessException.badRequest("密码至少 8 位，且需包含字母和数字");
        }
    }
}
