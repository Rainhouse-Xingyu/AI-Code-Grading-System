package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TGradePublish;
import com.rainexis.backend.entity.TRubric;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TTeacherReview;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TGradePublishMapper;
import com.rainexis.backend.mapper.TRubricMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TTeacherReviewMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ScoreSheetExportService {
    private final TUserMapper userMapper;
    private final TSubmissionMapper submissionMapper;
    private final TTeacherReviewMapper reviewMapper;
    private final TAiReportMapper reportMapper;
    private final TGradePublishMapper publishMapper;
    private final TRubricMapper rubricMapper;
    private final AssignmentClassService assignmentClassService;
    private final ObjectMapper objectMapper;

    public ScoreSheetExportService(TUserMapper userMapper,
                                   TSubmissionMapper submissionMapper,
                                   TTeacherReviewMapper reviewMapper,
                                   TAiReportMapper reportMapper,
                                   TGradePublishMapper publishMapper,
                                   TRubricMapper rubricMapper,
                                   AssignmentClassService assignmentClassService,
                                   ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.submissionMapper = submissionMapper;
        this.reviewMapper = reviewMapper;
        this.reportMapper = reportMapper;
        this.publishMapper = publishMapper;
        this.rubricMapper = rubricMapper;
        this.assignmentClassService = assignmentClassService;
        this.objectMapper = objectMapper;
    }

    public ExcelFile exportAssignment(TAssignment assignment) {
        List<TUser> students = visibleStudents(assignment);
        List<ScoreRow> rows = students.stream()
                .map(student -> scoreRow(assignment, student))
                .sorted(Comparator.comparing((ScoreRow row) -> text(row.className()))
                        .thenComparing(row -> text(row.username())))
                .toList();
        List<String> dimensions = dimensionColumns(assignment, rows);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("成绩汇总");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);
            writeHeader(sheet, headerStyle, dimensions);
            int rowIndex = 1;
            for (ScoreRow scoreRow : rows) {
                writeRow(sheet.createRow(rowIndex++), scoreRow, dimensions, numberStyle);
            }
            for (int i = 0; i < 10 + dimensions.size(); i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.min(Math.max(sheet.getColumnWidth(i), 2800), 9000));
            }
            workbook.write(output);
            return new ExcelFile(filename(assignment), output.toByteArray());
        } catch (Exception ex) {
            throw new BusinessException(500, "成绩表导出失败: " + ex.getMessage());
        }
    }

    private List<TUser> visibleStudents(TAssignment assignment) {
        List<String> visibleClasses = assignmentClassService.visibleClassNames(assignment, AuthContext.get());
        LambdaQueryWrapper<TUser> query = new LambdaQueryWrapper<TUser>()
                .eq(TUser::getRole, "student")
                .orderByAsc(TUser::getClassName)
                .orderByAsc(TUser::getUsername);
        if (!visibleClasses.isEmpty()) {
            query.in(TUser::getClassName, visibleClasses);
        } else if (!"admin".equals(AuthContext.get().role())) {
            query.eq(TUser::getId, -1);
        }
        return userMapper.selectList(query);
    }

    private ScoreRow scoreRow(TAssignment assignment, TUser student) {
        TSubmission submission = submissionMapper.selectOne(new LambdaQueryWrapper<TSubmission>()
                .eq(TSubmission::getAssignmentId, assignment.getId())
                .eq(TSubmission::getStudentId, student.getId())
                .eq(TSubmission::getCurrent, true)
                .orderByDesc(TSubmission::getUploadTime)
                .last("limit 1"));
        if (submission == null) {
            return new ScoreRow(student.getClassName(), student.getUsername(), student.getRealName(),
                    "未提交", false, null, "", Map.of());
        }
        TTeacherReview review = latestReview(submission.getId());
        TAiReport report = latestReport(submission.getId());
        TGradePublish publish = latestPublish(submission.getId());
        Map<String, BigDecimal> scores = dimensionScores(review, report);
        BigDecimal totalScore = publish != null && publish.getFinalScore() != null
                ? publish.getFinalScore()
                : review != null && review.getFinalScore() != null
                ? review.getFinalScore()
                : report == null ? submission.getCurrentScore() : report.getTotalScore();
        return new ScoreRow(student.getClassName(), student.getUsername(), student.getRealName(),
                submission.getStatus(), Boolean.TRUE.equals(submission.getLate()),
                totalScore, publishStatusText(publish), scores);
    }

    private List<String> dimensionColumns(TAssignment assignment, List<ScoreRow> rows) {
        Set<String> names = new LinkedHashSet<>(rubricDimensionNames(assignment));
        for (ScoreRow row : rows) {
            names.addAll(row.dimensionScores().keySet());
        }
        return List.copyOf(names);
    }

    private List<String> rubricDimensionNames(TAssignment assignment) {
        String rubricJson = null;
        TRubric rubric = rubricMapper.selectOne(new LambdaQueryWrapper<TRubric>()
                .eq(TRubric::getAssignmentId, assignment.getId())
                .eq(TRubric::getIsActive, (byte) 1)
                .orderByDesc(TRubric::getRubricVersion)
                .last("limit 1"));
        if (rubric != null && rubric.getRubricJson() != null && !rubric.getRubricJson().isBlank()) {
            rubricJson = rubric.getRubricJson();
        } else if (assignment.getNormalizedRubricJson() != null && !assignment.getNormalizedRubricJson().isBlank()) {
            rubricJson = assignment.getNormalizedRubricJson();
        }
        if (rubricJson == null) {
            return List.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(rubricJson, new TypeReference<>() {
            });
            Object dimensions = root.get("dimensions");
            if (!(dimensions instanceof List<?> values)) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> dimension && dimension.get("name") != null) {
                    names.add(String.valueOf(dimension.get("name")));
                }
            }
            return names;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, BigDecimal> dimensionScores(TTeacherReview review, TAiReport report) {
        Map<String, BigDecimal> scores = parseDimensionScores(review == null ? null : review.getModifiedJson());
        if (!scores.isEmpty()) {
            return scores;
        }
        scores = parseDimensionScores(report == null ? null : report.getScoreDetailJson());
        if (!scores.isEmpty()) {
            return scores;
        }
        return parseDimensionScores(report == null ? null : report.getScoreJson());
    }

    private Map<String, BigDecimal> parseDimensionScores(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            if (!(parsed instanceof List<?> dimensions)) {
                return Map.of();
            }
            Map<String, BigDecimal> scores = new LinkedHashMap<>();
            for (Object item : dimensions) {
                if (!(item instanceof Map<?, ?> dimension)) {
                    continue;
                }
                Object name = dimension.get("name");
                Object score = dimension.get("score");
                if (name == null || score == null) {
                    continue;
                }
                scores.put(String.valueOf(name), decimal(score));
            }
            return scores;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private TTeacherReview latestReview(Long submissionId) {
        return reviewMapper.selectOne(new LambdaQueryWrapper<TTeacherReview>()
                .eq(TTeacherReview::getSubmissionId, submissionId)
                .orderByDesc(TTeacherReview::getCreatedAt)
                .orderByDesc(TTeacherReview::getId)
                .last("limit 1"));
    }

    private TAiReport latestReport(Long submissionId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<TAiReport>()
                .eq(TAiReport::getSubmissionId, submissionId)
                .orderByDesc(TAiReport::getCreatedAt)
                .last("limit 1"));
    }

    private TGradePublish latestPublish(Long submissionId) {
        return publishMapper.selectOne(new LambdaQueryWrapper<TGradePublish>()
                .eq(TGradePublish::getSubmissionId, submissionId)
                .orderByDesc(TGradePublish::getPublishedAt)
                .last("limit 1"));
    }

    private void writeHeader(Sheet sheet, CellStyle headerStyle, List<String> dimensions) {
        Row header = sheet.createRow(0);
        List<String> fixed = List.of("班级", "学号", "姓名", "提交状态", "是否迟交", "发布状态");
        int column = 0;
        for (String title : fixed) {
            cell(header, column++, title, headerStyle);
        }
        for (String dimension : dimensions) {
            cell(header, column++, dimension, headerStyle);
        }
        cell(header, column, "总分", headerStyle);
    }

    private void writeRow(Row row, ScoreRow scoreRow, List<String> dimensions, CellStyle numberStyle) {
        int column = 0;
        cell(row, column++, scoreRow.className(), null);
        cell(row, column++, scoreRow.username(), null);
        cell(row, column++, scoreRow.realName(), null);
        cell(row, column++, submissionStatusText(scoreRow.status()), null);
        cell(row, column++, scoreRow.late() ? "是" : "否", null);
        cell(row, column++, scoreRow.publishStatus(), null);
        for (String dimension : dimensions) {
            BigDecimal score = scoreRow.dimensionScores().get(dimension);
            if (score == null) {
                row.createCell(column++);
            } else {
                numericCell(row, column++, score, numberStyle);
            }
        }
        if (scoreRow.totalScore() != null) {
            numericCell(row, column, scoreRow.totalScore(), numberStyle);
        }
    }

    private Cell cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return cell;
    }

    private void numericCell(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle numberStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
        return style;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String publishStatusText(TGradePublish publish) {
        if (publish == null) {
            return "未发布";
        }
        if (publish.getIsPublished() != null && publish.getIsPublished() == 1) {
            return "已发布";
        }
        if (publish.getIsPublished() != null && publish.getIsPublished() == 2) {
            return "已撤回";
        }
        return "未发布";
    }

    private String submissionStatusText(String status) {
        return switch (status == null ? "" : status) {
            case "published" -> "已发布";
            case "reviewed" -> "已复核";
            case "scored" -> "已评分";
            case "scoring" -> "评分中";
            case "parsed" -> "已解析";
            case "uploaded" -> "已上传";
            case "parse_failed" -> "解析失败";
            case "failed" -> "失败";
            default -> status == null || status.isBlank() ? "未知" : status;
        };
    }

    private String filename(TAssignment assignment) {
        return safePart(assignment.getTitle()) + "_成绩表_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
    }

    private String safePart(String value) {
        return value == null || value.isBlank()
                ? "assignment"
                : value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record ExcelFile(String filename, byte[] bytes) {
    }

    private record ScoreRow(String className,
                            String username,
                            String realName,
                            String status,
                            boolean late,
                            BigDecimal totalScore,
                            String publishStatus,
                            Map<String, BigDecimal> dimensionScores) {
    }
}
