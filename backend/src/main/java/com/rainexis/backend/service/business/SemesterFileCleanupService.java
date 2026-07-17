package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TProjectStructure;
import com.rainexis.backend.entity.TRubric;
import com.rainexis.backend.entity.TSemester;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TRubricMapper;
import com.rainexis.backend.mapper.TSemesterMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 清理已归档学期的原始上传文件。
 * 只删除物理文件，保留存储元数据以及学期、作业、提交、代码结构、评分与发布记录。
 */
@Service
public class SemesterFileCleanupService {
    private static final int MAX_PREVIEW_FILES = 200;

    private final TSemesterMapper semesterMapper;
    private final TAssignmentMapper assignmentMapper;
    private final TSubmissionMapper submissionMapper;
    private final TProjectStructureMapper structureMapper;
    private final TRubricMapper rubricMapper;
    private final ObjectMapper objectMapper;
    private final Path uploadRoot;

    public SemesterFileCleanupService(TSemesterMapper semesterMapper,
                                      TAssignmentMapper assignmentMapper,
                                      TSubmissionMapper submissionMapper,
                                      TProjectStructureMapper structureMapper,
                                      TRubricMapper rubricMapper,
                                      ObjectMapper objectMapper,
                                      @Value("${app.storage.root}") String storageRoot) {
        this.semesterMapper = semesterMapper;
        this.assignmentMapper = assignmentMapper;
        this.submissionMapper = submissionMapper;
        this.structureMapper = structureMapper;
        this.rubricMapper = rubricMapper;
        this.objectMapper = objectMapper;
        this.uploadRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public Map<String, Object> cleanup(Long semesterId, boolean execute) {
        return cleanup(semesterId, execute, false, null, null);
    }

    public Map<String, Object> cleanup(Long semesterId, boolean execute, boolean allowUnrecoverable) {
        return cleanup(semesterId, execute, allowUnrecoverable, null, null);
    }

    public Map<String, Object> cleanup(Long semesterId,
                                       boolean execute,
                                       boolean allowUnrecoverable,
                                       String confirmedSemesterName,
                                       String expectedPreviewToken) {
        TSemester semester = requireArchivedSemester(semesterId);
        List<TAssignment> assignments = assignmentMapper.selectList(new LambdaQueryWrapper<TAssignment>()
                .eq(TAssignment::getSemesterId, semesterId)
                .orderByAsc(TAssignment::getId));
        List<Long> assignmentIds = assignments.stream().map(TAssignment::getId).toList();
        LinkedHashMap<String, CleanupCandidate> candidates = collectCandidates(assignmentIds);
        Set<String> sharedPaths = sharedPaths(assignmentIds, candidates);

        int candidateCount = 0;
        int missingCount = 0;
        int skippedCount = 0;
        int submissionZipCount = 0;
        int rubricFileCount = 0;
        int unrecoverableSubmissionCount = 0;
        int deletedCount = 0;
        long candidateBytes = 0L;
        long deletedBytes = 0L;
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> previewFiles = new ArrayList<>();

        for (CleanupCandidate candidate : candidates.values()) {
            candidate.inspect(uploadRoot, sharedPaths.contains(candidate.key));
        }
        String previewToken = previewToken(semester, candidates.values());
        if (execute) {
            if (confirmedSemesterName == null
                    || !semester.getName().equals(confirmedSemesterName.trim())) {
                throw BusinessException.badRequest("学期名称确认不匹配，请重新输入");
            }
            if (expectedPreviewToken == null
                    || expectedPreviewToken.isBlank()
                    || !MessageDigest.isEqual(
                            previewToken.getBytes(StandardCharsets.UTF_8),
                            expectedPreviewToken.getBytes(StandardCharsets.UTF_8))) {
                throw BusinessException.conflict("归档文件列表已变化，请重新预览后再清理");
            }
        }
        int unrecoverableCandidates = candidates.values().stream()
                .filter(candidate -> candidate.deletable && candidate.submissionFile)
                .mapToInt(candidate -> candidate.unrecoverableSubmissionCount)
                .sum();
        if (execute && unrecoverableCandidates > 0 && !allowUnrecoverable) {
            throw BusinessException.badRequest(
                    "有 " + unrecoverableCandidates + " 份提交没有可重建的解析结构，请确认不可恢复风险后再清理");
        }

        for (CleanupCandidate candidate : candidates.values()) {
            if (candidate.submissionFile && candidate.exists && candidate.deletable) {
                submissionZipCount++;
                unrecoverableSubmissionCount += candidate.unrecoverableSubmissionCount;
            }
            if (candidate.rubricFile && candidate.exists && candidate.deletable) {
                rubricFileCount++;
            }
            if (candidate.exists && candidate.deletable) {
                candidateCount++;
                candidateBytes += candidate.fileSize;
            } else if (!candidate.exists && candidate.pathAllowed && !candidate.sharedReference) {
                missingCount++;
            } else {
                skippedCount++;
            }

            if (previewFiles.size() < MAX_PREVIEW_FILES) {
                previewFiles.add(candidate.payload());
            }
            if (!execute || !candidate.deletable) {
                continue;
            }
            try {
                boolean deleted = Files.deleteIfExists(candidate.path);
                if (deleted) {
                    deletedCount++;
                    deletedBytes += candidate.fileSize;
                }
                cleanupEmptyParents(candidate.path.getParent());
            } catch (Exception ex) {
                errors.add(candidate.fileName + ": " + ex.getMessage());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dryRun", !execute);
        payload.put("semesterId", semester.getId());
        payload.put("semesterName", semester.getName());
        payload.put("assignmentCount", assignmentIds.size());
        payload.put("referencedFileCount", candidates.size());
        payload.put("candidateCount", candidateCount);
        payload.put("candidateBytes", candidateBytes);
        payload.put("submissionZipCount", submissionZipCount);
        payload.put("rubricFileCount", rubricFileCount);
        payload.put("unrecoverableSubmissionCount", unrecoverableSubmissionCount);
        payload.put("missingCount", missingCount);
        payload.put("skippedCount", skippedCount);
        payload.put("deletedCount", deletedCount);
        payload.put("deletedBytes", deletedBytes);
        payload.put("errors", errors);
        payload.put("candidateFiles", previewFiles);
        payload.put("detailsTruncated", candidates.size() > MAX_PREVIEW_FILES);
        payload.put("previewToken", previewToken);
        return payload;
    }

    private TSemester requireArchivedSemester(Long semesterId) {
        TSemester semester = semesterMapper.selectById(semesterId);
        if (semester == null) {
            throw BusinessException.notFound("学期不存在");
        }
        if (!"archived".equals(semester.getStatus())) {
            throw BusinessException.conflict("只能清理已归档学期的文件");
        }
        return semester;
    }

    private LinkedHashMap<String, CleanupCandidate> collectCandidates(List<Long> assignmentIds) {
        LinkedHashMap<String, CleanupCandidate> candidates = new LinkedHashMap<>();
        if (assignmentIds.isEmpty()) {
            return candidates;
        }
        List<TSubmission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .in(TSubmission::getAssignmentId, assignmentIds)
                .orderByAsc(TSubmission::getId));
        Set<String> recoverableStructureRefs = recoverableStructureRefs(submissions);
        for (TSubmission submission : submissions) {
            if (submission.getFileUrl() == null || submission.getFileUrl().isBlank()) {
                continue;
            }
            CleanupCandidate candidate = candidate(candidates, submission.getFileUrl(), submission.getFileName());
            candidate.submissionFile = true;
            if (submission.getProjectStructureId() == null
                    || !recoverableStructureRefs.contains(
                            structureRef(submission.getId(), submission.getProjectStructureId()))) {
                candidate.unrecoverableSubmissionCount++;
            }
        }

        List<TRubric> rubrics = rubricMapper.selectList(new LambdaQueryWrapper<TRubric>()
                .in(TRubric::getAssignmentId, assignmentIds)
                .isNotNull(TRubric::getFileUrl)
                .orderByAsc(TRubric::getId));
        for (TRubric rubric : rubrics) {
            if (rubric.getFileUrl() == null || rubric.getFileUrl().isBlank()) {
                continue;
            }
            CleanupCandidate candidate = candidate(candidates, rubric.getFileUrl(), null);
            candidate.rubricFile = true;
        }
        return candidates;
    }

    private Set<String> recoverableStructureRefs(List<TSubmission> submissions) {
        List<Long> structureIds = submissions.stream()
                .map(TSubmission::getProjectStructureId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (structureIds.isEmpty()) {
            return Set.of();
        }
        Set<String> recoverable = new LinkedHashSet<>();
        for (TProjectStructure structure : structureMapper.selectBatchIds(structureIds)) {
            if (hasRecoverableFileTree(structure)) {
                recoverable.add(structureRef(structure.getSubmissionId(), structure.getId()));
            }
        }
        return recoverable;
    }

    private String structureRef(Long submissionId, Long structureId) {
        return String.valueOf(submissionId) + ":" + String.valueOf(structureId);
    }

    private boolean hasRecoverableFileTree(TProjectStructure structure) {
        if (structure == null || structure.getStructureJson() == null || structure.getStructureJson().isBlank()) {
            return false;
        }
        try {
            JsonNode fileTree = objectMapper.readTree(structure.getStructureJson()).path("file_tree");
            if (!fileTree.isArray()) {
                return false;
            }
            Set<String> entryNames = new LinkedHashSet<>();
            for (JsonNode file : fileTree) {
                if (!file.path("path").asText("").isBlank() && file.hasNonNull("content")) {
                    String entryName = normalizeRecoverableEntry(file.path("path").asText());
                    if (!entryName.isBlank() && !entryNames.add(entryName)) {
                        return false;
                    }
                }
            }
            return !entryNames.isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }

    private String normalizeRecoverableEntry(String name) {
        String normalized = Paths.get(name).normalize().toString().replace('\\', '/');
        if (normalized.startsWith("../") || normalized.equals("..") || normalized.startsWith("/")) {
            return "";
        }
        return normalized;
    }

    private CleanupCandidate candidate(Map<String, CleanupCandidate> candidates, String fileUrl, String fileName) {
        String key = normalizedKey(fileUrl);
        CleanupCandidate candidate = candidates.computeIfAbsent(key, ignored -> new CleanupCandidate(key, fileUrl));
        if (fileName != null && !fileName.isBlank()) {
            candidate.fileName = fileName;
        }
        return candidate;
    }

    private Set<String> sharedPaths(List<Long> assignmentIds, Map<String, CleanupCandidate> candidates) {
        Set<String> shared = new LinkedHashSet<>();
        if (assignmentIds.isEmpty() || candidates.isEmpty()) {
            return shared;
        }
        List<TSubmission> otherSubmissions = submissionMapper.selectList(new LambdaQueryWrapper<TSubmission>()
                .isNotNull(TSubmission::getFileUrl)
                .notIn(TSubmission::getAssignmentId, assignmentIds));
        otherSubmissions.stream()
                .map(TSubmission::getFileUrl)
                .filter(fileUrl -> fileUrl != null && !fileUrl.isBlank())
                .map(this::normalizedKey)
                .filter(candidates::containsKey)
                .forEach(shared::add);

        List<TRubric> otherRubrics = rubricMapper.selectList(new LambdaQueryWrapper<TRubric>()
                .isNotNull(TRubric::getFileUrl)
                .notIn(TRubric::getAssignmentId, assignmentIds));
        otherRubrics.stream()
                .map(TRubric::getFileUrl)
                .filter(fileUrl -> fileUrl != null && !fileUrl.isBlank())
                .map(this::normalizedKey)
                .filter(candidates::containsKey)
                .forEach(shared::add);
        return shared;
    }

    private String normalizedKey(String fileUrl) {
        try {
            Path path = Paths.get(fileUrl).toAbsolutePath().normalize();
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    ? path.toRealPath().toString()
                    : path.toString();
        } catch (Exception ex) {
            return "invalid:" + fileUrl;
        }
    }

    private String previewToken(TSemester semester, Iterable<CleanupCandidate> candidates) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, String.valueOf(semester.getId()));
            updateDigest(digest, semester.getName());
            updateDigest(digest, String.valueOf(semester.getArchivedAt()));
            for (CleanupCandidate candidate : candidates) {
                updateDigest(digest, candidate.key);
                updateDigest(digest, String.valueOf(candidate.exists));
                updateDigest(digest, String.valueOf(candidate.deletable));
                updateDigest(digest, String.valueOf(candidate.sharedReference));
                updateDigest(digest, String.valueOf(candidate.fileSize));
                updateDigest(digest, String.valueOf(candidate.lastModifiedMillis));
                updateDigest(digest, String.valueOf(candidate.unrecoverableSubmissionCount));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("生成归档文件预览标识失败", ex);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
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

    private static final class CleanupCandidate {
        private final String key;
        private final String originalUrl;
        private String fileName;
        private boolean submissionFile;
        private boolean rubricFile;
        private int unrecoverableSubmissionCount;
        private Path path;
        private boolean pathAllowed;
        private boolean sharedReference;
        private boolean exists;
        private boolean deletable;
        private long fileSize;
        private long lastModifiedMillis;
        private String skipReason;

        private CleanupCandidate(String key, String originalUrl) {
            this.key = key;
            this.originalUrl = originalUrl;
        }

        private void inspect(Path uploadRoot, boolean shared) {
            this.sharedReference = shared;
            try {
                path = Paths.get(originalUrl).toAbsolutePath().normalize();
                pathAllowed = path.startsWith(uploadRoot) && !Files.isSymbolicLink(path);
                exists = pathAllowed && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
                if (exists) {
                    Path realRoot = Files.exists(uploadRoot) ? uploadRoot.toRealPath() : uploadRoot;
                    pathAllowed = path.toRealPath().startsWith(realRoot);
                    exists = pathAllowed;
                }
                fileSize = exists ? Files.size(path) : 0L;
                lastModifiedMillis = exists ? Files.getLastModifiedTime(path).toMillis() : 0L;
                if (fileName == null || fileName.isBlank()) {
                    fileName = path.getFileName() == null ? originalUrl : path.getFileName().toString();
                }
                if (!pathAllowed) {
                    skipReason = "路径不在上传目录或为符号链接";
                } else if (sharedReference) {
                    skipReason = "文件仍被其他学期引用";
                } else if (!exists) {
                    skipReason = "文件已不存在";
                }
                deletable = pathAllowed && !sharedReference && exists;
            } catch (Exception ex) {
                pathAllowed = false;
                exists = false;
                deletable = false;
                skipReason = "文件路径无效";
                if (fileName == null || fileName.isBlank()) {
                    fileName = originalUrl;
                }
            }
        }

        private Map<String, Object> payload() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", fileName);
            item.put("fileType", submissionFile && rubricFile ? "mixed" : submissionFile ? "submission_zip" : "rubric_file");
            item.put("fileSize", fileSize);
            item.put("pathAllowed", pathAllowed);
            item.put("sharedReference", sharedReference);
            item.put("exists", exists);
            item.put("deletable", deletable);
            item.put("unrecoverableSubmissionCount", unrecoverableSubmissionCount);
            item.put("skipReason", skipReason);
            return item;
        }
    }
}
