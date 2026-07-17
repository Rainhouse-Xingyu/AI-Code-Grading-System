package com.rainexis.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 在请求进入Controller之前校验 Token 有效性、用户是否存在、CSRF 防护
 * 校验通过后将用户信息存入 AuthContext；失败则直接返回401/403
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;
    private final TUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, TUserMapper userMapper, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    /** 前置处理：校验 Token → 查库验证用户 → 可选 CSRF 校验 → 存入 AuthContext */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw BusinessException.unauthorized("缺少 Authorization Bearer Token");
            }
            AuthUser authUser = jwtService.parse(authorization.substring("Bearer ".length()));
            TUser currentUser = userMapper.selectById(authUser.id());
            if (currentUser == null || currentTokenVersion(currentUser) != authUser.tokenVersion()) {
                throw BusinessException.unauthorized("Token 已失效");
            }
            if (requiresCsrf(request) && !csrfMatches(request, authUser)) {
                throw BusinessException.forbidden("CSRF Token 无效");
            }
            AuthContext.set(authUser);
            return true;
        } catch (BusinessException ex) {
            response.setStatus(ex.getCode());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(ex.getCode(), ex.getMessage())));
            return false;
        }
    }

    /** 请求完成后清理 ThreadLocal，防止内存泄漏 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    /** 获取数据库中用户的当前Token版本号 */
    private int currentTokenVersion(TUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 判断当前请求是否需要 CSRF 校验（写操作+特定高危接口） */
    private boolean requiresCsrf(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) && !"PUT".equalsIgnoreCase(request.getMethod())
                && !"PATCH".equalsIgnoreCase(request.getMethod()) && !"DELETE".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return path.equals("/api/v1/ai-tasks/batch-score")
                || path.startsWith("/api/v1/grade-publish/")
                || path.equals("/api/v1/files/cleanup")
                || (path.startsWith("/api/v1/semesters/") && path.endsWith("/files/cleanup"))
                || path.equals("/api/v1/users/batch-import")
                || path.equals("/api/v1/admin/accounts/import")
                || path.startsWith("/api/v1/admin/config")
                || path.startsWith("/api/v1/admin/accounts")
                || path.equals("/api/v1/exports/pdf/batch");
    }

    /** 校验请求头中的 X-CSRF-Token 是否与Token中携带的一致 */
    private boolean csrfMatches(HttpServletRequest request, AuthUser authUser) {
        String header = request.getHeader("X-CSRF-Token");
        return header != null && !header.isBlank() && header.equals(authUser.csrfToken());
    }
}
