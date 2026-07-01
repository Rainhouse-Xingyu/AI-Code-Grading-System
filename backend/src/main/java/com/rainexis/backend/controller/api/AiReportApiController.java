package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.AuthUser;
import com.rainexis.backend.service.business.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 评分报告 API 控制器
 * 教师和学生都可以查看评分报告（各自有权限限制）
 */
@RestController
@RequestMapping("/api/v1/ai-reports")
public class AiReportApiController {
    private final TAiReportMapper reportMapper;
    private final TSubmissionMapper submissionMapper;
    private final TAssignmentMapper assignmentMapper;
    private final AccessControlService accessControlService;
    private final long deepSeekTokenQuota;

    public AiReportApiController(TAiReportMapper reportMapper,
                                 TSubmissionMapper submissionMapper,
                                 TAssignmentMapper assignmentMapper,
                                 AccessControlService accessControlService,
                                 @Value("${app.ai.deepseek-token-quota}") long deepSeekTokenQuota) {
        this.reportMapper = reportMapper;
        this.submissionMapper = submissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.accessControlService = accessControlService;
        this.deepSeekTokenQuota = deepSeekTokenQuota;
    }

    @GetMapping("/{submissionId}")
    public ApiResponse<TAiReport> teacherReport(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        return ApiResponse.ok(latestReport(submissionId));
    }

    @GetMapping("/{submissionId}/history")
    public ApiResponse<List<TAiReport>> teacherReportHistory(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        return ApiResponse.ok(reportMapper.selectList(new LambdaQueryWrapper<TAiReport>()
                .eq(TAiReport::getSubmissionId, submissionId)
                .orderByDesc(TAiReport::getCreatedAt)));
    }

    @GetMapping("/my/{submissionId}")
    public ApiResponse<TAiReport> myReport(@PathVariable Long submissionId) {
        AuthContext.requireStudent();
        accessControlService.requireStudentOwnSubmission(submissionId);
        return ApiResponse.ok(latestReport(submissionId));
    }

    @GetMapping("/token-quota")
    public ApiResponse<Map<String, Object>> tokenQuota() {
        AuthContext.requireTeacher();
        long used = reportMapper.selectList(new LambdaQueryWrapper<TAiReport>()
                        .isNotNull(TAiReport::getTokenUsage))
                .stream()
                .filter(this::isDeepSeekReport)
                .mapToLong(report -> report.getTokenUsage() == null ? 0L : report.getTokenUsage())
                .sum();
        long quota = Math.max(1L, deepSeekTokenQuota);
        long remaining = Math.max(0L, quota - used);
        BigDecimal percent = BigDecimal.valueOf(used)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(quota), 2, RoundingMode.HALF_UP);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "DeepSeek");
        payload.put("quotaTokens", quota);
        payload.put("usedTokens", used);
        payload.put("remainingTokens", remaining);
        payload.put("usagePercent", percent);
        payload.put("warningLevel", warningLevel(percent));
        payload.put("quotaExceeded", used >= quota);
        return ApiResponse.ok(payload);
    }

    @GetMapping("/token-stats")
    public ApiResponse<Map<String, Object>> tokenStats() {
        AuthContext.requireTeacher();
        AuthUser current = AuthContext.get();
        Map<String, Map<String, Object>> byModel = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> byAssignment = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byProvider = new LinkedHashMap<>();
        long totalTokens = 0;
        long totalReports = 0;

        List<TAiReport> reports = reportMapper.selectList(new LambdaQueryWrapper<TAiReport>()
                .isNotNull(TAiReport::getTokenUsage)
                .orderByDesc(TAiReport::getCreatedAt));
        for (TAiReport report : reports) {
            TSubmission submission = submissionMapper.selectById(report.getSubmissionId());
            if (submission == null) {
                continue;
            }
            TAssignment assignment = assignmentMapper.selectById(submission.getAssignmentId());
            if (!accessControlService.canReadAssignmentStats(current, assignment)
                    || !accessControlService.canTeacherAccessSubmission(submission)) {
                continue;
            }
            long tokens = report.getTokenUsage() == null ? 0L : report.getTokenUsage();
            String modelName = blankToUnknown(report.getModelName());
            String provider = providerOf(modelName);
            totalTokens += tokens;
            totalReports++;
            increment(byModel, modelName, tokens, "modelName", modelName);
            increment(byProvider, provider, tokens, "provider", provider);
            Map<String, Object> assignmentMeta = new LinkedHashMap<>();
            assignmentMeta.put("assignmentId", assignment.getId());
            assignmentMeta.put("title", assignment.getTitle());
            assignmentMeta.put("className", assignment.getClassName());
            increment(byAssignment, assignment.getId(), tokens, assignmentMeta);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalTokens", totalTokens);
        payload.put("reportCount", totalReports);
        payload.put("byModel", byModel.values());
        payload.put("byProvider", byProvider.values());
        payload.put("byAssignment", byAssignment.values());
        return ApiResponse.ok(payload);
    }

    private TAiReport latestReport(Long submissionId) {
        TAiReport report = reportMapper.selectOne(new LambdaQueryWrapper<TAiReport>()
                .eq(TAiReport::getSubmissionId, submissionId)
                .orderByDesc(TAiReport::getCreatedAt)
                .last("limit 1"));
        if (report == null) {
            throw BusinessException.notFound("AI 报告不存在");
        }
        return report;
    }

    private boolean isDeepSeekReport(TAiReport report) {
        String modelName = report.getModelName();
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("deepseek");
    }

    private String warningLevel(BigDecimal percent) {
        if (percent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return "exceeded";
        }
        if (percent.compareTo(BigDecimal.valueOf(90)) >= 0) {
            return "critical";
        }
        if (percent.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "warning";
        }
        return "normal";
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String providerOf(String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        if (normalized.contains("deepseek")) {
            return "DeepSeek";
        }
        if (normalized.contains("fallback")) {
            return "规则兜底";
        }
        if (normalized.contains("qwen") || normalized.contains("local")) {
            return "本地模型";
        }
        return "其他模型";
    }

    private void increment(Map<String, Map<String, Object>> target,
                           String key,
                           long tokens,
                           String labelKey,
                           String labelValue) {
        Map<String, Object> row = target.computeIfAbsent(key, ignored -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(labelKey, labelValue);
            item.put("reportCount", 0L);
            item.put("tokenUsage", 0L);
            return item;
        });
        row.put("reportCount", ((Number) row.get("reportCount")).longValue() + 1);
        row.put("tokenUsage", ((Number) row.get("tokenUsage")).longValue() + tokens);
    }

    private void increment(Map<Long, Map<String, Object>> target,
                           Long key,
                           long tokens,
                           Map<String, Object> metadata) {
        Map<String, Object> row = target.computeIfAbsent(key, ignored -> {
            Map<String, Object> item = new LinkedHashMap<>(metadata);
            item.put("reportCount", 0L);
            item.put("tokenUsage", 0L);
            return item;
        });
        row.put("reportCount", ((Number) row.get("reportCount")).longValue() + 1);
        row.put("tokenUsage", ((Number) row.get("tokenUsage")).longValue() + tokens);
    }
}
