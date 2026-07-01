package com.rainexis.backend.security;

/**
 * 认证用户记录（不可变数据类）
 * 从 JWT Token 中解析出的用户身份信息，通过 AuthContext 在请求上下文中传递
 */
public record AuthUser(Long id, String username, String role, String realName, String className, Integer tokenVersion,
                       String csrfToken) {
    /** 判断当前用户是否为教师或管理员 */
    public boolean isTeacher() {
        return "teacher".equals(role) || "admin".equals(role);
    }

    /** 判断当前用户是否为学生 */
    public boolean isStudent() {
        return "student".equals(role);
    }
}
