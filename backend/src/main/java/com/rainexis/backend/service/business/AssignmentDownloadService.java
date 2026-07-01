package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TTeacherReview;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import com.rainexis.backend.mapper.TUserMapper;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

@Service
public class AssignmentDownloadService {
    private final TAssignmentMapper assignmentMapper;
    private final TSubmissionMapper submissionMapper;
    private final TUserMapper userMapper;
    private final TAiReportMapper reportMapper;
    private final TTeacherReviewMapper reviewMapper;

    public AssignmentDownloadService(TAssignmentMapper assignmentMapper,
                                     TSubmissionMapper submissionMapper,
                                     TUserMapper userMapper,
                                     TAiReportMapper reportMapper,
                                     TTeacherReviewMapper reviewMapper) {
        this.assignmentMapper = assignmentMapper;
        this.submissionMapper = submissionMapper;
        this.userMapper = userMapper;
        this.reportMapper = reportMapper;
        this.reviewMapper = reviewMapper;
    }

    public String filename(Long assignmentId) {
        TAssignment assignment = assignmentMapper.selectById(assignmentId);
        String title = assignment == null ? "assignment_" + assignmentId : safePart(assignment.getTitle());
        return title + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
    }

    public String reportsFilename(Long assignmentId) {
        return baseAssignmentFilename(assignmentId) + "_reports_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
    }

    public String codesFilename(Long assignmentId) {
        return baseAssignmentFilename(assignmentId) + "_codes_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".zip";
    }

    public String reportFilename(Long submissionId) {
        TSubmission submission = requireSubmission(submissionId);
        return studentBaseName(userMapper.selectById(submission.getStudentId()), submission) + "-报告.md";
    }

    public String codeFilename(Long submissionId) {
        TSubmission submission = requireSubmission(submissionId);
        return studentBaseName(userMapper.selectById(submission.getStudentId()), submission) + "-代码.zip";
    }

    public void writeZip(Long assignmentId, List<Long> studentIds, OutputStream output) {
        List<TSubmission> submissions = querySubmissions(assignmentId, studentIds);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[8192];
            for (TSubmission submission : submissions) {
                TUser student = userMapper.selectById(submission.getStudentId());
                String baseName = studentBaseName(student, submission);
                writeCombinedReportEntry(zip, baseName, submission);
                writeExpandedCodeEntries(zip, baseName, submission, buffer);
            }
        } catch (Exception ex) {
            throw new BusinessException(500, "打包下载失败: " + ex.getMessage());
        }
    }

    public void writeReportsZip(Long assignmentId, List<Long> studentIds, OutputStream output) {
        List<TSubmission> submissions = querySubmissions(assignmentId, studentIds);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (TSubmission submission : submissions) {
                TUser student = userMapper.selectById(submission.getStudentId());
                writeReportEntry(zip, studentBaseName(student, submission), submission);
            }
        } catch (Exception ex) {
            throw new BusinessException(500, "报告打包下载失败: " + ex.getMessage());
        }
    }

    public void writeCodesZip(Long assignmentId, List<Long> studentIds, OutputStream output) {
        List<TSubmission> submissions = querySubmissions(assignmentId, studentIds);
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[8192];
            for (TSubmission submission : submissions) {
                TUser student = userMapper.selectById(submission.getStudentId());
                writeCodeEntry(zip, studentBaseName(student, submission), submission, buffer);
            }
        } catch (Exception ex) {
            throw new BusinessException(500, "代码打包下载失败: " + ex.getMessage());
        }
    }

    public void writeSingleReport(Long submissionId, OutputStream output) {
        try {
            output.write(reportMarkdown(requireSubmission(submissionId)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new BusinessException(500, "报告下载失败: " + ex.getMessage());
        }
    }

    public Path codePath(Long submissionId) {
        TSubmission submission = requireSubmission(submissionId);
        Path source = Paths.get(submission.getFileUrl()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw BusinessException.notFound("提交文件不存在");
        }
        return source;
    }

    private List<TSubmission> querySubmissions(Long assignmentId, List<Long> studentIds) {
        LambdaQueryWrapper<TSubmission> query = new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignmentId)
                .eq(TSubmission::getCurrent, true)
                .orderByAsc(TSubmission::getStudentId);
        if (studentIds != null && !studentIds.isEmpty()) {
            query.in(TSubmission::getStudentId, studentIds);
        }
        return submissionMapper.selectList(query);
    }

    private void writeReportEntry(ZipOutputStream zip, String baseName, TSubmission submission) throws Exception {
        String markdown = reportMarkdown(submission);
        zip.putNextEntry(new ZipEntry(baseName + "-报告.md"));
        zip.write(markdown.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeCombinedReportEntry(ZipOutputStream zip, String baseName, TSubmission submission) throws Exception {
        String markdown = reportMarkdown(submission);
        zip.putNextEntry(new ZipEntry(baseName + "/评分报告.md"));
        zip.write(markdown.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String reportMarkdown(TSubmission submission) {
        TTeacherReview review = reviewMapper.selectOne(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submission.getId())
                .orderByDesc(TTeacherReview::getCreatedAt)
                .last("limit 1"));
        if (review != null && review.getModifiedMarkdown() != null && !review.getModifiedMarkdown().isBlank()) {
            return review.getModifiedMarkdown();
        }
        TAiReport report = submission.getCurrentReportId() == null ? null : reportMapper.selectById(submission.getCurrentReportId());
        if (report != null && report.getReportMarkdown() != null && !report.getReportMarkdown().isBlank()) {
            return report.getReportMarkdown();
        }
        return "# 评分报告\n\n暂无评分报告。\n";
    }

    private void writeCodeEntry(ZipOutputStream zip, String baseName, TSubmission submission, byte[] buffer) throws Exception {
        Path source = Paths.get(submission.getFileUrl()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(baseName + "-代码.zip"));
        try (var input = Files.newInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private void writeExpandedCodeEntries(ZipOutputStream zip, String baseName, TSubmission submission, byte[] buffer) throws Exception {
        Path source = Paths.get(submission.getFileUrl()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            return;
        }
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = normalizeZipEntry(entry.getName());
                if (entry.isDirectory() || entryName.isBlank()) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(baseName + "/代码/" + entryName));
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private String studentBaseName(TUser student, TSubmission submission) {
        if (student == null) {
            return "student-" + submission.getStudentId();
        }
        return safePart(student.getUsername()) + "-" + safePart(student.getRealName());
    }

    private String safePart(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String normalizeZipEntry(String name) {
        String normalized = Paths.get(name).normalize().toString().replace('\\', '/');
        if (normalized.startsWith("../") || normalized.equals("..") || normalized.startsWith("/")) {
            return "";
        }
        return normalized;
    }

    private TSubmission requireSubmission(Long submissionId) {
        TSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BusinessException.notFound("提交不存在");
        }
        return submission;
    }

    private String baseAssignmentFilename(Long assignmentId) {
        TAssignment assignment = assignmentMapper.selectById(assignmentId);
        return assignment == null ? "assignment_" + assignmentId : safePart(assignment.getTitle());
    }
}
