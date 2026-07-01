package com.rainexis.backend.service.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 评分标准解析服务
 * 支持从 Word (.doc/.docx) 和 Excel (.xlsx/.xls) 文件中自动解析出结构化的评分维度
 * 解析结果为 JSON 格式：{dimensions: [{name, weight, max_score, criteria, items: [...]}, ...]}
 */
@Service
public class RubricParserService {
    private final ObjectMapper objectMapper;
    private static final String[] EXCEL_HEADERS = {"评分维度", "权重", "子项", "子项分值", "评分标准"};
    private static final Pattern WORD_DIMENSION_HEADING =
            Pattern.compile("(.+?)[(（]\\s*(?:权重[:：])?\\s*(\\d+(?:\\.\\d+)?)\\s*[%分]?[)）]");
    private static final Pattern SCORE_TEXT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分?");

    public RubricParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析上传的评分标准文件，返回结构化JSON */
    public ParsedRubric parse(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "rubric" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            List<Map<String, Object>> dimensions;
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                dimensions = parseExcel(file);
            } else if (lower.endsWith(".docx")) {
                dimensions = parseDocx(file);
            } else if (lower.endsWith(".doc")) {
                dimensions = parseDoc(file);
            } else {
                throw BusinessException.badRequest("评分标准仅支持 .doc、.docx、.xlsx 或 .xls");
            }
            BigDecimal total = dimensions.stream()
                    .map(item -> decimal(item.get("max_score")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> rubric = new LinkedHashMap<>();
            rubric.put("rubric_name", name);
            rubric.put("total_score", total);
            rubric.put("dimensions", dimensions);
            String json = objectMapper.writeValueAsString(rubric);
            return new ParsedRubric(json, json);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.badRequest("评分标准解析失败: " + ex.getMessage());
        }
    }

