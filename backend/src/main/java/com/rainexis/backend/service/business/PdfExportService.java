package com.rainexis.backend.service.business;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * PDF 导出服务
 * 将评分报告导出为 PDF 文件，支持单个导出和批量打包为 ZIP 下载
 * 使用 OpenPDF（lowagie）生成 PDF，包含学生信息、评分总览、维度得分和问题建议
 */
@Service
public class PdfExportService {
    private final TSubmissionMapper submissionMapper;
    private final TAiReportMapper reportMapper;
    private final TTeacherReviewMapper reviewMapper;
    private final TUserMapper userMapper;
    private final TAssignmentMapper assignmentMapper;
    private final ObjectMapper objectMapper;

    public PdfExportService(TSubmissionMapper submissionMapper,
                            TAiReportMapper reportMapper,
                            TTeacherReviewMapper reviewMapper,
                            TUserMapper userMapper,
                            TAssignmentMapper assignmentMapper,
                            ObjectMapper objectMapper) {
        this.submissionMapper = submissionMapper;
        this.reportMapper = reportMapper;
        this.reviewMapper = reviewMapper;
        this.userMapper = userMapper;
        this.assignmentMapper = assignmentMapper;
        this.objectMapper = objectMapper;
    }

    /** 导出单个提交的评分报告为PDF */
    public PdfFile exportSingle(Long submissionId) {
        TSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BusinessException.notFound("提交不存在");
        }
        TUser student = userMapper.selectById(submission.getStudentId());
        TAiReport report = reportMapper.selectById(submission.getCurrentReportId());
        TTeacherReview review = reviewMapper.selectOne(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submissionId)
                .orderByDesc(TTeacherReview::getCreatedAt)
                .last("limit 1"));
        String filename = pdfFilename(student, submissionId);
        return new PdfFile(filename, renderPdf(filename, submission, student, report, review));
    }

    /** 批量导出多个提交的评分报告为ZIP包 */
    public PdfArchive exportBatch(Iterable<Long> submissionIds) {
        try {
            List<Long> ids = new ArrayList<>();
            submissionIds.forEach(ids::add);
            if (ids.isEmpty()) {
                throw BusinessException.badRequest("请选择待导出的提交");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String zipName = batchFilename(ids);
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (Long id : ids) {
                    TSubmission submission = submissionMapper.selectById(id);
                    if (submission == null) {
                        continue;
                    }
                    TUser student = userMapper.selectById(submission.getStudentId());
                    String name = pdfFilename(student, id);
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(exportSingle(id).bytes());
                    zip.closeEntry();
                }
            }
            return new PdfArchive(zipName, out.toByteArray());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "批量 PDF 导出失败: " + ex.getMessage());
        }
    }

    /** 渲染PDF内容：学生信息、评分标准、得分明细和报告正文 */
    private byte[] renderPdf(String title, TSubmission submission, TUser student, TAiReport report, TTeacherReview review) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();
            Font font = new Font(chineseBaseFont(), 11);
            Font titleFont = new Font(chineseBaseFont(), 16, Font.BOLD);
            TAssignment assignment = assignmentMapper.selectById(submission.getAssignmentId());
            String className = student == null ? "" : nullToEmpty(student.getClassName());
            String studentNo = student == null ? String.valueOf(submission.getStudentId()) : nullToEmpty(student.getUsername());
            String studentName = student == null ? "" : nullToEmpty(student.getRealName());
            Paragraph reportTitle = new Paragraph(reportTitle(assignment), titleFont);
            reportTitle.setAlignment(Element.ALIGN_CENTER);
            document.add(reportTitle);
            document.add(new Paragraph("文件名：" + title, font));
            document.add(new Paragraph("作业：" + (assignment == null ? submission.getAssignmentId() : nullToEmpty(assignment.getTitle())), font));
            document.add(new Paragraph("学号：" + studentNo, font));
            document.add(new Paragraph("班级：" + className, font));
            document.add(new Paragraph("姓名：" + studentName, font));
            document.add(new Paragraph("提交文件：" + nullToEmpty(submission.getFileName()), font));
            document.add(new Paragraph("最终得分：" + finalScoreText(report, review), font));
            if (review != null && hasText(review.getFinalComment())) {
                document.add(new Paragraph("教师评语：" + review.getFinalComment(), font));
            }
            document.add(new Paragraph(" ", font));
            appendRubricSection(document, font, assignment);
            appendEarnedSection(document, font, review != null && hasText(review.getModifiedJson())
                    ? review.getModifiedJson()
                    : report == null ? null : report.getScoreDetailJson());
            document.add(new Paragraph("请结合得分明细逐项检查自己的实现，重点核对采分点对应的代码是否完整、清晰、可运行。", font));
            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new BusinessException(500, "PDF 导出失败: " + ex.getMessage());
        }
    }

    private BaseFont chineseBaseFont() throws Exception {
        for (String candidate : fontCandidates()) {
            String normalized = normalizeFontCandidate(candidate);
            if (normalized == null) {
                continue;
            }
            try {
                return BaseFont.createFont(normalized, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // Try the next known CJK font path.
            }
        }
        try {
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        } catch (Exception ex) {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
        }
    }

    private List<String> fontCandidates() {
        List<String> candidates = new ArrayList<>();
        String configured = System.getenv("REPORT_PDF_FONT_PATH");
        if (hasText(configured)) {
            candidates.add(configured);
        }
        candidates.add("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0");
        candidates.add("/usr/share/fonts/truetype/arphic/uming.ttc,0");
        candidates.add("/System/Library/Fonts/STHeiti Light.ttc,0");
        candidates.add("/System/Library/Fonts/STHeiti Medium.ttc,0");
        candidates.add("/System/Library/Fonts/Supplemental/Songti.ttc,0");
        candidates.add("/Library/Fonts/Arial Unicode.ttf");
        candidates.add("/System/Library/Fonts/Supplemental/Arial Unicode.ttf");
        return candidates;
    }

    private String normalizeFontCandidate(String candidate) {
        String value = candidate.trim();
        if (value.isEmpty()) {
            return null;
        }
        int ttcIndex = value.lastIndexOf(',');
        String pathValue = ttcIndex > 0 ? value.substring(0, ttcIndex) : value;
        return Files.isRegularFile(Path.of(pathValue)) ? value : null;
    }

    private String finalScoreText(TAiReport report, TTeacherReview review) {
        if (review != null && review.getFinalScore() != null) {
            return review.getFinalScore().toPlainString();
        }
        if (report != null && report.getTotalScore() != null) {
            return report.getTotalScore().toPlainString();
        }
        return "待评分";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String reportTitle(TAssignment assignment) {
        if (assignment != null && hasText(assignment.getCourseName())) {
            return assignment.getCourseName().trim() + "评分报告";
        }
        return "AI 代码评分报告";
    }

    /** 将本次评分标准追加到PDF文档中 */
    private void appendRubricSection(Document document, Font font, TAssignment assignment) throws Exception {
        if (assignment == null || !hasText(assignment.getNormalizedRubricJson())) {
            return;
        }
        document.add(new Paragraph("本次评分标准：", font));
        Map<String, Object> rubric = objectMapper.readValue(assignment.getNormalizedRubricJson(), new TypeReference<>() {
        });
        Object dimensionsValue = rubric.get("dimensions");
        if (!(dimensionsValue instanceof List<?> dimensions)) {
            return;
        }
        for (Object dimensionValue : dimensions) {
            if (!(dimensionValue instanceof Map<?, ?> dimension)) {
                continue;
            }
            document.add(new Paragraph("- " + text(dimension.get("name"))
                    + "（满分 " + text(dimension.get("max_score")) + "）："
                    + text(dimension.get("criteria")), font));
        }
        document.add(new Paragraph(" ", font));
    }

    /** 追加得分明细，不展示未得分项 */
    private void appendEarnedSection(Document document, Font font, String json) throws Exception {
        if (json == null || json.isBlank()) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return;
        }
        List<Map<String, Object>> earnedRows = rows.stream()
                .filter(row -> number(row.get("score")) > 0)
                .toList();
        if (earnedRows.isEmpty()) {
            return;
        }
        document.add(new Paragraph("得分明细：", font));
        Font headerFont = new Font(font.getBaseFont(), 10, Font.BOLD);
        Font bodyFont = new Font(font.getBaseFont(), 9);
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {0.8f, 3.2f, 1.2f, 5.8f});
        table.setSpacingBefore(6);
        table.setSpacingAfter(10);
        table.setSplitLate(false);
        table.setSplitRows(true);
        addHeaderCell(table, "序号", headerFont);
        addHeaderCell(table, "得分点", headerFont);
        addHeaderCell(table, "得分", headerFont);
        addHeaderCell(table, "说明", headerFont);
        int index = 1;
        for (Map<String, Object> row : earnedRows) {
            addBodyCell(table, String.valueOf(index++), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, text(row.get("name")), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table, scoreText(row), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, text(row.getOrDefault("comment", "")), bodyFont, Element.ALIGN_LEFT);
        }
        document.add(table);
        document.add(new Paragraph(" ", font));
    }

    private void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(new Color(230, 230, 230));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private String scoreText(Map<String, Object> row) {
        Object score = row.getOrDefault("score", "");
        Object maxScore = row.getOrDefault("max_score", row.getOrDefault("maxScore", ""));
        if (maxScore == null || String.valueOf(maxScore).isBlank()) {
            return text(score);
        }
        return text(score) + "/" + text(maxScore);
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 生成PDF文件名：学号_姓名.pdf */
    private String pdfFilename(TUser student, Long submissionId) {
        if (student == null) {
            return "submission_" + submissionId + ".pdf";
        }
        return safePart(student.getUsername()) + "_" + safePart(student.getRealName()) + ".pdf";
    }

    /** 生成批量导出的ZIP文件名：作业标题_评分报告_日期.zip */
    private String batchFilename(List<Long> submissionIds) {
        TSubmission first = submissionMapper.selectById(submissionIds.get(0));
        if (first != null) {
            TAssignment assignment = assignmentMapper.selectById(first.getAssignmentId());
            if (assignment != null && assignment.getTitle() != null && !assignment.getTitle().isBlank()) {
                return safePart(assignment.getTitle()) + "_评分报告_" + LocalDate.now() + ".zip";
            }
        }
        return "评分报告_" + LocalDate.now() + ".zip";
    }

    private String safePart(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    /** PDF单文件结果 */
    public record PdfFile(String filename, byte[] bytes) {
    }

    /** PDF批量导出ZIP包结果 */
    public record PdfArchive(String filename, byte[] bytes) {
    }
}
