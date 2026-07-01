package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.PasswordService;
import com.rainexis.backend.service.business.AccessControlService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户管理 API 控制器
 * 提供个人信息查询/修改、密码修改、学生批量导入、学生管理等功能
 * 教师可以管理本班学生，admin 可以管理所有学生
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserApiController {
    private final TUserMapper userMapper;
    private final PasswordService passwordService;
    private final AccessControlService accessControlService;

    public UserApiController(TUserMapper userMapper,
                             PasswordService passwordService,
                             AccessControlService accessControlService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.accessControlService = accessControlService;
    }

    /** 获取当前登录用户的个人信息 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        TUser user = userMapper.selectById(AuthContext.get().id());
        if (user == null) {
            throw BusinessException.unauthorized("用户不存在");
        }
        return ApiResponse.ok(userPayload(user));
    }

    /** 修改个人信息（姓名、邮箱、电话） */
    @PutMapping("/me")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody ProfileUpdateRequest request) {
        TUser user = userMapper.selectById(AuthContext.get().id());
        if (user == null) {
            throw BusinessException.unauthorized("用户不存在");
        }
        user.setRealName(clean(request == null ? null : request.realName()));
        user.setEmail(clean(request == null ? null : request.email()));
        user.setPhone(clean(request == null ? null : request.phone()));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResponse.ok(userPayload(user));
    }

    /** 修改当前用户密码，修改后 Token 版本号递增使旧 Token 失效 */
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@RequestParam String oldPassword, @RequestParam String newPassword) {
        TUser user = userMapper.selectById(AuthContext.get().id());
        if (user == null || !passwordService.matches(oldPassword, user.getPassword())) {
            throw BusinessException.badRequest("旧密码不正确");
        }
        passwordService.requireStrong(newPassword);
        user.setPassword(passwordService.encode(newPassword));
        user.setNeedPasswordChange(false);
        user.setTokenVersion(nextTokenVersion(user));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResponse.ok(null);
    }

    /** 通过 Excel 文件批量导入学生（需要 CSRF Token） */
    @PostMapping("/batch-import")
    public ApiResponse<Map<String, Object>> batchImport(@RequestParam MultipartFile file,
                                                        @RequestParam(defaultValue = "Stu123456") String initialPassword) {
        AuthContext.requireTeacher();
        passwordService.requireStrong(initialPassword);
        List<TUser> imported = new ArrayList<>();
        List<Map<String, Object>> successRows = new ArrayList<>();
        List<Map<String, Object>> skippedRows = new ArrayList<>();
        List<Map<String, Object>> failedRows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                int rowNumber = i + 1;
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String username = formatter.formatCellValue(row.getCell(0)).trim();
                if (username.isBlank()) {
                    failedRows.add(importResult(rowNumber, "", "failed", "学号不能为空"));
                    continue;
                }
                String realName = formatter.formatCellValue(row.getCell(1)).trim();
                String importedClassName = formatter.formatCellValue(row.getCell(2)).trim();
                if (realName.isBlank()) {
                    failedRows.add(importResult(rowNumber, username, "failed", "姓名不能为空"));
                    continue;
                }
                if (importedClassName.isBlank()) {
                    failedRows.add(importResult(rowNumber, username, "failed", "班级不能为空"));
                    continue;
                }
                if (userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, username)) > 0) {
                    skippedRows.add(importResult(rowNumber, username, "skipped", "学号已存在"));
                    continue;
                }
                TUser student = new TUser();
                student.setUsername(username);
                student.setRealName(realName);
                String teacherClassName = AuthContext.get().className();
                if (teacherClassName != null && !teacherClassName.isBlank()
                        && !teacherClassName.equals(importedClassName)) {
                    throw BusinessException.forbidden("只能导入自己班级的学生");
                }
                student.setClassName(importedClassName);
                student.setRole("student");
                student.setPassword(passwordService.encode(initialPassword));
                student.setNeedPasswordChange(true);
                student.setLoginFailCount(0);
                student.setTokenVersion(0);
                student.setCreatedAt(LocalDateTime.now());
                userMapper.insert(student);
                imported.add(student);
                successRows.add(importResult(rowNumber, username, "success", "导入成功"));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.badRequest("学生 Excel 导入失败: " + ex.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRows", successRows.size() + skippedRows.size() + failedRows.size());
        result.put("imported", imported.size());
        result.put("success", successRows);
        result.put("skipped", skippedRows);
        result.put("failed", failedRows);
        result.put("students", imported);
        return ApiResponse.ok(result);
    }

    /** 教师手动创建单个学生账号 */
    @PostMapping("/students")
    public ApiResponse<Map<String, Object>> createStudent(@RequestBody CreateStudentRequest request) {
        AuthContext.requireTeacher();
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw BusinessException.badRequest("学号不能为空");
        }
        if (request.realName() == null || request.realName().isBlank()) {
            throw BusinessException.badRequest("姓名不能为空");
        }
        String password = request.initialPassword() == null || request.initialPassword().isBlank()
                ? "Stu123456"
                : request.initialPassword();
        passwordService.requireStrong(password);
        String username = request.username().trim();
        if (userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, username)) > 0) {
            throw BusinessException.conflict("学号已存在");
        }
        String className = studentClassName(request.className());
        TUser student = new TUser();
        student.setUsername(username);
        student.setRealName(request.realName().trim());
        student.setClassName(className);
        student.setRole("student");
        student.setPassword(passwordService.encode(password));
        student.setNeedPasswordChange(true);
        student.setLoginFailCount(0);
        student.setTokenVersion(0);
        student.setCreatedAt(LocalDateTime.now());
        student.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(student);
        return ApiResponse.ok(userPayload(student));
    }

    /** 教师查询学生列表（默认只返回本班学生，admin可查看所有） */
    @GetMapping("/students")
    public ApiResponse<List<TUser>> students(@RequestParam(name = "class", required = false) String className,
                                             @RequestParam(name = "q", required = false) String keyword) {
        AuthContext.requireTeacher();
        LambdaQueryWrapper<TUser> query = new LambdaQueryWrapper<TUser>().eq(TUser::getRole, "student");
        String teacherClassName = AuthContext.get().className();
        if (!"admin".equals(AuthContext.get().role()) && teacherClassName != null && !teacherClassName.isBlank()) {
            if (className != null && !className.isBlank() && !teacherClassName.equals(className)) {
                throw BusinessException.forbidden("只能查看自己班级的学生");
            }
            query.eq(TUser::getClassName, teacherClassName);
        } else if (className != null && !className.isBlank()) {
            query.eq(TUser::getClassName, className);
        }
        String cleanedKeyword = clean(keyword);
        if (cleanedKeyword != null) {
            query.and(wrapper -> wrapper
                    .like(TUser::getUsername, cleanedKeyword)
                    .or()
                    .like(TUser::getRealName, cleanedKeyword)
                    .or()
                    .like(TUser::getClassName, cleanedKeyword));
        }
        return ApiResponse.ok(userMapper.selectList(query.orderByAsc(TUser::getClassName).orderByAsc(TUser::getUsername)));
    }

    /** 下载学生批量导入的 Excel 模板 */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        AuthContext.requireTeacher();
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("students");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("学号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("班级");
            var example = sheet.createRow(1);
            example.createCell(0).setCellValue("2024001");
            example.createCell(1).setCellValue("张三");
            example.createCell(2).setCellValue(AuthContext.get().className() == null ? "软件工程2401" : AuthContext.get().className());
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
            workbook.write(out);
            return downloadBytes(out.toByteArray(), "学生导入模板.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (Exception ex) {
            throw new BusinessException(500, "生成学生导入模板失败: " + ex.getMessage());
        }
    }

    /** 教师重置学生密码 */
    @PostMapping("/reset-password/{studentId}")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable Long studentId,
                                                          @RequestParam(defaultValue = "Stu123456") String newPassword) {
        AuthContext.requireTeacher();
        passwordService.requireStrong(newPassword);
        TUser student = accessControlService.requireTeacherCanManageStudent(studentId);
        student.setPassword(passwordService.encode(newPassword));
        student.setNeedPasswordChange(true);
        student.setLoginFailCount(0);
        student.setLockedUntil(null);
        student.setTokenVersion(nextTokenVersion(student));
        student.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(student);
        return ApiResponse.ok(Map.of("studentId", studentId, "reset", true));
    }

    /** 教师批量重置当前可管理学生密码 */
    @PostMapping("/reset-password-all")
    public ApiResponse<Map<String, Object>> resetAllPasswords(@RequestParam(defaultValue = "Stu123456") String newPassword,
                                                              @RequestBody(required = false) ResetAllPasswordRequest request) {
        AuthContext.requireTeacher();
        passwordService.requireStrong(newPassword);
        LambdaQueryWrapper<TUser> query = new LambdaQueryWrapper<TUser>().eq(TUser::getRole, "student");
        if (request != null && request.studentIds() != null && !request.studentIds().isEmpty()) {
            query.in(TUser::getId, request.studentIds());
        }
        String teacherClassName = AuthContext.get().className();
        if (!"admin".equals(AuthContext.get().role()) && teacherClassName != null && !teacherClassName.isBlank()) {
            query.eq(TUser::getClassName, teacherClassName);
        }
        List<TUser> students = userMapper.selectList(query);
        for (TUser student : students) {
            student.setPassword(passwordService.encode(newPassword));
            student.setNeedPasswordChange(true);
            student.setLoginFailCount(0);
            student.setLockedUntil(null);
            student.setTokenVersion(nextTokenVersion(student));
            student.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(student);
        }
        return ApiResponse.ok(Map.of("resetCount", students.size()));
    }

    private int nextTokenVersion(TUser user) {
        return (user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1;
    }

    private String studentClassName(String requestedClassName) {
        String teacherClassName = AuthContext.get().className();
        String className = clean(requestedClassName);
        if (!"admin".equals(AuthContext.get().role()) && teacherClassName != null && !teacherClassName.isBlank()) {
            if (className != null && !teacherClassName.equals(className)) {
                throw BusinessException.forbidden("只能新增自己班级的学生");
            }
            return teacherClassName;
        }
        if (className == null) {
            throw BusinessException.badRequest("班级不能为空");
        }
        return className;
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private Map<String, Object> userPayload(TUser user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("realName", user.getRealName() == null ? "" : user.getRealName());
        payload.put("email", user.getEmail() == null ? "" : user.getEmail());
        payload.put("phone", user.getPhone() == null ? "" : user.getPhone());
        payload.put("className", user.getClassName() == null ? "" : user.getClassName());
        payload.put("needPasswordChange", Boolean.TRUE.equals(user.getNeedPasswordChange()));
        TUser teacher = teacherForStudent(user);
        payload.put("teacherUsername", teacher == null ? "" : teacher.getUsername());
        payload.put("teacherRealName", teacher == null ? "" : teacher.getRealName());
        return payload;
    }

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

    private Map<String, Object> importResult(int rowNumber, String username, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("row", rowNumber);
        result.put("username", username);
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    public record ProfileUpdateRequest(String realName, String email, String phone) {
    }

    public record CreateStudentRequest(String username, String realName, String className, String initialPassword) {
    }

    public record ResetAllPasswordRequest(List<Long> studentIds) {
    }

    private ResponseEntity<byte[]> downloadBytes(byte[] bytes, String filename, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(bytes);
    }
}