    /** 从Excel文件中解析评分维度，按行读取：维度名、权重、子项名、子项分数、评分标准 */
    private List<Map<String, Object>> parseExcel(MultipartFile file) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw BusinessException.badRequest("Excel 评分标准格式不符：文件缺少工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            if (hasStandardExcelHeader(sheet.getRow(0), formatter)) {
                return parseStandardExcel(sheet, formatter);
            }
            if (looksLikeHorizontalScoreSheet(sheet, formatter)) {
                return parseHorizontalScoreSheet(sheet, formatter);
            }
            validateExcelHeader(sheet.getRow(0), formatter);
            return List.of();
        }
    }

    private List<Map<String, Object>> parseStandardExcel(Sheet sheet, DataFormatter formatter) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        validateExcelHeader(sheet.getRow(0), formatter);
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            String name = formatter.formatCellValue(row.getCell(0)).trim();
            if (name.isBlank()) {
                continue;
            }
            String weight = formatter.formatCellValue(row.getCell(1)).trim();
            String itemName = formatter.formatCellValue(row.getCell(2)).trim();
            String itemScore = formatter.formatCellValue(row.getCell(3)).trim();
            String criteria = formatter.formatCellValue(row.getCell(4)).trim();
            BigDecimal maxScore = itemScore.isBlank()
                    ? parseNumberStrict(weight, "Excel 第 " + (i + 1) + " 行缺少子项分值或权重")
                    : parseNumberStrict(itemScore, "Excel 第 " + (i + 1) + " 行子项分值格式不正确");
            Map<String, Object> dimension = dimension(
                    name,
                    weight.isBlank() ? maxScore : parseNumberStrict(weight, "Excel 第 " + (i + 1) + " 行权重格式不正确"),
                    maxScore,
                    criteria
            );
            if (!itemName.isBlank()) {
                dimension.put("items", List.of(Map.of("name", itemName, "max_score", maxScore, "criteria", criteria)));
            }
            dimensions.add(dimension);
        }
        if (dimensions.isEmpty()) {
            throw BusinessException.badRequest("Excel 评分标准格式不符：未读取到评分维度");
        }
        return dimensions;
    }

    private List<Map<String, Object>> parseHorizontalScoreSheet(Sheet sheet, DataFormatter formatter) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        int lastColumn = 0;
        for (int rowIndex = 0; rowIndex <= 4; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                lastColumn = Math.max(lastColumn, row.getLastCellNum());
            }
        }
        for (int column = 2; column < lastColumn; column++) {
            String category = mergedCellText(sheet, 1, column, formatter);
            String serial = mergedCellText(sheet, 2, column, formatter);
            String itemName = mergedCellText(sheet, 3, column, formatter);
            String scoreText = mergedCellText(sheet, 4, column, formatter);
            String scoreSource = scoreText.isBlank() ? category : scoreText;
            BigDecimal score = firstScore(scoreSource);
            if (score == null) {
                continue;
            }
            String cleanCategory = cleanScoreText(category);
            if (cleanCategory.contains("总分")) {
                continue;
            }
            String cleanItemName = cleanScoreText(itemName);
            String name = cleanItemName.isBlank() ? cleanCategory : cleanItemName;
            if (!serial.isBlank() && serial.matches("\\d+(?:\\.0)?")) {
                name = serial.replaceAll("\\.0$", "") + ". " + name;
            }
            String criteria = cleanCategory.isBlank() || cleanCategory.equals(cleanItemName)
                    ? name
                    : cleanCategory + " - " + name;
            dimensions.add(dimension(name, score, score, criteria));
        }
        if (dimensions.isEmpty()) {
            throw BusinessException.badRequest("Excel 评分标准格式不符：未读取到评分维度");
        }
        return dimensions;
    }

    /** 从新版Word文件中解析评分维度，通过正则匹配"维度名(权重%)"模式 */
    private List<Map<String, Object>> parseDocx(MultipartFile file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (InputStream input = file.getInputStream(); XWPFDocument document = new XWPFDocument(input)) {
            for (var paragraph : document.getParagraphs()) {
                lines.add(paragraph.getText());
            }
        }
        return parseWordLines(lines);
    }

    /** 从老版Word二进制文件中解析评分维度 */
    private List<Map<String, Object>> parseDoc(MultipartFile file) throws Exception {
        try (InputStream input = file.getInputStream();
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            return parseWordLines(Arrays.asList(extractor.getParagraphText()));
        }
    }

    private List<Map<String, Object>> parseWordLines(List<String> lines) {
        List<Map<String, Object>> schoolFormat = parseSchoolWordRubric(lines);
        if (!schoolFormat.isEmpty()) {
            return schoolFormat;
        }
        return parseHeadingWordRubric(lines);
    }

    private List<Map<String, Object>> parseHeadingWordRubric(List<String> lines) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            for (String text : line.split("\\R")) {
                if (text.isBlank()) {
                    continue;
                }
                Matcher matcher = WORD_DIMENSION_HEADING.matcher(text.trim());
                if (matcher.find()) {
                    BigDecimal score = new BigDecimal(matcher.group(2));
                    dimensions.add(dimension(cleanName(matcher.group(1)), score, score, text.trim()));
                }
            }
        }
        if (dimensions.isEmpty()) {
            throw BusinessException.badRequest("Word 评分标准格式不符：未找到“维度名 (分值)”格式内容");
        }
        return dimensions;
    }

    private List<Map<String, Object>> parseSchoolWordRubric(List<String> rawLines) {
        List<String> lines = normalizeLines(rawLines);
        List<Map<String, Object>> dimensions = parseKnowledgePointRows(lines);
        if (!dimensions.isEmpty()) {
            appendSummaryDimension(lines, dimensions, "系统交互性", BigDecimal.valueOf(15));
            appendSummaryDimension(lines, dimensions, "项目创意性", BigDecimal.TEN);
            appendSummaryDimension(lines, dimensions, "新技术应用", BigDecimal.valueOf(15));
        }
        return dimensions;
    }

    private List<Map<String, Object>> parseKnowledgePointRows(List<String> lines) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if ("序号".equals(lines.get(i)) && containsWithin(lines, i + 1, 5, "知识点/评分点")) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return dimensions;
        }
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("系统交互性、项目创意性")) {
                break;
            }
            if (!line.matches("\\d+")) {
                continue;
            }
            int sequence = Integer.parseInt(line);
            if (sequence < 1 || sequence > 100) {
                continue;
            }
            int nextIndex = nextSequenceIndex(lines, i, sequence);
            int end = nextIndex > 0 ? nextIndex : nextSectionIndex(lines, i + 1);
            List<String> segment = new ArrayList<>();
            for (int j = i + 1; j < end; j++) {
                String value = lines.get(j);
                if (!"知识点/评分点".equals(value) && !"知识点/评分点说明".equals(value) && !"评分".equals(value)) {
                    segment.add(value);
                }
            }
            if (segment.size() < 2) {
                continue;
            }
            BigDecimal score = scoreLine(segment.get(segment.size() - 1));
            if (score == null) {
                continue;
            }
            List<String> content = segment.subList(0, segment.size() - 1);
            if (content.isEmpty()) {
                continue;
            }
            String name = content.size() == 1 ? content.get(0) : content.get(0);
            String criteria = content.size() == 1 ? content.get(0) : String.join("；", content.subList(1, content.size()));
            dimensions.add(dimension(sequence + ". " + cleanName(name), score, score, criteria));
            i = nextIndex > 0 ? nextIndex - 1 : end - 1;
        }
        return dimensions;
    }

    private int nextSequenceIndex(List<String> lines, int currentIndex, int sequence) {
        String nextSequence = String.valueOf(sequence + 1);
        for (int i = currentIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("系统交互性、项目创意性")) {
                break;
            }
            if (nextSequence.equals(line) && i > currentIndex + 1 && scoreLine(lines.get(i - 1)) != null) {
                return i;
            }
        }
        return -1;
    }

    private int nextSectionIndex(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            if (lines.get(i).startsWith("系统交互性、项目创意性")) {
                return i;
            }
        }
        return lines.size();
    }

    private void appendSummaryDimension(List<String> lines, List<Map<String, Object>> dimensions, String name, BigDecimal score) {
        if (dimensions.stream().anyMatch(item -> String.valueOf(item.get("name")).contains(name))) {
            return;
        }
        if (lines.stream().noneMatch(line -> line.contains(name))) {
            return;
        }
        dimensions.add(dimension(name, score, score, summaryCriteria(lines, name)));
    }

    private String summaryCriteria(List<String> lines, String name) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (name.equals(lines.get(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return name;
        }
        List<String> parts = new ArrayList<>();
        for (int i = start; i < Math.min(lines.size(), start + 12); i++) {
            String value = lines.get(i);
            if (i > start && ("系统交互性".equals(value) || "项目创意性".equals(value) || "新技术应用".equals(value))) {
                break;
            }
            if (!value.matches("\\d+(?:-\\d+)?分") && !"优秀".equals(value) && !"良好".equals(value)
                    && !"合格".equals(value) && !"不合格".equals(value)) {
                parts.add(value);
            }
        }
        return String.join("；", parts);
    }

    /** 校验Excel模板表头，避免把格式错误的文件误解析为评分维度 */
    private void validateExcelHeader(Row header, DataFormatter formatter) {
        if (header == null) {
            throw BusinessException.badRequest("Excel 表头缺失：第 1 行必须包含评分维度、权重、子项、子项分值、评分标准");
        }
        for (int i = 0; i < EXCEL_HEADERS.length; i++) {
            String actual = formatter.formatCellValue(header.getCell(i)).trim();
            if (!EXCEL_HEADERS[i].equals(actual)) {
                throw BusinessException.badRequest("Excel 表头缺失或格式不符：第 " + (i + 1) + " 列应为“" + EXCEL_HEADERS[i] + "”");
            }
        }
    }

    private boolean hasStandardExcelHeader(Row header, DataFormatter formatter) {
        if (header == null) {
            return false;
        }
        for (int i = 0; i < EXCEL_HEADERS.length; i++) {
            String actual = formatter.formatCellValue(header.getCell(i)).trim();
            if (!EXCEL_HEADERS[i].equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeHorizontalScoreSheet(Sheet sheet, DataFormatter formatter) {
        String title = mergedCellText(sheet, 0, 0, formatter);
        String firstHeader = mergedCellText(sheet, 1, 0, formatter);
        String secondHeader = mergedCellText(sheet, 1, 1, formatter);
        return title.contains("评分表") && "学号".equals(firstHeader) && "姓名".equals(secondHeader);
    }

    private String mergedCellText(Sheet sheet, int rowIndex, int columnIndex, DataFormatter formatter) {
        Row row = sheet.getRow(rowIndex);
        String value = row == null ? "" : formatter.formatCellValue(row.getCell(columnIndex)).trim();
        if (!value.isBlank()) {
            return value;
        }
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            if (range.isInRange(rowIndex, columnIndex)) {
                Row firstRow = sheet.getRow(range.getFirstRow());
                return firstRow == null ? "" : formatter.formatCellValue(firstRow.getCell(range.getFirstColumn())).trim();
            }
        }
        return "";
    }

    /** 构建评分维度Map */
    private Map<String, Object> dimension(String name, BigDecimal weight, BigDecimal maxScore, String criteria) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("weight", weight);
        value.put("max_score", maxScore);
        value.put("criteria", criteria == null ? "" : criteria);
        value.put("items", List.of(Map.of("name", name, "max_score", maxScore, "criteria", criteria == null ? "" : criteria)));
        return value;
    }

    private BigDecimal parseNumberStrict(String value, String message) {
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest(message);
        }
        Matcher matcher = SCORE_TEXT.matcher(value);
        if (!matcher.find()) {
            throw BusinessException.badRequest(message);
        }
        return new BigDecimal(matcher.group(1));
    }

    private BigDecimal firstScore(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = SCORE_TEXT.matcher(value);
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private BigDecimal scoreLine(String value) {
        return value == null || !value.matches("\\d+(?:\\.\\d+)?") ? null : new BigDecimal(value);
    }

    private String cleanScoreText(String value) {
        return value == null ? "" : value.replaceAll("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]", "").trim();
    }

    private List<String> normalizeLines(List<String> rawLines) {
        List<String> lines = new ArrayList<>();
        for (String line : rawLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String normalized = line
                    .replace('\u0007', '\n')
                    .replace('\u2028', '\n');
            for (String part : normalized.split("\\R")) {
                String text = part.trim();
                if (!text.isBlank()) {
                    lines.add(text);
                }
            }
        }
        return lines;
    }

    private boolean containsWithin(List<String> lines, int start, int length, String value) {
        for (int i = start; i < Math.min(lines.size(), start + length); i++) {
            if (value.equals(lines.get(i))) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private String cleanName(String value) {
        return value.replaceAll("^#+", "").replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "").trim();
    }

    /** 解析后的评分标准结果 */
    public record ParsedRubric(String rubricJson, String parsedJson) {
    }
}
