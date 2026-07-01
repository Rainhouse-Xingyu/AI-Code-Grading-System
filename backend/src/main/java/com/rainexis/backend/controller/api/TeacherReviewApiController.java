package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TGradePublish;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TTeacherReview;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师复核 API 控制器
 * 教师在 AI 评分的基础上进行人工复核：修改分数、评语、评分报告
 * 复核完成后才能发布成绩
 */
@RestController
@RequestMapping("/api/v1/teacher-reviews")
public class TeacherReviewApiController {
    private final TTeacherReviewMapper reviewMapper;
    private final TAiReportMapper reportMapper;
    private final TSubmissionMapper submissionMapper;
    private final TGradePublishMapper publishMapper;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    public TeacherReviewApiController(TTeacherReviewMapper reviewMapper,
                                      TAiReportMapper reportMapper,
                                      TSubmissionMapper submissionMapper,
                                      TGradePublishMapper publishMapper,
                                      ObjectMapper objectMapper,
                                      AccessControlService accessControlService) {
        this.reviewMapper = reviewMapper;
        this.reportMapper = reportMapper;
        this.submissionMapper = submissionMapper;
        this.publishMapper = publishMapper;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
    }

    /** 保存/更新教师复核结果 */
    @PutMapping("/{submissionId}")
    public ApiResponse<TTeacherReview> save(@PathVariable Long submissionId, @RequestBody ReviewRequest request) {
        AuthContext.requireTeacher();
        TSubmission submission = accessControlService.requireTeacherSubmissionAccess(submissionId);
        if (isPublished(submissionId)) {
            throw BusinessException.conflict("成绩已发布，需撤回后才能修改复核");
        }
        TAiReport report = latestReport(submissionId);
        TTeacherReview previous = latestReview(submissionId);
        TTeacherReview review = new TTeacherReview();
        review.setSubmissionId(submissionId);
        review.setAiReportId(report.getId());
        review.setTeacherId(AuthContext.get().id());
        review.setCreatedAt(LocalDateTime.now());
        BigDecimal finalScore = request.finalScore() == null ? scoreFromModifiedJson(request.modifiedJson(), report.getTotalScore()) : request.finalScore();
        review.setFinalScore(finalScore);
        review.setFinalComment(request.finalComment());
        review.setModifiedJson(request.modifiedJson());
        review.setModifiedMarkdown(finalMarkdown(
                request.modifiedMarkdown(),
                previous == null ? null : previous.getModifiedMarkdown(),
                report.getReportMarkdown()
        ));
        reviewMapper.insert(review);
        submission.setStatus("reviewed");
        submission.setCurrentScore(finalScore);
        submissionMapper.updateById(submission);
        return ApiResponse.ok(review);
    }

    /** 获取教师对某提交的最新复核记录 */
    @GetMapping("/{submissionId}")
    public ApiResponse<TTeacherReview> get(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        TTeacherReview review = latestReview(submissionId);
        if (review == null) {
            throw BusinessException.notFound("教师批改记录不存在");
        }
        return ApiResponse.ok(review);
    }

    /** 查看某提交的全部教师复核历史，用于追溯谁在何时修改了分数、评语和报告 */
    @GetMapping("/{submissionId}/history")
    public ApiResponse<List<TTeacherReview>> history(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        return ApiResponse.ok(reviewMapper.selectList(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submissionId)
                .orderByDesc(TTeacherReview::getCreatedAt)
                .orderByDesc(TTeacherReview::getId)));
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

    private TTeacherReview latestReview(Long submissionId) {
        return reviewMapper.selectOne(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submissionId)
                .orderByDesc(TTeacherReview::getCreatedAt)
                .orderByDesc(TTeacherReview::getId)
                .last("limit 1"));
    }

    private BigDecimal scoreFromModifiedJson(String modifiedJson, BigDecimal fallback) {
        if (modifiedJson == null || modifiedJson.isBlank()) {
            return fallback;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(modifiedJson, new TypeReference<>() {
            });
            return items.stream()
                    .map(item -> new BigDecimal(item.getOrDefault("score", "0").toString()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private boolean isPublished(Long submissionId) {
        TGradePublish publish = publishMapper.selectOne(new LambdaQueryWrapper<TGradePublish>()
                .eq(TGradePublish::getSubmissionId, submissionId)
                .eq(TGradePublish::getIsPublished, (byte) 1)
                .last("limit 1"));
        return publish != null;
    }

    private String finalMarkdown(String requested, String existing, String fallback) {
        if (requested != null) {
            return requested;
        }
        if (existing != null) {
            return existing;
        }
        return fallback;
    }

    public record ReviewRequest(BigDecimal finalScore, String finalComment, String modifiedJson, String modifiedMarkdown) {
    }
}
