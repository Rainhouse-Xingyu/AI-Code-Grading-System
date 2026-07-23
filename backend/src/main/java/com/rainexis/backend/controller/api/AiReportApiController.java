package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.rainexis.backend.service.business.RuntimeConfigService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final String localModel;
    private final RuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    public AiReportApiController(TAiReportMapper reportMapper,
                                 TSubmissionMapper submissionMapper,
                                 TAssignmentMapper assignmentMapper,
                                 AccessControlService accessControlService,
                                 @Value("${app.ai.deepseek-token-quota}") long deepSeekTokenQuota,
                                 @Value("${app.ai.local-model}") String localModel,
                                 RuntimeConfigService runtimeConfigService,
                                 ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.submissionMapper = submissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.accessControlService = accessControlService;
        this.deepSeekTokenQuota = deepSeekTokenQuota;
        this.localModel = localModel;
        this.runtimeConfigService = runtimeConfigService;
        this.objectMapper = objectMapper;
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

    @PostMapping("/{submissionId}/manual")
    public ApiResponse<TAiReport> manualReport(@PathVariable Long submissionId, @RequestBody ManualReportRequest request) {
        AuthContext.requireTeacher();
        TSubmission submission = accessControlService.requireTeacherSubmissionAccess(submissionId);
        if ("published".equals(submission.getStatus())) {
            throw BusinessException.conflict("成绩已发布，需撤回后才能手动评分");
        }
        TAssignment assignment = assignmentMapper.selectById(submission.getAssignmentId());
        List<Map<String, Object>> rows = normalizeManualScores(assignment, request == null ? null : request.dimensionScores());
        BigDecimal total = request != null && request.totalScore() != null
                ? request.totalScore()
                : rows.stream()
                .map(row -> decimal(row.get("score")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        total = clamp(total, BigDecimal.ZERO, BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> issues = normalizeIssues(request == null ? null : request.issues());
        TAiReport report = new TAiReport();
        report.setSubmissionId(submissionId);
        report.setTaskId(null);
        report.setModelName("manual");
        report.setTotalScore(total);
        try {
            String scoreJson = objectMapper.writeValueAsString(rows);
            report.setScoreJson(scoreJson);
            report.setScoreDetailJson(scoreJson);
            report.setFileAnalysisJson("[]");
            report.setSuggestion(objectMapper.writeValueAsString(issues));
        } catch (Exception ex) {
            throw new BusinessException(500, "手动评分保存失败: " + ex.getMessage());
        }
        report.setTokenUsage(0);
        report.setReportMarkdown(manualMarkdown(assignment, total, rows, issues, request == null ? null : request.reportMarkdown()));
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
        submission.setStatus("scored");
        submission.setCurrentScore(total);
        submission.setCurrentReportId(report.getId());
        submissionMapper.updateById(submission);
        return ApiResponse.ok(report);
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
        long quota = Math.max(1L, runtimeConfigService.getLong("DEEPSEEK_TOKEN_QUOTA", deepSeekTokenQuota));
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

    private List<Map<String, Object>> normalizeManualScores(TAssignment assignment, List<Map<String, Object>> requestedRows) {
        List<Map<String, Object>> requested = requestedRows == null ? List.of() : requestedRows;
        List<Map<String, Object>> rubricRows = rubricRows(assignment);
        List<Map<String, Object>> source = rubricRows.isEmpty() ? requested : rubricRows;
        if (source.isEmpty()) {
            throw BusinessException.badRequest("手动评分至少需要一个评分点");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            Map<String, Object> base = source.get(i);
            Map<String, Object> requestedRow = i < requestedRowsSize(requested) ? requested.get(i) : Map.of();
            BigDecimal max = decimal(firstNonNull(base.get("max_score"), base.get("maxScore"), base.get("weight"), requestedRow.get("max_score"), 100));
            if (max.compareTo(BigDecimal.ZERO) <= 0) {
                max = BigDecimal.valueOf(100);
            }
            BigDecimal score = clamp(decimal(firstNonNull(requestedRow.get("score"), base.get("score"), 0)), BigDecimal.ZERO, max)
                    .setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", text(firstNonNull(base.get("name"), requestedRow.get("name"), "评分点")));
            row.put("score", score);
            row.put("max_score", max.setScale(2, RoundingMode.HALF_UP));
            row.put("comment", text(firstNonNull(requestedRow.get("comment"), base.get("comment"), "教师手动评分。")));
            rows.add(row);
        }
        return rows;
    }

    private int requestedRowsSize(List<Map<String, Object>> requested) {
        return requested == null ? 0 : requested.size();
    }

    private List<Map<String, Object>> rubricRows(TAssignment assignment) {
        if (assignment == null || assignment.getNormalizedRubricJson() == null || assignment.getNormalizedRubricJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> rubric = objectMapper.readValue(assignment.getNormalizedRubricJson(), new TypeReference<>() {
            });
            Object dimensions = rubric.get("dimensions");
            if (!(dimensions instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    raw.forEach((key, value) -> row.put(String.valueOf(key), value));
                    rows.add(row);
                }
            }
            return rows;
        } catch (Exception ex) {
            throw new BusinessException(500, "读取评分标准失败: " + ex.getMessage());
        }
    }

    private List<Map<String, Object>> normalizeIssues(List<Map<String, Object>> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of(Map.of(
                    "severity", "suggestion",
                    "file", "project",
                    "line", 1,
                    "description", "教师手动评分，暂无额外问题记录。"
            ));
        }
        return requested.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", text(firstNonNull(item.get("severity"), "suggestion")));
            row.put("file", text(firstNonNull(item.get("file"), "project")));
            row.put("line", firstNonNull(item.get("line"), 1));
            row.put("description", text(firstNonNull(item.get("description"), item.get("message"), "")));
            return row;
        }).toList();
    }

    private String manualMarkdown(TAssignment assignment,
                                  BigDecimal total,
                                  List<Map<String, Object>> rows,
                                  List<Map<String, Object>> issues,
                                  String requestedMarkdown) {
        if (requestedMarkdown != null && !requestedMarkdown.isBlank()) {
            return requestedMarkdown;
        }
        String title = assignment == null || assignment.getCourseName() == null || assignment.getCourseName().isBlank()
                ? "评分报告"
                : assignment.getCourseName().trim() + "评分报告";
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n");
        markdown.append("## 总分: ").append(total).append("/100\n\n");
        markdown.append("## 分项评分\n");
        for (Map<String, Object> row : rows) {
            markdown.append("- ")
                    .append(row.get("name"))
                    .append(": ")
                    .append(row.get("score"))
                    .append("/")
                    .append(row.get("max_score"))
                    .append("，")
                    .append(row.get("comment"))
                    .append("\n");
        }
        markdown.append("\n## 问题列表\n");
        for (Map<String, Object> issue : issues) {
            markdown.append("- [")
                    .append(issue.get("severity"))
                    .append("] ")
                    .append(issue.get("file"))
                    .append(":")
                    .append(issue.get("line"))
                    .append(" ")
                    .append(issue.get("description"))
                    .append("\n");
        }
        return markdown.toString();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isDeepSeekReport(TAiReport report) {
        String modelName = report.getModelName();
        return modelName != null
                && !isLocalModelName(modelName)
                && modelName.toLowerCase(Locale.ROOT).contains("deepseek");
    }

    public record ManualReportRequest(BigDecimal totalScore,
                                      List<Map<String, Object>> dimensionScores,
                                      List<Map<String, Object>> issues,
                                      String reportMarkdown) {
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
        if (isLocalModelName(modelName)) {
            return "本地模型";
        }
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

    private boolean isLocalModelName(String modelName) {
        String normalized = modelName == null ? "" : modelName.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("local/") || normalized.startsWith("local:")) {
            return true;
        }
        // 兼容修复前已经保存、尚未带 local/ 前缀的本地模型报告。
        String configuredLocalModel = runtimeConfigService.get("LOCAL_AI_MODEL", localModel);
        return configuredLocalModel != null
                && !configuredLocalModel.isBlank()
                && normalized.equals(configuredLocalModel.strip().toLowerCase(Locale.ROOT));
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
