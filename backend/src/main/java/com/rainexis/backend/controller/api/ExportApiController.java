package com.rainexis.backend.controller.api;

import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import com.rainexis.backend.service.business.PdfExportService;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDF 导出 API 控制器
 * 教师可以单份导出或批量导出评分报告为 PDF（批量导出打包为 ZIP）
 */
@RestController
@RequestMapping("/api/v1/exports/pdf")
public class ExportApiController {
    private final PdfExportService pdfExportService;
    private final AccessControlService accessControlService;

    public ExportApiController(PdfExportService pdfExportService,
                               AccessControlService accessControlService) {
        this.pdfExportService = pdfExportService;
        this.accessControlService = accessControlService;
    }

    /** 导出单个提交的评分报告为PDF */
    @PostMapping("/single/{submissionId}")
    public ResponseEntity<byte[]> single(@PathVariable Long submissionId) {
        AuthContext.requireTeacher();
        accessControlService.requireTeacherSubmissionAccess(submissionId);
        PdfExportService.PdfFile pdf = pdfExportService.exportSingle(submissionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdf.filename(), java.nio.charset.StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(pdf.bytes());
    }

    /** 批量导出多个提交的评分报告为ZIP包（需 CSRF Token） */
    @PostMapping("/batch")
    public ResponseEntity<byte[]> batch(@RequestBody BatchExportRequest request) {
        AuthContext.requireTeacher();
        if (request == null || request.submissionIds() == null || request.submissionIds().isEmpty()) {
            throw BusinessException.badRequest("请选择待导出的提交");
        }
        request.submissionIds().forEach(accessControlService::requireTeacherSubmissionAccess);
        PdfExportService.PdfArchive archive = pdfExportService.exportBatch(request.submissionIds());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(archive.filename(), java.nio.charset.StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(archive.bytes());
    }

    public record BatchExportRequest(List<Long> submissionIds) {
    }
}
