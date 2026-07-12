package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TRubricTemplateItem;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAiLogMapper;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TRubricDimensionItemMapper;
import com.rainexis.backend.mapper.TRubricMapper;
import com.rainexis.backend.mapper.TRubricTemplateItemMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.AuthUser;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.AssignmentClassService;
import com.rainexis.backend.service.business.AssignmentDownloadService;
import com.rainexis.backend.service.business.RubricDimensionItemService;
import com.rainexis.backend.service.business.ScoreSheetExportService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 作业管理 API 控制器
 * 教师：创建/编辑/发布作业、查看提交统计
 * 学生：查看本班已发布的作业列表
 */
@RestController
@RequestMapping("/api/v1/assignments")
public class AssignmentApiController {
    private final TAssignmentMapper assignmentMapper;
    private final TSubmissionMapper submissionMapper;
    private final TUserMapper userMapper;
    private final TProjectStructureMapper structureMapper;
    private final TAiTaskMapper taskMapper;
    private final TAiLogMapper logMapper;
    private final TAiReportMapper reportMapper;
    private final TTeacherReviewMapper reviewMapper;
    private final TGradePublishMapper publishMapper;
    private final TRubricMapper rubricMapper;
    private final TRubricDimensionItemMapper rubricDimensionItemMapper;
    private final TRubricTemplateItemMapper templateItemMapper;
    private final AccessControlService accessControlService;
    private final AssignmentClassService assignmentClassService;
    private final AssignmentDownloadService assignmentDownloadService;
    private final RubricDimensionItemService dimensionItemService;
    private final ScoreSheetExportService scoreSheetExportService;
    private final ObjectMapper objectMapper;

