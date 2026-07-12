package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.JwtService;
import com.rainexis.backend.security.PasswordService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 API 控制器
 * 提供用户注册、登录和 Token 刷新功能
 * 注册和登录接口无需认证，刷新接口需携带有效 Token
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {
    private final TUserMapper userMapper;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    public AuthApiController(TUserMapper userMapper, PasswordService passwordService, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    /** 用户注册（默认角色为 teacher） */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        if (request.username() == null || request.password() == null) {
            throw BusinessException.badRequest("用户名和密码不能为空");
        }
        passwordService.requireStrong(request.password());
        if (userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, request.username())) > 0) {
            throw BusinessException.conflict("用户名已存在");
        }
        TUser user = new TUser();
        user.setUsername(request.username());
        user.setPassword(passwordService.encode(request.password()));
        user.setRole("teacher");
        user.setRealName(request.realName());
        user.setEmail(request.email());
        user.setClassName(request.className());
        user.setTeachingClass(request.className());
        user.setNeedPasswordChange(false);
        user.setLoginFailCount(0);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return ApiResponse.ok(tokenPayload(user));
    }

    /** 用户登录：校验密码 → 检查锁定 → 返回JWT Token */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        TUser user = userMapper.selectOne(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, request.username()).last("limit 1"));
        if (user == null) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(423, "账户已锁定，请 15 分钟后再试");
        }
        if (!passwordMatches(request.password(), user.getPassword())) {
            recordLoginFailure(user);
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);
        return ApiResponse.ok(tokenPayload(user));
    }

    /** 刷新当前用户的JWT Token */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        TUser user = userMapper.selectById(AuthContext.get().id());
        if (user == null) {
            throw BusinessException.unauthorized("用户不存在");
        }
        return ApiResponse.ok(tokenPayload(user));
    }

    private boolean passwordMatches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        return passwordService.matches(raw, stored);
    }

    private void recordLoginFailure(TUser user) {
        int failCount = user.getLoginFailCount() == null ? 1 : user.getLoginFailCount() + 1;
        user.setLoginFailCount(failCount);
        if (failCount >= 5) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        }
        userMapper.updateById(user);
    }

    /** 构建Token响应：JWT + CSRF Token + 过期时间 + 用户信息 */
    private Map<String, Object> tokenPayload(TUser user) {
        return Map.of(
                "token", jwtService.createToken(user),
                "csrfToken", jwtService.csrfToken(user),
                "token_type", "Bearer",
                "expires_in", 24 * 3600,
                "user", userPayload(user)
        );
    }

    private Map<String, Object> userPayload(TUser user) {
        TUser teacher = teacherForStudent(user);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("realName", user.getRealName() == null ? "" : user.getRealName());
        payload.put("email", user.getEmail() == null ? "" : user.getEmail());
        payload.put("phone", user.getPhone() == null ? "" : user.getPhone());
        payload.put("className", user.getClassName() == null ? "" : user.getClassName());
        payload.put("employeeNo", user.getEmployeeNo() == null ? "" : user.getEmployeeNo());
        payload.put("college", user.getCollege() == null ? "" : user.getCollege());
        payload.put("teachingCourse", user.getTeachingCourse() == null ? "" : user.getTeachingCourse());
        payload.put("teachingClass", user.getTeachingClass() == null ? "" : user.getTeachingClass());
        payload.put("teacherUsername", teacher == null ? "" : teacher.getUsername());
        payload.put("teacherRealName", teacher == null ? "" : teacher.getRealName());
        payload.put("needPasswordChange", Boolean.TRUE.equals(user.getNeedPasswordChange()));
        return payload;
    }

    /** 查找学生所属班级的教师（用于前端显示） */
    private TUser teacherForStudent(TUser user) {
        if (user == null || !"student".equals(user.getRole()) || user.getClassName() == null || user.getClassName().isBlank()) {
            return null;
        }
        return userMapper.selectOne(new LambdaQueryWrapper<TUser>()
                .eq(TUser::getRole, "teacher")
                .eq(TUser::getClassName, user.getClassName())
                .orderByAsc(TUser::getId)
                .last("limit 1"));
    }

    public record LoginRequest(String username, String password) {
    }

    public record RegisterRequest(String username, String password, String realName, String email, String className) {
    }
}
