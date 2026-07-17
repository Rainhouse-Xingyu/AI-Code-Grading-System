package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TFile;
import com.rainexis.backend.mapper.TFileMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务
 * 处理文件上传到本地文件系统，支持学生提交ZIP文件和评分标准文件（doc/docx/xlsx/xls）
 * 包含文件类型校验、大小限制、ZIP魔数检测等安全验证
 */
@Service
public class FileStorageService {
    /** 学生提交ZIP文件最大为 50MB */
    private static final long MAX_SUBMISSION_ZIP_SIZE = 50L * 1024 * 1024;
    /** 评分标准文件最大为 10MB */
    private static final long MAX_RUBRIC_SIZE = 10L * 1024 * 1024;
    private final TFileMapper fileMapper;
    private final JdbcTemplate jdbcTemplate;
    /** 文件上传根目录 */
    private final Path uploadRoot;

    public FileStorageService(TFileMapper fileMapper,
                              JdbcTemplate jdbcTemplate,
                              @Value("${app.storage.root}") String storageRoot) {
        this.fileMapper = fileMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.uploadRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    /** 存储学生提交的ZIP文件 */
    public StoredFile storeSubmissionZip(MultipartFile file, Long uploaderId, String username, String realName) {
        ensureExtension(file, ".zip");
        ensureMaxSize(file, MAX_SUBMISSION_ZIP_SIZE, "文件大小不能超过 50MB");
        ensureZipMagic(file);
        String displayName = safePart(username) + "_" + safePart(realName == null ? "student" : realName) + ".zip";
        return store(file, uploaderId, "submission_zip", displayName);
    }

    /** 存储评分标准文件（支持 .doc、.docx、.xlsx 和 .xls） */
    public StoredFile storeRubric(MultipartFile file, Long uploaderId) {
        String lower = originalName(file).toLowerCase(Locale.ROOT);
        boolean doc = lower.endsWith(".doc");
        boolean docx = lower.endsWith(".docx");
        boolean xlsx = lower.endsWith(".xlsx");
        boolean xls = lower.endsWith(".xls");
        if (!doc && !docx && !xlsx && !xls) {
            throw BusinessException.badRequest("评分标准仅支持 .doc、.docx、.xlsx 或 .xls");
        }
        ensureMaxSize(file, MAX_RUBRIC_SIZE, "评分标准文件大小不能超过 10MB");
        if (doc || xls) {
            ensureOle2Magic(file, doc ? "DOC" : "XLS");
        } else {
            ensureZipMagic(file);
        }
        return store(file, uploaderId, doc || docx ? "rubric_word" : "rubric_excel", null);
    }

    public Map<String, Object> cleanupOlderThan(int olderThanDays, boolean execute) {
        if (olderThanDays < 1) {
            throw BusinessException.badRequest("清理天数必须大于 0");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(olderThanDays);
        List<TFile> candidates = fileMapper.selectList(new LambdaQueryWrapper<TFile>()
                .lt(TFile::getCreatedAt, cutoff)
                .orderByAsc(TFile::getCreatedAt));
        Set<String> referencedPaths = referencedFilePaths();
        long totalBytes = 0;
        int candidateCount = 0;
        int protectedCount = 0;
        int deletedFiles = 0;
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> candidateFiles = new ArrayList<>();
        for (TFile file : candidates) {
            Map<String, Object> candidate = cleanupCandidate(file, referencedPaths);
            candidateFiles.add(candidate);
            boolean deletable = Boolean.TRUE.equals(candidate.get("deletable"));
            if (deletable) {
                candidateCount++;
                totalBytes += file.getFileSize() == null ? 0L : file.getFileSize();
            } else {
                protectedCount++;
            }
            if (!execute) {
                continue;
            }
            if (!deletable) {
                continue;
            }
            try {
                Path path = requiredCleanupPath(file.getFileUrl());
                Files.deleteIfExists(path);
                fileMapper.deleteById(file.getId());
                deletedFiles++;
            } catch (Exception ex) {
                errors.add(file.getFileName() + ": " + ex.getMessage());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dryRun", !execute);
        payload.put("olderThanDays", olderThanDays);
        payload.put("cutoffTime", cutoff);
        payload.put("candidateCount", candidateCount);
        payload.put("candidateBytes", totalBytes);
        payload.put("candidateFiles", candidateFiles);
        payload.put("protectedCount", protectedCount);
        payload.put("deletedCount", deletedFiles);
        payload.put("errors", errors);
        return payload;
    }

    private Map<String, Object> cleanupCandidate(TFile file, Set<String> referencedPaths) {
        String normalizedPath = normalizedPath(file.getFileUrl());
        boolean pathAllowed = normalizedPath != null && Paths.get(normalizedPath).startsWith(cleanupRoot());
        boolean protectedReference = normalizedPath != null && referencedPaths.contains(normalizedPath);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", file.getId());
        item.put("fileName", file.getFileName());
        item.put("storageName", file.getStorageName());
        item.put("fileType", file.getFileType());
        item.put("fileSize", file.getFileSize() == null ? 0L : file.getFileSize());
        item.put("createdAt", file.getCreatedAt());
        item.put("relativePath", cleanupRelativePath(file.getFileUrl()));
        item.put("pathAllowed", pathAllowed);
        item.put("protectedReference", protectedReference);
        item.put("deletable", pathAllowed && !protectedReference);
        item.put("skipReason", !pathAllowed ? "路径不在上传目录" : protectedReference ? "业务记录仍在引用" : null);
        return item;
    }

    private Set<String> referencedFilePaths() {
        Set<String> paths = new LinkedHashSet<>();
        List<String> fileUrls = jdbcTemplate.queryForList("""
                SELECT file_url FROM t_submission WHERE file_url IS NOT NULL AND file_url <> ''
                UNION
                SELECT file_url FROM t_rubric WHERE file_url IS NOT NULL AND file_url <> ''
                """, String.class);
        for (String fileUrl : fileUrls) {
            String normalized = normalizedPath(fileUrl);
            if (normalized != null) {
                paths.add(normalized);
            }
        }
        return paths;
    }

    private Path requiredCleanupPath(String fileUrl) {
        String normalized = normalizedPath(fileUrl);
        if (normalized == null) {
            throw BusinessException.badRequest("文件路径无效");
        }
        Path path = Paths.get(normalized);
        if (!path.startsWith(cleanupRoot())) {
            throw BusinessException.badRequest("文件路径不在上传目录");
        }
        return path;
    }

    private String normalizedPath(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(fileUrl).toAbsolutePath().normalize();
            return Files.exists(path)
                    ? path.toRealPath().toString()
                    : path.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String cleanupRelativePath(String fileUrl) {
        String normalized = normalizedPath(fileUrl);
        if (normalized == null) {
            return "";
        }
        Path path = Paths.get(normalized);
        Path root = cleanupRoot();
        if (path.startsWith(root)) {
            return root.relativize(path).toString();
        }
        return path.getFileName() == null ? fileUrl : path.getFileName().toString();
    }

    private Path cleanupRoot() {
        try {
            return Files.exists(uploadRoot) ? uploadRoot.toRealPath() : uploadRoot;
        } catch (IOException ex) {
            return uploadRoot;
        }
    }

    /** 通用文件存储方法：校验 → 创建目录 → 写入文件 → 记录元数据到数据库 */
    private StoredFile store(MultipartFile file, Long uploaderId, String fileType, String forcedStorageName) {
        if (file.isEmpty()) {
            throw BusinessException.badRequest("上传文件不能为空");
        }
        try {
            Files.createDirectories(uploadRoot);
            YearMonth month = YearMonth.from(LocalDate.now());
            Path typeMonthDir = uploadRoot
                    .resolve(safePart(fileType))
                    .resolve(String.valueOf(month.getYear()))
                    .resolve(String.format("%02d", month.getMonthValue()))
                    .normalize();
            Files.createDirectories(typeMonthDir);
            String originalName = originalName(file);
            String storageName = forcedStorageName == null
                    ? UUID.randomUUID() + "_" + safePart(originalName)
                    : forcedStorageName;
            Path targetDir = forcedStorageName == null
                    ? typeMonthDir
                    : typeMonthDir.resolve(UUID.randomUUID().toString()).normalize();
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(storageName).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw BusinessException.badRequest("非法文件名");
            }
            file.transferTo(target);

            TFile record = new TFile();
            record.setFileName(originalName);
            record.setStorageName(storageName);
            record.setFileUrl(target.toString());
            record.setFileType(fileType);
            record.setFileSize(file.getSize());
            record.setUploaderId(uploaderId);
            fileMapper.insert(record);
            return new StoredFile(record.getId(), originalName, storageName, target, file.getSize());
        } catch (IOException ex) {
            throw new BusinessException(500, "文件保存失败: " + ex.getMessage());
        }
    }

    /** 校验文件扩展名 */
    private void ensureExtension(MultipartFile file, String extension) {
        if (!originalName(file).toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw BusinessException.badRequest("仅支持 ZIP 格式");
        }
    }

    /** 校验文件大小是否在允许范围内 */
    private void ensureMaxSize(MultipartFile file, long maxSize, String message) {
        if (file.getSize() > maxSize) {
            throw BusinessException.badRequest(message);
        }
    }

    /** 校验ZIP文件魔数（PK..），防止上传非ZIP文件伪装 */
    private void ensureZipMagic(MultipartFile file) {
        byte[] signature = new byte[4];
        try (InputStream input = file.getInputStream()) {
            int read = input.read(signature);
            if (read < 4 || signature[0] != 'P' || signature[1] != 'K'
                    || !((signature[2] == 3 && signature[3] == 4)
                    || (signature[2] == 5 && signature[3] == 6)
                    || (signature[2] == 7 && signature[3] == 8))) {
                throw BusinessException.badRequest("ZIP 文件格式无效");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw BusinessException.badRequest("ZIP 文件读取失败: " + ex.getMessage());
        }
    }

    /** 校验老式Office二进制文件魔数，支持 .doc/.xls */
    private void ensureOle2Magic(MultipartFile file, String label) {
        byte[] signature = new byte[8];
        byte[] expected = new byte[] {
                (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
        };
        try (InputStream input = file.getInputStream()) {
            int read = input.read(signature);
            if (read < signature.length) {
                throw BusinessException.badRequest(label + " 文件格式无效");
            }
            for (int i = 0; i < signature.length; i++) {
                if (signature[i] != expected[i]) {
                    throw BusinessException.badRequest(label + " 文件格式无效");
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw BusinessException.badRequest(label + " 文件读取失败: " + ex.getMessage());
        }
    }

    /** 获取安全原始文件名 */
    private String originalName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "upload.bin";
        }
        return Paths.get(original).getFileName().toString();
    }

    /** 清理文件名中的非法字符，保留字母数字中文和基本标点 */
    private String safePart(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    /** 已存储文件的元数据记录 */
    public record StoredFile(Long id, String originalName, String storageName, Path path, long size) {
    }
}