    public AssignmentApiController(TAssignmentMapper assignmentMapper,
                                   TSubmissionMapper submissionMapper,
                                   TUserMapper userMapper,
                                   TProjectStructureMapper structureMapper,
                                   TAiTaskMapper taskMapper,
                                   TAiLogMapper logMapper,
                                   TAiReportMapper reportMapper,
                                   TTeacherReviewMapper reviewMapper,
                                   TGradePublishMapper publishMapper,
                                   TRubricMapper rubricMapper,
                                   TRubricDimensionItemMapper rubricDimensionItemMapper,
                                   TRubricTemplateItemMapper templateItemMapper,
                                   AccessControlService accessControlService,
                                   AssignmentClassService assignmentClassService,
                                   AssignmentDownloadService assignmentDownloadService,
                                   RubricDimensionItemService dimensionItemService,
                                   ScoreSheetExportService scoreSheetExportService,
                                   ObjectMapper objectMapper) {
        this.assignmentMapper = assignmentMapper;
        this.submissionMapper = submissionMapper;
        this.userMapper = userMapper;
        this.structureMapper = structureMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.reportMapper = reportMapper;
        this.reviewMapper = reviewMapper;
        this.publishMapper = publishMapper;
        this.rubricMapper = rubricMapper;
        this.rubricDimensionItemMapper = rubricDimensionItemMapper;
        this.templateItemMapper = templateItemMapper;
        this.accessControlService = accessControlService;
        this.assignmentClassService = assignmentClassService;
        this.assignmentDownloadService = assignmentDownloadService;
        this.dimensionItemService = dimensionItemService;
        this.scoreSheetExportService = scoreSheetExportService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<TAssignment>> list(@RequestParam(name = "class_id", required = false) String classId) {
        AuthUser current = AuthContext.get();
        if (current.isStudent()) {
            List<TAssignment> assignments = assignmentMapper.selectList(new LambdaQueryWrapper<TAssignment>()
                    .eq(TAssignment::getStatus, "published")
                    .orderByDesc(TAssignment::getCreatedAt));
            return ApiResponse.ok(assignmentClassService.attachClassNames(assignments.stream()
                    .filter(assignment -> assignmentClassService.includesClass(assignment, current.className()))
                    .filter(this::studentCanSeeAssignment)
                    .toList()));
        }
        List<TAssignment> assignments = assignmentMapper.selectList(new LambdaQueryWrapper<TAssignment>()
                .orderByDesc(TAssignment::getCreatedAt));
        if (!"admin".equals(current.role())) {
            assignments = assignments.stream()
                    .filter(assignment -> "published".equals(assignment.getStatus()))
                    .filter(assignment -> assignmentClassService.includesClass(assignment, current.className()))
                    .toList();
        }
        return ApiResponse.ok(assignmentClassService.attachClassNames(assignments));
    }

    /** 创建作业 */
    @PostMapping
    public ApiResponse<TAssignment> create(@RequestBody AssignmentRequest request) {
        AuthContext.requireAdmin();
        if (request.title() == null || request.title().isBlank()) {
            throw BusinessException.badRequest("作业标题不能为空");
        }
        List<String> classNames = requestClassNames(request);
        if (classNames.isEmpty()) {
            throw BusinessException.badRequest("请选择至少一个发布班级");
        }
        TAssignment assignment = new TAssignment();
        assignment.setTitle(request.title());
        assignment.setCourseName(cleanText(request.courseName()));
        assignment.setDescription(request.description());
        assignment.setLanguage(request.language());
        assignment.setClassName(String.join(",", classNames));
        assignment.setTeacherId(AuthContext.get().id());
        assignment.setStartTime(request.startTime());
        assignment.setEndTime(request.endTime());
        assignment.setLatePolicy(latePolicy(request.latePolicy()));
        assignment.setLatePenaltyPercent(latePenaltyPercent(request.latePenaltyPercent()));
        List<TRubricTemplateItem> selectedTemplateItems = applyTemplateSelection(assignment, request);
        if (request.published() && selectedTemplateItems.isEmpty()) {
            throw BusinessException.badRequest("发布作业前请选择管理员发布的评分模板和评分点");
        }
        assignment.setStatus(request.published() ? "published" : "draft");
        assignment.setCreatedAt(LocalDateTime.now());
        assignmentMapper.insert(assignment);
        assignmentClassService.replaceClasses(assignment.getId(), classNames);
        persistTemplateDimensionItems(assignment.getId(), selectedTemplateItems);
        return ApiResponse.ok(assignmentClassService.attachClassNames(assignment));
    }

    @PutMapping("/{id}")
    public ApiResponse<TAssignment> update(@PathVariable Long id, @RequestBody AssignmentRequest request) {
        AuthContext.requireAdmin();
        TAssignment assignment = accessControlService.requireAssignmentOwner(id);
        if ("published".equals(assignment.getStatus())) {
            assignment.setCourseName(cleanText(request == null ? null : request.courseName()));
            assignment.setDescription(request == null ? null : request.description());
            assignmentMapper.updateById(assignment);
            return ApiResponse.ok(assignmentClassService.attachClassNames(assignment));
        }
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw BusinessException.badRequest("作业标题不能为空");
        }
        List<String> classNames = requestClassNames(request);
        if (classNames.isEmpty()) {
            throw BusinessException.badRequest("请选择至少一个发布班级");
        }
        assignment.setTitle(request.title());
        assignment.setCourseName(cleanText(request.courseName()));
        assignment.setDescription(request.description());
        assignment.setLanguage(request.language());
        assignment.setClassName(String.join(",", classNames));
        assignment.setStartTime(request.startTime());
        assignment.setEndTime(request.endTime());
        assignment.setLatePolicy(latePolicy(request.latePolicy()));
        assignment.setLatePenaltyPercent(latePenaltyPercent(request.latePenaltyPercent()));
        List<TRubricTemplateItem> selectedTemplateItems = applyTemplateSelection(assignment, request);
        if (request.published() && selectedTemplateItems.isEmpty()) {
            throw BusinessException.badRequest("发布作业前请选择管理员发布的评分模板和评分点");
        }
        assignment.setStatus(request.published() ? "published" : "draft");
        assignmentMapper.updateById(assignment);
        assignmentClassService.replaceClasses(assignment.getId(), classNames);
        persistTemplateDimensionItems(assignment.getId(), selectedTemplateItems);
        return ApiResponse.ok(assignmentClassService.attachClassNames(assignment));
    }

    /** 发布作业（变为学生可见） */
    @PatchMapping("/{id}/publish")
    public ApiResponse<TAssignment> publish(@PathVariable Long id) {
        AuthContext.requireAdmin();
        TAssignment assignment = accessControlService.requireAssignmentOwner(id);
        if (assignment.getNormalizedRubricJson() == null || assignment.getNormalizedRubricJson().isBlank()) {
            throw BusinessException.badRequest("发布作业前请选择管理员发布的评分模板和评分点");
        }
        if (assignmentClassService.classNames(assignment).isEmpty()) {
            throw BusinessException.badRequest("发布作业前请选择至少一个班级");
        }
        assignment.setStatus("published");
        assignmentMapper.updateById(assignment);
        return ApiResponse.ok(assignmentClassService.attachClassNames(assignment));
    }

