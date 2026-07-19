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

    /** 渲染PDF内容：学生信息、整合评分标准的得分明细和问题建议 */
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
                document.add(new Paragraph("教师总评：" + review.getFinalComment(), font));
            }
            document.add(new Paragraph(" ", font));
            appendEarnedSection(document, font, assignment, review != null && hasText(review.getModifiedJson())
                    ? review.getModifiedJson()
                    : report == null ? null : report.getScoreDetailJson());
            appendIssueSection(document, font, report == null ? null : report.getSuggestion());
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

    /** 追加得分明细，将评分标准合并进表格且不展示未得分项 */
    private void appendEarnedSection(Document document, Font font, TAssignment assignment, String json) throws Exception {
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
        List<RubricCriterion> rubricCriteria = rubricCriteria(assignment);
        List<EarnedRow> earnedRows = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            if (number(row.get("score")) <= 0) {
                continue;
            }
            earnedRows.add(new EarnedRow(row, criterionFor(row, index, rows.size(), rubricCriteria)));
        }
        if (earnedRows.isEmpty()) {
            return;
        }
        document.add(new Paragraph("得分明细：", font));
        Font headerFont = new Font(font.getBaseFont(), 9, Font.BOLD);
        Font bodyFont = new Font(font.getBaseFont(), 8.5f);
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {0.65f, 2.15f, 1.35f, 3.0f, 3.1f});
        table.setSpacingBefore(6);
        table.setSpacingAfter(10);
        table.setSplitLate(false);
        table.setSplitRows(true);
        table.setHeaderRows(1);
        addHeaderCell(table, "序号", headerFont);
        addHeaderCell(table, "得分点", headerFont);
        addHeaderCell(table, "得分", headerFont);
        addHeaderCell(table, "评分标准", headerFont);
        addHeaderCell(table, "得分说明", headerFont);
        int index = 1;
        for (EarnedRow earnedRow : earnedRows) {
            Map<String, Object> row = earnedRow.score();
            addBodyCell(table, String.valueOf(index++), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, text(row.get("name")), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table, scoreText(row), bodyFont, Element.ALIGN_CENTER);
            addBodyCell(table, earnedRow.criterion(), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table, text(row.getOrDefault("comment", "")), bodyFont, Element.ALIGN_LEFT);
        }
        document.add(table);
    }

    /** 追加问题与改进建议，合并文件路径和行号以节省版面。 */
    private void appendIssueSection(Document document, Font font, String json) throws Exception {
        if (!hasText(json)) {
            return;
        }
        List<Map<String, Object>> issues;
        try {
            issues = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return;
        }
        issues = issues.stream()
                .filter(issue -> hasText(text(issue.getOrDefault("description", issue.get("message")))))
                .toList();
        if (issues.isEmpty()) {
            return;
        }
        document.add(new Paragraph("问题与改进建议：", font));
        Font headerFont = new Font(font.getBaseFont(), 9, Font.BOLD);
        Font bodyFont = new Font(font.getBaseFont(), 8.5f);
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {0.65f, 1.05f, 2.8f, 5.75f});
        table.setSpacingBefore(6);
        table.setSpacingAfter(10);
        table.setSplitLate(false);
        table.setSplitRows(true);
        table.setHeaderRows(1);
        addHeaderCell(table, "序号", headerFont);
        addHeaderCell(table, "级别", headerFont);
        addHeaderCell(table, "位置", headerFont);
        addHeaderCell(table, "问题与建议", headerFont);
        int index = 1;
        for (Map<String, Object> issue : issues) {
            String severity = text(issue.getOrDefault("severity", "suggestion"));
            addBodyCell(table, String.valueOf(index++), bodyFont, Element.ALIGN_CENTER);
            addSeverityCell(table, severityText(severity), severity, bodyFont);
            addBodyCell(table, issueLocation(issue), bodyFont, Element.ALIGN_LEFT);
            addBodyCell(table,
                    text(issue.getOrDefault("description", issue.get("message"))),
                    bodyFont,
                    Element.ALIGN_LEFT);
        }
        document.add(table);
    }

    private List<RubricCriterion> rubricCriteria(TAssignment assignment) {
        if (assignment == null || !hasText(assignment.getNormalizedRubricJson())) {
            return List.of();
        }
        try {
            Map<String, Object> rubric = objectMapper.readValue(assignment.getNormalizedRubricJson(), new TypeReference<>() {
            });
            Object dimensionsValue = rubric.get("dimensions");
            if (!(dimensionsValue instanceof List<?> dimensions)) {
                return List.of();
            }
            List<RubricCriterion> criteria = new ArrayList<>();
            for (Object dimensionValue : dimensions) {
                if (!(dimensionValue instanceof Map<?, ?> dimension)) {
                    continue;
                }
                criteria.add(new RubricCriterion(
                        text(dimension.get("name")),
                        rubricCriterionText(dimension)
                ));
            }
            return criteria;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String rubricCriterionText(Map<?, ?> dimension) {
        String criterion = text(dimension.get("criteria")).trim();
        if (hasText(criterion)) {
            return criterion;
        }
        Object itemsValue = dimension.get("items");
        if (!(itemsValue instanceof List<?> items)) {
            return "";
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> text(item.get("criteria")).trim())
                .filter(this::hasText)
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private String criterionFor(Map<String, Object> row,
                                int rowIndex,
                                int scoreRowCount,
                                List<RubricCriterion> rubricCriteria) {
        String rowCriterion = text(row.get("criteria")).trim();
        if (hasText(rowCriterion)) {
            return rowCriterion;
        }
        String scoreName = text(row.get("name")).trim();
        for (RubricCriterion criterion : rubricCriteria) {
            if (criterion.name().equals(scoreName)) {
                return criterion.criterion();
            }
        }
        if (scoreRowCount == rubricCriteria.size() && rowIndex < rubricCriteria.size()) {
            return rubricCriteria.get(rowIndex).criterion();
        }
        return "";
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

    private void addSeverityCell(PdfPTable table, String text, String severity, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(switch (severity == null ? "" : severity.toLowerCase()) {
            case "error" -> new Color(253, 232, 232);
            case "warning" -> new Color(250, 236, 216);
            default -> new Color(230, 240, 255);
        });
        cell.setPadding(5);
        table.addCell(cell);
    }

    private String severityText(String severity) {
        if (severity == null) {
            return "建议";
        }
        return switch (severity.toLowerCase()) {
            case "error" -> "错误";
            case "warning" -> "警告";
            case "suggestion" -> "建议";
            default -> severity;
        };
    }

    private String issueLocation(Map<String, Object> issue) {
        String file = text(issue.getOrDefault("file", "project")).trim();
        if (!hasText(file) || "project".equalsIgnoreCase(file)) {
            return "项目整体";
        }
        String displayFile = compactFilePath(file);
        String line = text(issue.get("line")).trim();
        if (!hasText(line) || "-".equals(line) || "0".equals(line)) {
            return displayFile;
        }
        return displayFile + "\n第 " + line + " 行";
    }

    private String compactFilePath(String file) {
        String normalized = file.replace('\\', '/');
        int lastSeparator = normalized.lastIndexOf('/');
        if (lastSeparator < 0) {
            return normalized;
        }
        int parentSeparator = normalized.lastIndexOf('/', lastSeparator - 1);
        return normalized.substring(parentSeparator + 1);
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

    private record RubricCriterion(String name, String criterion) {
    }

    private record EarnedRow(Map<String, Object> score, String criterion) {
    }
}
