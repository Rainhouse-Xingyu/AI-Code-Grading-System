package com.rainexis.backend.security;

import com.rainexis.backend.common.BusinessException;

/**
 * 认证上下文（ThreadLocal实现）
 * 存储当前请求的用户信息，每个线程独立隔离，请求结束后自动清理
 * 提供便捷的权限校验方法
 */
public final class AuthContext {
    /** 使用 ThreadLocal 存储当前请求的认证用户信息 */
    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 设置当前线程的认证用户 */
    public static void set(AuthUser user) {
        CURRENT.set(user);
    }

    /** 获取当前线程的认证用户，未登录则抛出401异常 */
    public static AuthUser get() {
        AuthUser user = CURRENT.get();
        if (user == null) {
            throw BusinessException.unauthorized("未登录或登录已过期");
        }
        return user;
    }

    /** 校验当前用户是否为教师，否则抛出403异常 */
    public static void requireTeacher() {
        if (!get().isTeacher()) {
            throw BusinessException.forbidden("仅教师可操作");
        }
    }

    public static void requireAdmin() {
        if (!"admin".equals(get().role())) {
            throw BusinessException.forbidden("仅管理员可操作");
        }
    }

    /** 校验当前用户是否为学生，否则抛出403异常 */
    public static void requireStudent() {
        if (!get().isStudent()) {
            throw BusinessException.forbidden("仅学生可操作");
        }
    }

    /** 清理当前线程的认证信息（请求结束后调用，防止内存泄漏） */
    public static void clear() {
        CURRENT.remove();
    }
}
