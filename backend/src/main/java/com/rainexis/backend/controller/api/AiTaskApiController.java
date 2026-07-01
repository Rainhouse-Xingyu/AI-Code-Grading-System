package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiLog;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.mapper.TAiLogMapper;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.AiScoringService;
import java.util.List;
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
    private final AccessControlService accessControlService;

    public AiTaskApiController(AiScoringService aiScoringService,
                               TAiTaskMapper taskMapper,
                               TAiLogMapper logMapper,
                               AccessControlService accessControlService) {
        this.aiScoringService = aiScoringService;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.accessControlService = accessControlService;
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
    public ApiResponse<List<TAiTask>> list(@RequestParam(name = "assignment_id") Long assignmentId) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        return ApiResponse.ok(taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getAssignmentId, assignmentId)
                .orderByDesc(TAiTask::getCreatedAt))
                .stream()
                .filter(task -> task.getSubmissionId() == null || accessControlService.canTeacherAccessSubmission(task.getSubmissionId()))
                .toList());
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

    public record BatchScoreRequest(Long assignmentId, List<Long> submissionIds, Boolean jointReview) {
    }
}
