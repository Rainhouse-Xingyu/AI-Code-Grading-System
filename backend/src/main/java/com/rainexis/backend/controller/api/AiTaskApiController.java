package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiLog;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAiLogMapper;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.AiScoringService;
import com.rainexis.backend.service.business.AiTaskDispatcherService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 评分任务 API 控制器
 * 教师可以发起批量评分、查看任务列表、重试失败任务和查看评分日志
 */
@RestController
@RequestMapping("/api/v1/ai-tasks")
public class AiTaskApiController {
    private final AiScoringService aiScoringService;
    private final TAiTaskMapper taskMapper;
    private final TAiLogMapper logMapper;
    private final TSubmissionMapper submissionMapper;
    private final TUserMapper userMapper;
    private final AccessControlService accessControlService;
    private final AiTaskDispatcherService dispatcherService;

    public AiTaskApiController(AiScoringService aiScoringService,
                               TAiTaskMapper taskMapper,
                               TAiLogMapper logMapper,
                               TSubmissionMapper submissionMapper,
                               TUserMapper userMapper,
                               AccessControlService accessControlService,
                               AiTaskDispatcherService dispatcherService) {
        this.aiScoringService = aiScoringService;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.submissionMapper = submissionMapper;
        this.userMapper = userMapper;
        this.accessControlService = accessControlService;
        this.dispatcherService = dispatcherService;
    }

