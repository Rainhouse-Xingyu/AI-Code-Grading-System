package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.PasswordService;
import com.rainexis.backend.service.business.RuntimeConfigService;
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

@RestController
@RequestMapping("/api/v1/admin")
public class AdminApiController {
    private static final String DEFAULT_TEACHER_PASSWORD = "Teacher123456";
    private final TUserMapper userMapper;
    private final PasswordService passwordService;
    private final RuntimeConfigService runtimeConfigService;

    public AdminApiController(TUserMapper userMapper,
                              PasswordService passwordService,
                              RuntimeConfigService runtimeConfigService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        AuthContext.requireAdmin();
        return ApiResponse.ok(Map.of(
                "envPath", runtimeConfigService.envPath().toString(),
                "items", runtimeConfigService.list()
        ));
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(@RequestBody ConfigUpdateRequest request) {
        AuthContext.requireAdmin();
        List<Map<String, Object>> items = runtimeConfigService.update(request == null ? Map.of() : request.values());
        return ApiResponse.ok(Map.of(
                "envPath", runtimeConfigService.envPath().toString(),
                "items", items
        ));
    }

    @GetMapping("/accounts")
    public ApiResponse<List<Map<String, Object>>> accounts() {
        AuthContext.requireAdmin();
        List<TUser> users = userMapper.selectList(new LambdaQueryWrapper<TUser>()
                .in(TUser::getRole, List.of("teacher", "admin"))
                .orderByAsc(TUser::getRole)
                .orderByAsc(TUser::getUsername));
        return ApiResponse.ok(users.stream().map(this::accountPayload).toList());
    }

    @PostMapping("/accounts")
    public ApiResponse<Map<String, Object>> createAccount(@RequestBody AccountRequest request) {
        AuthContext.requireAdmin();
        validateAccountRequest(request, true);
        String username = clean(request.username());
        if (userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, username)) > 0) {
            throw BusinessException.conflict("账号已存在");
        }
        String password = clean(request.initialPassword());
        if (password == null) {
            password = DEFAULT_TEACHER_PASSWORD;
        }
        passwordService.requireStrong(password);
        TUser user = new TUser();
        user.setUsername(username);
        applyAccountFields(user, request);
        user.setPassword(passwordService.encode(password));
        user.setNeedPasswordChange(true);
        user.setLoginFailCount(0);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return ApiResponse.ok(accountPayload(user));
    }

    @PutMapping("/accounts/{id}")
    public ApiResponse<Map<String, Object>> updateAccount(@PathVariable Long id, @RequestBody AccountRequest request) {
        AuthContext.requireAdmin();
        validateAccountRequest(request, false);
        TUser user = userMapper.selectById(id);
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }
        String nextUsername = clean(request.username());
        if (nextUsername != null && !nextUsername.equals(user.getUsername())
                && userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, nextUsername)) > 0) {
            throw BusinessException.conflict("账号已存在");
        }
        if (nextUsername != null) {
            user.setUsername(nextUsername);
        }
        applyAccountFields(user, request);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResponse.ok(accountPayload(user));
    }

    @PostMapping("/accounts/{id}/reset-password")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                          @RequestParam(defaultValue = DEFAULT_TEACHER_PASSWORD) String newPassword) {
        AuthContext.requireAdmin();
        TUser user = userMapper.selectById(id);
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }
        passwordService.requireStrong(newPassword);
        user.setPassword(passwordService.encode(newPassword));
        user.setNeedPasswordChange(true);
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResponse.ok(Map.of("id", id, "reset", true));
    }

    @PostMapping("/accounts/import")
    public ApiResponse<Map<String, Object>> importAccounts(@RequestParam MultipartFile file,
                                                           @RequestParam(defaultValue = DEFAULT_TEACHER_PASSWORD) String initialPassword) {
        AuthContext.requireAdmin();
        passwordService.requireStrong(initialPassword);
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
                String employeeNo = formatter.formatCellValue(row.getCell(0)).trim();
                String realName = formatter.formatCellValue(row.getCell(1)).trim();
                String college = formatter.formatCellValue(row.getCell(2)).trim();
                String teachingCourse = formatter.formatCellValue(row.getCell(3)).trim();
                String teachingClass = formatter.formatCellValue(row.getCell(4)).trim();
                String adminFlag = formatter.formatCellValue(row.getCell(5)).trim();
                String rowPassword = formatter.formatCellValue(row.getCell(6)).trim();
                if (employeeNo.isBlank()) {
                    failedRows.add(importResult(rowNumber, "", "failed", "教职工号不能为空"));
                    continue;
                }
                if (realName.isBlank()) {
                    failedRows.add(importResult(rowNumber, employeeNo, "failed", "姓名不能为空"));
                    continue;
                }
                if (userMapper.selectCount(new LambdaQueryWrapper<TUser>().eq(TUser::getUsername, employeeNo)) > 0) {
                    skippedRows.add(importResult(rowNumber, employeeNo, "skipped", "账号已存在"));
                    continue;
                }
                String password = rowPassword.isBlank() ? initialPassword : rowPassword;
                passwordService.requireStrong(password);
                TUser user = new TUser();
                user.setUsername(employeeNo);
                user.setEmployeeNo(employeeNo);
                user.setRealName(realName);
                user.setCollege(college);
                user.setTeachingCourse(teachingCourse);
                user.setTeachingClass(teachingClass);
                user.setClassName(teachingClass);
                user.setRole(isTruthy(adminFlag) ? "admin" : "teacher");
                user.setPassword(passwordService.encode(password));
                user.setNeedPasswordChange(true);
                user.setLoginFailCount(0);
                user.setTokenVersion(0);
                user.setCreatedAt(LocalDateTime.now());
                user.setUpdatedAt(LocalDateTime.now());
                userMapper.insert(user);
                successRows.add(importResult(rowNumber, employeeNo, "success", "导入成功"));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.badRequest("教师 Excel 导入失败: " + ex.getMessage());
        }
        return ApiResponse.ok(Map.of(
                "totalRows", successRows.size() + skippedRows.size() + failedRows.size(),
                "imported", successRows.size(),
                "success", successRows,
                "skipped", skippedRows,
                "failed", failedRows
        ));
    }

    @GetMapping("/accounts/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        AuthContext.requireAdmin();
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("teachers");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("教职工号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("学院");
            header.createCell(3).setCellValue("教授课程");
            header.createCell(4).setCellValue("教授班级");
            header.createCell(5).setCellValue("是否管理员");
            header.createCell(6).setCellValue("初始密码");
            var example = sheet.createRow(1);
            example.createCell(0).setCellValue("t2024001");
            example.createCell(1).setCellValue("王老师");
            example.createCell(2).setCellValue("软件学院");
            example.createCell(3).setCellValue("Java 程序设计");
            example.createCell(4).setCellValue("软件工程2401");
            example.createCell(5).setCellValue("否");
            example.createCell(6).setCellValue(DEFAULT_TEACHER_PASSWORD);
            for (int i = 0; i <= 6; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("教师管理员导入模板.xlsx", StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(out.toByteArray());
        } catch (Exception ex) {
            throw new BusinessException(500, "生成教师导入模板失败: " + ex.getMessage());
        }
    }

    private void validateAccountRequest(AccountRequest request, boolean requirePassword) {
        if (request == null || clean(request.username()) == null) {
            throw BusinessException.badRequest("账号/教职工号不能为空");
        }
        if (clean(request.realName()) == null) {
            throw BusinessException.badRequest("姓名不能为空");
        }
        String role = clean(request.role());
        if (!List.of("teacher", "admin").contains(role)) {
            throw BusinessException.badRequest("角色仅支持 teacher/admin");
        }
        if (requirePassword && clean(request.initialPassword()) != null) {
            passwordService.requireStrong(request.initialPassword());
        }
    }

    private void applyAccountFields(TUser user, AccountRequest request) {
        String username = clean(request.username());
        user.setRole(clean(request.role()));
        user.setRealName(clean(request.realName()));
        user.setEmail(clean(request.email()));
        user.setPhone(clean(request.phone()));
        user.setEmployeeNo(clean(request.employeeNo()) == null ? username : clean(request.employeeNo()));
        user.setCollege(clean(request.college()));
        user.setTeachingCourse(clean(request.teachingCourse()));
        user.setTeachingClass(clean(request.teachingClass()));
        user.setClassName(clean(request.teachingClass()));
    }

    private Map<String, Object> accountPayload(TUser user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", user.getId());
        payload.put("username", user.getUsername());
        payload.put("role", user.getRole());
        payload.put("realName", user.getRealName() == null ? "" : user.getRealName());
        payload.put("email", user.getEmail() == null ? "" : user.getEmail());
        payload.put("phone", user.getPhone() == null ? "" : user.getPhone());
        payload.put("employeeNo", user.getEmployeeNo() == null ? "" : user.getEmployeeNo());
        payload.put("college", user.getCollege() == null ? "" : user.getCollege());
        payload.put("teachingCourse", user.getTeachingCourse() == null ? "" : user.getTeachingCourse());
        payload.put("teachingClass", user.getTeachingClass() == null ? "" : user.getTeachingClass());
        payload.put("className", user.getClassName() == null ? "" : user.getClassName());
        payload.put("needPasswordChange", Boolean.TRUE.equals(user.getNeedPasswordChange()));
        payload.put("locked", user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now()));
        return payload;
    }

    private Map<String, Object> importResult(int rowNumber, String username, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("row", rowNumber);
        result.put("username", username);
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    private boolean isTruthy(String value) {
        return "是".equals(value) || "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    public record ConfigUpdateRequest(Map<String, String> values) {
    }

    public record AccountRequest(String username,
                                 String role,
                                 String realName,
                                 String email,
                                 String phone,
                                 String employeeNo,
                                 String college,
                                 String teachingCourse,
                                 String teachingClass,
                                 String initialPassword) {
    }
}