    /** 删除作业及其关联数据 */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AuthContext.requireAdmin();
        accessControlService.requireAssignmentOwner(id);
        List<TSubmission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, id));
        List<Long> submissionIds = submissions.stream().map(TSubmission::getId).toList();
        if (!submissionIds.isEmpty()) {
            publishMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TGradePublish>()
                    .in(com.rainexis.backend.entity.TGradePublish::getSubmissionId, submissionIds));
            reviewMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TTeacherReview>()
                    .in(com.rainexis.backend.entity.TTeacherReview::getSubmissionId, submissionIds));
            reportMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TAiReport>()
                    .in(com.rainexis.backend.entity.TAiReport::getSubmissionId, submissionIds));
            structureMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TProjectStructure>()
                    .in(com.rainexis.backend.entity.TProjectStructure::getSubmissionId, submissionIds));
            logMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TAiLog>()
                    .in(com.rainexis.backend.entity.TAiLog::getSubmissionId, submissionIds));
            submissionMapper.delete(new LambdaQueryWrapper<TSubmission>().in(TSubmission::getId, submissionIds));
        }
        taskMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TAiTask>()
                .eq(com.rainexis.backend.entity.TAiTask::getAssignmentId, id));
        rubricDimensionItemMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TRubricDimensionItem>()
                .eq(com.rainexis.backend.entity.TRubricDimensionItem::getAssignmentId, id));
        rubricMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TRubric>()
                .eq(com.rainexis.backend.entity.TRubric::getAssignmentId, id));
        assignmentClassService.replaceClasses(id, List.of());
        assignmentMapper.deleteById(id);
        return ApiResponse.ok(Map.of("deleted", true, "assignmentId", id, "submissionCount", submissions.size()));
    }

    /** 获取作业提交统计（学生总数、已提交、已评分、已复核、已发布等） */
    @GetMapping("/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        List<String> visibleClasses = assignmentClassService.visibleClassNames(assignment, AuthContext.get());
        String className = visibleClasses.isEmpty() ? "" : String.join(",", visibleClasses);
        LambdaQueryWrapper<TUser> studentQuery = new LambdaQueryWrapper<TUser>()
                .eq(TUser::getRole, "student");
        if (!visibleClasses.isEmpty()) {
            studentQuery.in(TUser::getClassName, visibleClasses);
        } else if (!"admin".equals(AuthContext.get().role())) {
            studentQuery.eq(TUser::getId, -1);
        }
        long studentTotal = userMapper.selectCount(studentQuery);
        List<TSubmission> currentSubmissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, id)
                .eq(TSubmission::getCurrent, true))
                .stream()
                .filter(accessControlService::canTeacherAccessSubmission)
                .toList();
        long submitted = currentSubmissions.size();
        long scored = currentSubmissions.stream().filter(submission -> List.of("scored", "reviewed", "published").contains(submission.getStatus())).count();
        long reviewed = currentSubmissions.stream().filter(submission -> List.of("reviewed", "published").contains(submission.getStatus())).count();
        long published = currentSubmissions.stream().filter(submission -> "published".equals(submission.getStatus())).count();
        long parseFailed = currentSubmissions.stream().filter(submission -> "parse_failed".equals(submission.getStatus())).count();
        long unsubmitted = Math.max(0, studentTotal - submitted);
        return ApiResponse.ok(Map.of(
                "assignmentId", id,
                "className", className == null ? "" : className,
                "studentTotal", studentTotal,
                "submitted", submitted,
                "unsubmitted", unsubmitted,
                "scored", scored,
                "reviewed", reviewed,
                "published", published,
                "parseFailed", parseFailed
        ));
    }

    @GetMapping("/{id}/download-all")
    public ResponseEntity<StreamingResponseBody> downloadAll(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        return downloadZip(id, visibleStudentIds(assignment, false));
    }

    @GetMapping("/{id}/download-selected")
    public ResponseEntity<StreamingResponseBody> downloadSelected(@PathVariable Long id, @RequestParam String studentIds) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        List<Long> ids = List.of(studentIds.split(",")).stream()
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
        if (ids.isEmpty()) {
            throw BusinessException.badRequest("请选择学生");
        }
        List<Long> visibleStudentIds = visibleStudentIds(assignment, true);
        if (!visibleStudentIds.containsAll(ids)) {
            throw BusinessException.forbidden("只能下载可访问班级的学生提交");
        }
        return downloadZip(id, ids);
    }

    @GetMapping("/{id}/download-reports")
    public ResponseEntity<StreamingResponseBody> downloadReports(@PathVariable Long id,
                                                                 @RequestParam(required = false) String studentIds) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        List<Long> ids = parseVisibleStudentIds(assignment, studentIds);
        StreamingResponseBody body = output -> assignmentDownloadService.writeReportsZip(id, ids, output);
        return streamZip(assignmentDownloadService.reportsFilename(id), body);
    }

    @GetMapping("/{id}/download-codes")
    public ResponseEntity<StreamingResponseBody> downloadCodes(@PathVariable Long id,
                                                               @RequestParam(required = false) String studentIds) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        List<Long> ids = parseVisibleStudentIds(assignment, studentIds);
        StreamingResponseBody body = output -> assignmentDownloadService.writeCodesZip(id, ids, output);
        return streamZip(assignmentDownloadService.codesFilename(id), body);
    }

    @GetMapping("/{id}/score-sheet")
    public ResponseEntity<byte[]> scoreSheet(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(id);
        ScoreSheetExportService.ExcelFile excel = scoreSheetExportService.exportAssignment(assignment);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(excel.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(excel.bytes());
    }

    private ResponseEntity<StreamingResponseBody> downloadZip(Long assignmentId, List<Long> studentIds) {
        String filename = assignmentDownloadService.filename(assignmentId);
        StreamingResponseBody body = output -> assignmentDownloadService.writeZip(assignmentId, studentIds, output);
        return streamZip(filename, body);
    }

    private ResponseEntity<StreamingResponseBody> streamZip(String filename, StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                .toString())
                .body(body);
    }

    private List<Long> parseVisibleStudentIds(TAssignment assignment, String studentIds) {
        if (studentIds == null || studentIds.isBlank()) {
            return visibleStudentIds(assignment, false);
        }
        List<Long> ids = List.of(studentIds.split(",")).stream()
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
        if (ids.isEmpty()) {
            throw BusinessException.badRequest("请选择学生");
        }
        List<Long> visibleStudentIds = visibleStudentIds(assignment, true);
        if (!visibleStudentIds.containsAll(ids)) {
            throw BusinessException.forbidden("只能下载可访问班级的学生提交");
        }
        return ids;
    }

    private String latePolicy(String value) {
        if (value == null || value.isBlank()) {
            return "forbid";
        }
        if (!List.of("forbid", "allow_mark", "allow_penalty").contains(value)) {
            throw BusinessException.badRequest("迟交策略无效");
        }
        return value;
    }

    private boolean studentCanSeeAssignment(TAssignment assignment) {
        if (assignment.getEndTime() == null || !LocalDateTime.now().isAfter(assignment.getEndTime())) {
            return true;
        }
        return !"forbid".equals(latePolicy(assignment.getLatePolicy()));
    }

    private Integer latePenaltyPercent(Integer value) {
        int percent = value == null ? 0 : value;
        if (percent < 0 || percent > 100) {
            throw BusinessException.badRequest("迟交扣分比例必须在 0-100 之间");
        }
        return percent;
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private List<TRubricTemplateItem> applyTemplateSelection(TAssignment assignment, AssignmentRequest request) {
        if (request.rubricTemplateId() == null && (request.selectedRubricItemIds() == null || request.selectedRubricItemIds().isEmpty())) {
            assignment.setRubricTemplateId(null);
            assignment.setSelectedRubricItemIds(null);
            assignment.setNormalizedRubricJson(null);
            return List.of();
        }
        if (request.rubricTemplateId() == null || request.selectedRubricItemIds() == null || request.selectedRubricItemIds().isEmpty()) {
            throw BusinessException.badRequest("请选择评分模板和至少一个评分点");
        }
        List<TRubricTemplateItem> selected = templateItemMapper.selectList(new LambdaQueryWrapper<TRubricTemplateItem>()
                .eq(TRubricTemplateItem::getTemplateId, request.rubricTemplateId())
                .in(TRubricTemplateItem::getId, request.selectedRubricItemIds())
                .eq(TRubricTemplateItem::getEnabled, (byte) 1)
                .orderByAsc(TRubricTemplateItem::getDimensionOrder)
                .orderByAsc(TRubricTemplateItem::getPointOrder));
        if (selected.size() != request.selectedRubricItemIds().size()) {
            throw BusinessException.badRequest("评分模板中存在无效或停用的评分点");
        }
        try {
            assignment.setRubricTemplateId(request.rubricTemplateId());
            assignment.setSelectedRubricItemIds(objectMapper.writeValueAsString(request.selectedRubricItemIds()));
            assignment.setNormalizedRubricJson(objectMapper.writeValueAsString(normalizedRubric(selected)));
            return selected;
        } catch (Exception ex) {
            throw new BusinessException(500, "评分模板归一化失败: " + ex.getMessage());
        }
    }

    private void persistTemplateDimensionItems(Long assignmentId, List<TRubricTemplateItem> selectedTemplateItems) {
        if (selectedTemplateItems == null || selectedTemplateItems.isEmpty()) {
            dimensionItemService.clearAssignmentTemplateItems(assignmentId);
            return;
        }
        dimensionItemService.replaceAssignmentTemplateItems(assignmentId, selectedTemplateItems);
    }

    private List<String> requestClassNames(AssignmentRequest request) {
        List<String> classNames = assignmentClassService.normalize(request.classNames());
        if (!classNames.isEmpty()) {
            return classNames;
        }
        return assignmentClassService.parseLegacyClassName(request.className());
    }

    private List<Long> visibleStudentIds(TAssignment assignment, boolean requireClassLimit) {
        List<String> visibleClasses = assignmentClassService.visibleClassNames(assignment, AuthContext.get());
        if (visibleClasses.isEmpty() && "admin".equals(AuthContext.get().role()) && !requireClassLimit) {
            return List.of();
        }
        LambdaQueryWrapper<TUser> query = new LambdaQueryWrapper<TUser>()
                .eq(TUser::getRole, "student");
        if (!visibleClasses.isEmpty()) {
            query.in(TUser::getClassName, visibleClasses);
        } else {
            query.eq(TUser::getId, -1);
        }
        return userMapper.selectList(query).stream()
                .map(TUser::getId)
                .toList();
    }

    private Map<String, Object> normalizedRubric(List<TRubricTemplateItem> selected) {
        BigDecimal total = selected.stream()
                .map(TRubricTemplateItem::getPointScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("勾选评分点原始总分必须大于 0");
        }
        List<Map<String, Object>> dimensions = new ArrayList<>();
        BigDecimal normalizedSum = BigDecimal.ZERO;
        for (int i = 0; i < selected.size(); i++) {
            TRubricTemplateItem item = selected.get(i);
            BigDecimal maxScore = i == selected.size() - 1
                    ? BigDecimal.valueOf(100).subtract(normalizedSum)
                    : item.getPointScore().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            normalizedSum = normalizedSum.add(maxScore);
            Map<String, Object> dimension = new LinkedHashMap<>();
            dimension.put("name", item.getDimensionName() + " - " + item.getPointName());
            dimension.put("original_score", item.getPointScore());
            dimension.put("weight", maxScore);
            dimension.put("max_score", maxScore);
            dimension.put("criteria", item.getCriteria());
            dimension.put("items", List.of(Map.of(
                    "name", item.getPointName(),
                    "original_score", item.getPointScore(),
                    "max_score", maxScore,
                    "criteria", item.getCriteria() == null ? "" : item.getCriteria()
            )));
            dimensions.add(dimension);
        }
        return Map.of(
                "rubric_name", "归一化评分标准",
                "total_score", 100,
                "normalization_formula", "归一化满分 = 原始满分 / 勾选项原始满分之和 × 100",
                "dimensions", dimensions
        );
    }

    public record AssignmentRequest(String title, String courseName, String description, String language, String className, List<String> classNames,
                                    LocalDateTime startTime,
                                    LocalDateTime endTime, String latePolicy, Integer latePenaltyPercent, boolean published,
                                    Long rubricTemplateId, List<Long> selectedRubricItemIds) {
    }
}