    /** 批量发起AI评分任务（单次最多200个，需CSRF Token） */
    @PostMapping("/batch-score")
    public ApiResponse<List<TAiTask>> batchScore(@RequestBody BatchScoreRequest request) {
        AuthContext.requireTeacher();
        if (request == null || request.assignmentId() == null || request.submissionIds() == null || request.submissionIds().isEmpty()) {
            throw BusinessException.badRequest("请选择待评分提交");
        }
        if (request.submissionIds().size() > 200) {
            throw BusinessException.badRequest("单次最多发起 200 个评分任务");
        }
        accessControlService.requireAssignmentAccess(request.assignmentId());
        request.submissionIds().forEach(accessControlService::requireTeacherSubmissionAccess);
        return ApiResponse.ok(aiScoringService.createBatchTasks(request.assignmentId(), request.submissionIds(), Boolean.TRUE.equals(request.jointReview())));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(name = "assignment_id") Long assignmentId) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        List<TAiTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getAssignmentId, assignmentId)
                .orderByDesc(TAiTask::getCreatedAt))
                .stream()
                .filter(task -> task.getSubmissionId() == null || accessControlService.canTeacherAccessSubmission(task.getSubmissionId()))
                .toList();
        return ApiResponse.ok(taskPayloads(tasks));
    }

    @GetMapping("/progress")
    public ApiResponse<Map<String, Object>> progress(@RequestParam(name = "assignment_id") Long assignmentId) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        List<TAiTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                        .eq(TAiTask::getAssignmentId, assignmentId)
                        .orderByDesc(TAiTask::getCreatedAt))
                .stream()
                .filter(task -> task.getSubmissionId() == null || accessControlService.canTeacherAccessSubmission(task.getSubmissionId()))
                .toList();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (String status : List.of("pending", "running", "success", "failed", "cancelled")) {
            statusCounts.put(status, tasks.stream().filter(task -> status.equals(task.getStatus())).count());
        }
        String latestBatchId = tasks.stream()
                .map(TAiTask::getBatchId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        List<TAiTask> latestBatchTasks = latestBatchId.isBlank()
                ? List.of()
                : tasks.stream().filter(task -> latestBatchId.equals(task.getBatchId())).toList();
        Map<String, Long> latestBatchCounts = new LinkedHashMap<>();
        for (String status : List.of("pending", "running", "success", "failed", "cancelled")) {
            latestBatchCounts.put(status, latestBatchTasks.stream().filter(task -> status.equals(task.getStatus())).count());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assignmentId", assignmentId);
        payload.put("total", tasks.size());
        payload.put("statusCounts", statusCounts);
        payload.put("activeTasks", dispatcherService.activeTasks());
        payload.put("maxConcurrentTasks", dispatcherService.maxConcurrentTasks());
        payload.put("latestBatchId", latestBatchId);
        payload.put("latestBatchTotal", latestBatchTasks.size());
        payload.put("latestBatchCounts", latestBatchCounts);
        return ApiResponse.ok(payload);
    }

    /** 结束当前作业最近一批仍在等待或执行中的 AI 评分任务 */
    @PostMapping("/cancel-current")
    public ApiResponse<List<TAiTask>> cancelCurrent(@RequestParam(name = "assignment_id") Long assignmentId) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        return ApiResponse.ok(aiScoringService.cancelCurrentBatch(assignmentId));
    }

    /** 重试失败的AI评分任务 */
    @PostMapping("/{taskId}/retry")
    public ApiResponse<TAiTask> retry(@PathVariable Long taskId) {
        AuthContext.requireTeacher();
        TAiTask task = taskMapper.selectById(taskId);
        if (task != null) {
            requireTaskAccess(task);
        }
        return ApiResponse.ok(aiScoringService.retryTask(taskId));
    }

    /** 查看某个任务的评分日志 */
    @GetMapping("/{taskId}/logs")
    public ApiResponse<List<TAiLog>> logs(@PathVariable Long taskId) {
        AuthContext.requireTeacher();
        TAiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw BusinessException.notFound("AI 任务不存在");
        }
        requireTaskAccess(task);
        return ApiResponse.ok(logMapper.selectList(new LambdaQueryWrapper<TAiLog>()
                .eq(TAiLog::getTaskId, taskId)
                .orderByAsc(TAiLog::getCreatedAt)));
    }

    private void requireTaskAccess(TAiTask task) {
        if (task.getSubmissionId() != null) {
            accessControlService.requireTeacherSubmissionAccess(task.getSubmissionId());
            return;
        }
        accessControlService.requireAssignmentAccess(task.getAssignmentId());
    }

    /** 为任务列表补充提交学生与文件名，便于按批次追踪每位学生的评分进度。 */
    private List<Map<String, Object>> taskPayloads(List<TAiTask> tasks) {
        Set<Long> submissionIds = tasks.stream()
                .map(TAiTask::getSubmissionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, TSubmission> submissions = submissionIds.isEmpty() ? Map.of() : submissionMapper.selectBatchIds(submissionIds)
                .stream()
                .collect(Collectors.toMap(TSubmission::getId, Function.identity()));
        Set<Long> studentIds = submissions.values().stream()
                .map(TSubmission::getStudentId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, TUser> students = studentIds.isEmpty() ? Map.of() : userMapper.selectBatchIds(studentIds)
                .stream()
                .collect(Collectors.toMap(TUser::getId, Function.identity()));
        return tasks.stream().map(task -> taskPayload(task, submissions.get(task.getSubmissionId()), students)).toList();
    }

    private Map<String, Object> taskPayload(TAiTask task, TSubmission submission, Map<Long, TUser> students) {
        TUser student = submission == null ? null : students.get(submission.getStudentId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", task.getId());
        payload.put("submissionId", task.getSubmissionId());
        payload.put("assignmentId", task.getAssignmentId());
        payload.put("batchId", task.getBatchId());
        payload.put("modelName", task.getModelName());
        payload.put("status", task.getStatus());
        payload.put("promptTokens", task.getPromptTokens());
        payload.put("completionTokens", task.getCompletionTokens());
        payload.put("totalTokens", task.getTotalTokens());
        payload.put("errorMessage", task.getErrorMessage());
        payload.put("retryCount", task.getRetryCount());
        payload.put("startTime", task.getStartTime());
        payload.put("endTime", task.getEndTime());
        payload.put("createdAt", task.getCreatedAt());
        payload.put("studentId", submission == null ? null : submission.getStudentId());
        payload.put("studentUsername", student == null ? "" : student.getUsername());
        payload.put("studentRealName", student == null ? "" : student.getRealName());
        payload.put("submissionFileName", submission == null ? "" : submission.getFileName());
        return payload;
    }

    public record BatchScoreRequest(Long assignmentId, List<Long> submissionIds, Boolean jointReview) {
    }
}
