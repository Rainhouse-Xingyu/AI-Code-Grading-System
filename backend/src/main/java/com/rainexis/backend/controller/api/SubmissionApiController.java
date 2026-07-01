package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TGradePublish;
import com.rainexis.backend.entity.TProjectStructure;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.FileStorageService;
import com.rainexis.backend.service.business.ZipStructureService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
    private final TGradePublishMapper publishMapper;
    private final TProjectStructureMapper structureMapper;
    private final TUserMapper userMapper;
    private final AccessControlService accessControlService;
    private final FileStorageService fileStorageService;
    private final ZipStructureService zipStructureService;

    public SubmissionApiController(TSubmissionMapper submissionMapper,
                                   TAssignmentMapper assignmentMapper,
                                   TGradePublishMapper publishMapper,
                                   TProjectStructureMapper structureMapper,
                                   TUserMapper userMapper,
                                   AccessControlService accessControlService,
                                   FileStorageService fileStorageService,
                                   ZipStructureService zipStructureService) {
        this.submissionMapper = submissionMapper;
        this.assignmentMapper = assignmentMapper;
        this.publishMapper = publishMapper;
        this.structureMapper = structureMapper;
        this.userMapper = userMapper;
        this.accessControlService = accessControlService;
        this.fileStorageService = fileStorageService;
        this.zipStructureService = zipStructureService;
    }

    /** 学生上传代码ZIP文件 */
    @PostMapping
    public ApiResponse<Map<String, Object>> upload(@RequestParam Long assignmentId, @RequestParam MultipartFile file) {
        AuthContext.requireStudent();
        TAssignment assignment = accessControlService.requireStudentCanViewAssignment(assignmentId);
        LocalDateTime uploadTime = LocalDateTime.now();
        boolean late = assignment.getEndTime() != null && uploadTime.isAfter(assignment.getEndTime());
        if (late) {
            throw BusinessException.conflict("已超过截止时间，不能提交");
        }
        TUser student = userMapper.selectById(AuthContext.get().id());
        TSubmission latest = submissionMapper.selectOne(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getStudentId, student.getId())
                .orderByDesc(TSubmission::getSubmissionVersion)
                .orderByDesc(TSubmission::getUploadTime)
                .last("limit 1"));
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

    /** 教师查看指定作业下所有学生的提交记录 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listByAssignment(@RequestParam(name = "assignment_id") Long assignmentId) {
        AuthContext.requireTeacher();
        accessControlService.requireAssignmentAccess(assignmentId);
        List<TSubmission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getCurrent, true)
                .orderByDesc(TSubmission::getUploadTime));
        return ApiResponse.ok(submissions.stream()
                .filter(accessControlService::canTeacherAccessSubmission)
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

    /** 教师/学生下载提交的原始文件 */
    @GetMapping("/{submissionId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long submissionId) {
        TSubmission submission;
        if ("student".equals(AuthContext.get().role())) {
            submission = accessControlService.requireStudentOwnSubmission(submissionId);
        } else {
            submission = accessControlService.requireTeacherSubmissionAccess(submissionId);
        }
        Path path = Paths.get(submission.getFileUrl()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw BusinessException.notFound("提交文件不存在");
        }
        String filename = path.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                .toString())
                .body(new FileSystemResource(path));
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

    private String latePolicy(TAssignment assignment) {
        return assignment.getLatePolicy() == null || assignment.getLatePolicy().isBlank()
                ? "forbid"
                : assignment.getLatePolicy();
    }
}
