package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TGradePublish;
import com.rainexis.backend.entity.TProjectStructure;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.AssignmentDownloadService;
import com.rainexis.backend.service.business.FileStorageService;
import com.rainexis.backend.service.business.SubmissionCleanupService;
import com.rainexis.backend.service.business.ZipStructureService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 提交管理 API 控制器
 * 学生：上传ZIP代码文件、查看自己的提交历史、下载提交文件
 * 教师：查看作业下所有学生的提交、下载学生提交文件
 *
 * 上传流程：保存ZIP → 解压分析代码结构 → 存入 t_project_structure → 等待教师发起AI评分
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionApiController {
    private final TSubmissionMapper submissionMapper;
    private final TAssignmentMapper assignmentMapper;
    private final TAiTaskMapper taskMapper;
    private final TGradePublishMapper publishMapper;
    private final TProjectStructureMapper structureMapper;
    private final TUserMapper userMapper;
    private final AccessControlService accessControlService;
    private final AssignmentDownloadService assignmentDownloadService;
    private final FileStorageService fileStorageService;
    private final SubmissionCleanupService submissionCleanupService;
    private final ZipStructureService zipStructureService;

    public SubmissionApiController(TSubmissionMapper submissionMapper,
                                   TAssignmentMapper assignmentMapper,
                                   TAiTaskMapper taskMapper,
                                   TGradePublishMapper publishMapper,
                                   TProjectStructureMapper structureMapper,
                                   TUserMapper userMapper,
                                   AccessControlService accessControlService,
                                   AssignmentDownloadService assignmentDownloadService,
                                   FileStorageService fileStorageService,
                                   SubmissionCleanupService submissionCleanupService,
                                   ZipStructureService zipStructureService) {
        this.submissionMapper = submissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.publishMapper = publishMapper;
        this.structureMapper = structureMapper;
        this.userMapper = userMapper;
        this.accessControlService = accessControlService;
        this.assignmentDownloadService = assignmentDownloadService;
        this.fileStorageService = fileStorageService;
        this.submissionCleanupService = submissionCleanupService;
        this.zipStructureService = zipStructureService;
    }

    /** 学生上传代码ZIP文件 */
    @PostMapping
    public ApiResponse<Map<String, Object>> upload(@RequestParam Long assignmentId, @RequestParam MultipartFile file) {
        AuthContext.requireStudent();
        TAssignment assignment = accessControlService.requireStudentCanViewAssignment(assignmentId);
        LocalDateTime uploadTime = LocalDateTime.now();
        boolean late = assignment.getEndTime() != null && uploadTime.isAfter(assignment.getEndTime());
        if (late && "forbid".equals(latePolicy(assignment))) {
            throw BusinessException.conflict("已超过截止时间，不能提交");
        }
        TUser student = userMapper.selectById(AuthContext.get().id());
        TSubmission latest = submissionMapper.selectOne(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getStudentId, student.getId())
                .orderByDesc(TSubmission::getSubmissionVersion)
                .orderByDesc(TSubmission::getUploadTime)
                .last("limit 1"));
        ensureSubmissionStillOpen(latest);
        TSubmission archived = new TSubmission();
        archived.setCurrent(false);
        submissionMapper.update(archived, new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getStudentId, student.getId())
                .eq(TSubmission::getCurrent, true));
        FileStorageService.StoredFile stored = fileStorageService.storeSubmissionZip(file, student.getId(), student.getUsername(), student.getRealName());
        TSubmission submission = new TSubmission();
        submission.setAssignmentId(assignmentId);
        submission.setStudentId(student.getId());
        submission.setFileUrl(stored.path().toString());
        submission.setFileName(stored.originalName());
        submission.setSubmissionVersion(latest == null || latest.getSubmissionVersion() == null ? 1 : latest.getSubmissionVersion() + 1);
        submission.setCurrent(true);
        submission.setLanguage(assignment.getLanguage());
        submission.setUploadTime(uploadTime);
        submission.setLate(late);
        submission.setStatus("uploaded");
        submissionMapper.insert(submission);
        try {
            ZipStructureService.StructureResult result = zipStructureService.analyze(stored.path(), assignment.getLanguage());
            TProjectStructure structure = new TProjectStructure();
            structure.setSubmissionId(submission.getId());
            structure.setStructureJson(result.structureJson());
            structure.setLanguage(assignment.getLanguage());
            structure.setFileCount(result.fileCount());
            structure.setCreatedAt(LocalDateTime.now());
            structureMapper.insert(structure);
            submission.setProjectStructureId(structure.getId());
            submission.setFileCount(result.fileCount());
            submission.setStatus("parsed");
            submissionMapper.updateById(submission);
            return ApiResponse.ok(Map.of("submission", submission, "message", "已提交，等待教师评分"));
        } catch (BusinessException ex) {
            submission.setStatus("parse_failed");
            submissionMapper.updateById(submission);
            throw ex;
        }
    }

    /** 教师将某个学生提交打回重交，并物理删除提交文件、评分任务、日志和报告 */
    @PostMapping("/{submissionId}/return")
    public ApiResponse<Map<String, Object>> returnForResubmission(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        TSubmission submission = accessControlService.requireTeacherSubmissionAccess(submissionId);
        if ("running".equals(latestTaskStatus(submissionId))) {
            throw BusinessException.conflict("AI 任务正在运行中，稍后再打回");
        }
        submissionCleanupService.deleteSubmissionPhysically(submission);
        return ApiResponse.ok(Map.of("returned", true, "submissionId", submissionId));
    }

    /** 教师查看指定作业下所有学生的提交记录 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listByAssignment(@RequestParam(name = "assignment_id") Long assignmentId,
                                                                    @RequestParam(name = "student_no", required = false) String studentNo,
                                                                    @RequestParam(name = "status", required = false) String status) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        LambdaQueryWrapper<TSubmission> query = new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getCurrent, true);
        String cleanedStatus = clean(status);
        if (cleanedStatus != null) {
            query.eq(TSubmission::getStatus, cleanedStatus);
        }
        List<TSubmission> submissions = submissionMapper.selectList(query.orderByDesc(TSubmission::getUploadTime));
        String cleanedStudentNo = clean(studentNo);
        return ApiResponse.ok(submissions.stream()
                .filter(accessControlService::canTeacherAccessSubmission)
                .filter(submission -> matchesStudentNo(submission, cleanedStudentNo))
                .map(this::submissionPayload)
                .toList());
    }

    /** 学生查看自己的所有提交记录 */
    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my() {
        AuthContext.requireStudent();
        List<TSubmission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getStudentId, AuthContext.get().id())
                .orderByDesc(TSubmission::getUploadTime));
        return ApiResponse.ok(submissions.stream().map(this::submissionPayload).toList());
    }

    /** 教师/学生下载提交代码 ZIP */
    @GetMapping("/{submissionId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long submissionId) {
        if ("student".equals(AuthContext.get().role())) {
            accessControlService.requireStudentOwnSubmission(submissionId);
        } else {
            accessControlService.requireTeacherSubmissionAccess(submissionId);
        }
        String filename = assignmentDownloadService.codeFilename(submissionId);
        StreamingResponseBody body = output -> assignmentDownloadService.writeSingleCodeZip(submissionId, output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    /** 教师单独下载某份提交的 PDF 评分报告 */
    @GetMapping("/{submissionId}/download-report")
    public ResponseEntity<StreamingResponseBody> downloadReport(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        String filename = assignmentDownloadService.reportFilename(submissionId);
        StreamingResponseBody body = output -> assignmentDownloadService.writeSingleReport(submissionId, output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    private Map<String, Object> submissionPayload(TSubmission submission) {
        TUser student = userMapper.selectById(submission.getStudentId());
        TGradePublish publish = publishMapper.selectOne(new LambdaQueryWrapper<TGradePublish>()
                .eq(TGradePublish::getSubmissionId, submission.getId())
                .last("limit 1"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", submission.getId());
        payload.put("assignmentId", submission.getAssignmentId());
        payload.put("studentId", submission.getStudentId());
        payload.put("studentUsername", student == null ? "" : student.getUsername());
        payload.put("studentRealName", student == null ? "" : student.getRealName());
        payload.put("fileName", submission.getFileName());
        payload.put("submissionVersion", submission.getSubmissionVersion());
        payload.put("current", Boolean.TRUE.equals(submission.getCurrent()));
        payload.put("uploadTime", submission.getUploadTime());
        payload.put("late", Boolean.TRUE.equals(submission.getLate()));
        payload.put("status", submission.getStatus());
        payload.put("currentScore", submission.getCurrentScore());
        payload.put("currentReportId", submission.getCurrentReportId());
        payload.put("fileCount", submission.getFileCount());
        payload.put("language", submission.getLanguage());
        payload.put("publishId", publish == null ? null : publish.getId());
        payload.put("publishStatus", publish == null ? null : publish.getIsPublished());
        payload.put("publishedAt", publish == null ? null : publish.getPublishedAt());
        payload.put("publishedFinalScore", publish == null ? null : publish.getFinalScore());
        return payload;
    }

    private void ensureSubmissionStillOpen(TSubmission latest) {
        if (latest == null) {
            return;
        }
        if (List.of("scoring", "scored", "reviewed", "published", "failed").contains(latest.getStatus())) {
            throw BusinessException.conflict("作业已进入评分阶段，不能再次提交");
        }
    }

    private String latestTaskStatus(Long submissionId) {
        TAiTask task = taskMapper.selectOne(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getSubmissionId, submissionId)
                .orderByDesc(TAiTask::getCreatedAt)
                .last("limit 1"));
        return task == null ? "" : task.getStatus();
    }

    private String latePolicy(TAssignment assignment) {
        return assignment.getLatePolicy() == null || assignment.getLatePolicy().isBlank()
                ? "forbid"
                : assignment.getLatePolicy();
    }

    private boolean matchesStudentNo(TSubmission submission, String studentNo) {
        if (studentNo == null) {
            return true;
        }
        TUser student = userMapper.selectById(submission.getStudentId());
        return student != null && student.getUsername() != null && student.getUsername().contains(studentNo);
    }

    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
