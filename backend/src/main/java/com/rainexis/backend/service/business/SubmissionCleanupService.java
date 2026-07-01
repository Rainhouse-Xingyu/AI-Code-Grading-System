package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TFile;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAiLogMapper;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TFileMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SubmissionCleanupService {
    private final TSubmissionMapper submissionMapper;
    private final TProjectStructureMapper structureMapper;
    private final TAiTaskMapper taskMapper;
    private final TAiLogMapper logMapper;
    private final TAiReportMapper reportMapper;
    private final TTeacherReviewMapper reviewMapper;
    private final TGradePublishMapper publishMapper;
    private final TFileMapper fileMapper;
    private final Path uploadRoot;

    public SubmissionCleanupService(TSubmissionMapper submissionMapper,
                                    TProjectStructureMapper structureMapper,
                                    TAiTaskMapper taskMapper,
                                    TAiLogMapper logMapper,
                                    TAiReportMapper reportMapper,
                                    TTeacherReviewMapper reviewMapper,
                                    TGradePublishMapper publishMapper,
                                    TFileMapper fileMapper,
                                    @Value("${app.storage.root}") String storageRoot) {
        this.submissionMapper = submissionMapper;
        this.structureMapper = structureMapper;
        this.taskMapper = taskMapper;
        this.logMapper = logMapper;
        this.reportMapper = reportMapper;
        this.reviewMapper = reviewMapper;
        this.publishMapper = publishMapper;
        this.fileMapper = fileMapper;
        this.uploadRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public void deleteSubmissionPhysically(TSubmission submission) {
        if (submission == null || submission.getId() == null) {
            return;
        }
        Long submissionId = submission.getId();
        List<TAiTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getSubmissionId, submissionId));
        List<Long> taskIds = tasks.stream().map(TAiTask::getId).toList();
        if (!taskIds.isEmpty()) {
            logMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TAiLog>()
                    .in(com.rainexis.backend.entity.TAiLog::getTaskId, taskIds));
        }
        publishMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TGradePublish>()
                .eq(com.rainexis.backend.entity.TGradePublish::getSubmissionId, submissionId));
        reviewMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TTeacherReview>()
                .eq(com.rainexis.backend.entity.TTeacherReview::getSubmissionId, submissionId));
        reportMapper.delete(new LambdaQueryWrapper<TAiReport>()
                .eq(TAiReport::getSubmissionId, submissionId));
        structureMapper.delete(new LambdaQueryWrapper<com.rainexis.backend.entity.TProjectStructure>()
                .eq(com.rainexis.backend.entity.TProjectStructure::getSubmissionId, submissionId));
        taskMapper.delete(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getSubmissionId, submissionId));
        deleteStoredFile(submission.getFileUrl());
        submissionMapper.deleteById(submissionId);
    }

    public void deleteStoredFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return;
        }
        Path path = Paths.get(fileUrl).toAbsolutePath().normalize();
        if (!path.startsWith(uploadRoot)) {
            throw BusinessException.badRequest("提交文件路径不在上传目录内，已拒绝删除");
        }
        try {
            Files.deleteIfExists(path);
            fileMapper.delete(new LambdaQueryWrapper<TFile>().eq(TFile::getFileUrl, path.toString()));
            cleanupEmptyParents(path.getParent());
        } catch (Exception ex) {
            throw new BusinessException(500, "提交文件删除失败: " + ex.getMessage());
        }
    }

    private void cleanupEmptyParents(Path start) {
        Path current = start;
        while (current != null && current.startsWith(uploadRoot) && !current.equals(uploadRoot)) {
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
                Files.deleteIfExists(current);
                current = current.getParent();
            } catch (Exception ignored) {
                return;
            }
        }
    }
}
