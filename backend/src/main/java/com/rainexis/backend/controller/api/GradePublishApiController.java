package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TGradePublish;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TTeacherReview;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成绩发布 API 控制器
 * 教师发布/撤回成绩，学生查看自己的成绩
 * 发布时会计算迟交扣分（allow_penalty 策略下）
 */
@RestController
@RequestMapping("/api/v1/grade-publish")
public class GradePublishApiController {
    private final TGradePublishMapper publishMapper;
    private final TSubmissionMapper submissionMapper;
    private final TAssignmentMapper assignmentMapper;
    private final TTeacherReviewMapper reviewMapper;
    private final TAiReportMapper reportMapper;
    private final AccessControlService accessControlService;

    public GradePublishApiController(TGradePublishMapper publishMapper,
                                     TSubmissionMapper submissionMapper,
                                     TAssignmentMapper assignmentMapper,
                                     TTeacherReviewMapper reviewMapper,
                                     TAiReportMapper reportMapper,
                                     AccessControlService accessControlService) {
        this.publishMapper = publishMapper;
        this.submissionMapper = submissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.reviewMapper = reviewMapper;
        this.reportMapper = reportMapper;
        this.accessControlService = accessControlService;
    }

    /** 批量发布成绩（需先完成教师复核，需 CSRF Token） */
    @PostMapping("/push")
    public ApiResponse<List<TGradePublish>> push(@RequestBody PushRequest request) {
        AuthContext.requireTeacher();
        if (request == null || request.submissionIds() == null || request.submissionIds().isEmpty()) {
            throw BusinessException.badRequest("请选择待推送成绩");
        }
        List<TGradePublish> result = new ArrayList<>();
        for (Long submissionId : request.submissionIds()) {
            TSubmission submission = accessControlService.requireTeacherSubmissionAccess(submissionId);
            TTeacherReview review = latestReview(submissionId);
            if (review == null) {
                throw BusinessException.conflict("请先完成教师复核后再发布成绩");
            }
            result.add(publishSubmission(submission, review));
        }
        return ApiResponse.ok(result);
    }

    /** 按当前作业一键推送所有已复核成绩（教师班级/作业维度，需 CSRF Token） */
    @PostMapping("/push-all")
    public ApiResponse<List<TGradePublish>> pushAll(@RequestBody PushAllRequest request) {
        AuthContext.requireTeacher();
        if (request == null || request.assignmentId() == null) {
            throw BusinessException.badRequest("请选择作业");
        }
        accessControlService.requireAssignmentAccess(request.assignmentId());
        List<TSubmission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, request.assignmentId())
                .eq(TSubmission::getCurrent, true)
                .orderByAsc(TSubmission::getStudentId));
        List<TGradePublish> result = new ArrayList<>();
        for (TSubmission submission : submissions.stream().filter(accessControlService::canTeacherAccessSubmission).toList()) {
            TTeacherReview review = latestReview(submission.getId());
            if (review != null) {
                result.add(publishSubmission(submission, review));
            }
        }
        if (result.isEmpty()) {
            throw BusinessException.conflict("当前作业没有可推送的已复核成绩");
        }
        return ApiResponse.ok(result);
    }

    /** 撤回已发布的成绩 */
    @PostMapping("/retract/{id}")
    public ApiResponse<TGradePublish> retract(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TGradePublish publish = publishMapper.selectById(id);
        if (publish == null) {
            throw BusinessException.notFound("发布记录不存在");
        }
        accessControlService.requireTeacherSubmissionAccess(publish.getSubmissionId());
        publish.setIsPublished((byte) 2);
        publishMapper.updateById(publish);
        TSubmission submission = submissionMapper.selectById(publish.getSubmissionId());
        if (submission != null) {
            submission.setStatus("reviewed");
            submissionMapper.updateById(submission);
        }
        return ApiResponse.ok(publish);
    }

    /** 学生查看自己在某次作业中的已发布成绩 */
    @GetMapping("/my-grade/{assignmentId}")
    public ApiResponse<Map<String, Object>> myGrade(@PathVariable Long assignmentId) {
        AuthContext.requireStudent();
        TGradePublish publish = publishMapper.selectOne(new LambdaQueryWrapper<TGradePublish>()
                .eq(TGradePublish::getAssignmentId, assignmentId)
                .eq(TGradePublish::getStudentId, AuthContext.get().id())
                .eq(TGradePublish::getIsPublished, (byte) 1)
                .last("limit 1"));
        if (publish == null) {
            throw BusinessException.notFound("成绩尚未发布");
        }
        TSubmission submission = submissionMapper.selectById(publish.getSubmissionId());
        TTeacherReview review = latestReview(publish.getSubmissionId());
        TAiReport report = latestReport(publish.getSubmissionId());
        return ApiResponse.ok(Map.of("publish", publish, "submission", submission, "teacherReview", review == null ? "" : review, "aiReport", report));
    }

    private TTeacherReview latestReview(Long submissionId) {
        return reviewMapper.selectOne(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submissionId)
                .orderByDesc(TTeacherReview::getCreatedAt)
                .orderByDesc(TTeacherReview::getId)
                .last("limit 1"));
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

    private TGradePublish publishSubmission(TSubmission submission, TTeacherReview review) {
        latestReport(submission.getId());
        BigDecimal score = finalPublishedScore(review.getFinalScore(), submission);
        TGradePublish publish = publishMapper.selectOne(new LambdaQueryWrapper<TGradePublish>()
                .eq(TGradePublish::getSubmissionId, submission.getId())
                .last("limit 1"));
        if (publish == null) {
            publish = new TGradePublish();
            publish.setSubmissionId(submission.getId());
            publish.setAssignmentId(submission.getAssignmentId());
            publish.setStudentId(submission.getStudentId());
        }
        publish.setFinalScore(score);
        publish.setReportId(review.getId());
        publish.setIsPublished((byte) 1);
        publish.setPublishedAt(LocalDateTime.now());
        if (publish.getId() == null) {
            publishMapper.insert(publish);
        } else {
            publishMapper.updateById(publish);
        }
        submission.setStatus("published");
        submissionMapper.updateById(submission);
        return publish;
    }

    private BigDecimal finalPublishedScore(BigDecimal reviewScore, TSubmission submission) {
        if (reviewScore == null) {
            return BigDecimal.ZERO;
        }
        if (!Boolean.TRUE.equals(submission.getLate())) {
            return reviewScore;
        }
        TAssignment assignment = assignmentMapper.selectById(submission.getAssignmentId());
        if (assignment == null || !"allow_penalty".equals(assignment.getLatePolicy())) {
            return reviewScore;
        }
        int percent = assignment.getLatePenaltyPercent() == null ? 0 : assignment.getLatePenaltyPercent();
        if (percent <= 0) {
            return reviewScore;
        }
        BigDecimal multiplier = BigDecimal.valueOf(100 - Math.min(percent, 100))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return reviewScore.multiply(multiplier).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public record PushRequest(List<Long> submissionIds) {
    }

    public record PushAllRequest(Long assignmentId) {
    }
}
