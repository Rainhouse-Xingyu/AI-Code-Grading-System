package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TRubricDimensionItem;
import com.rainexis.backend.entity.TRubric;
import com.rainexis.backend.mapper.TRubricDimensionItemMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TRubricMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.AccessControlService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 评分标准 API 控制器。
 * 新流程中评分标准由管理员模板统一发布，教师端不再上传 Word/Excel Rubric。
 */
@RestController
@RequestMapping("/api/v1/rubrics")
public class RubricApiController {
    private final TRubricMapper rubricMapper;
    private final TRubricDimensionItemMapper dimensionItemMapper;
    private final TAssignmentMapper assignmentMapper;
    private final AccessControlService accessControlService;

    public RubricApiController(TRubricMapper rubricMapper,
                               TRubricDimensionItemMapper dimensionItemMapper,
                               TAssignmentMapper assignmentMapper,
                               AccessControlService accessControlService) {
        this.rubricMapper = rubricMapper;
        this.dimensionItemMapper = dimensionItemMapper;
        this.assignmentMapper = assignmentMapper;
        this.accessControlService = accessControlService;
    }

    /** 评分标准由管理员模板统一发布，教师不再上传 Rubric 文件。 */
    @PostMapping
    public ApiResponse<TRubric> upload(@RequestParam Long assignmentId, @RequestParam MultipartFile file) {
        AuthContext.requireTeacher();
        throw BusinessException.forbidden("评分标准由管理员模板统一发布，教师不再上传 Rubric");
    }

    @GetMapping("/active")
    public ApiResponse<TRubric> active(@RequestParam Long assignmentId) {
        AuthContext.requireTeacher();
        TAssignment assignment = accessControlService.requireAssignmentAccess(assignmentId);
        TRubric rubric = rubricMapper.selectOne(new LambdaQueryWrapper<TRubric>()
                .eq(TRubric::getAssignmentId, assignmentId)
                .eq(TRubric::getIsActive, (byte) 1)
                .orderByDesc(TRubric::getRubricVersion)
                .last("limit 1"));
        if (rubric == null && assignment.getNormalizedRubricJson() != null && !assignment.getNormalizedRubricJson().isBlank()) {
            rubric = new TRubric();
            rubric.setAssignmentId(assignmentId);
            rubric.setRubricType("template_normalized");
            rubric.setRubricJson(assignment.getNormalizedRubricJson());
            rubric.setParsedJson(assignment.getNormalizedRubricJson());
            rubric.setVersion(1);
            rubric.setRubricVersion(1);
            rubric.setIsActive((byte) 1);
            rubric.setCreatedAt(assignment.getCreatedAt());
        }
        return ApiResponse.ok(rubric);
    }

    @GetMapping("/{id}/preview")
    public ApiResponse<TRubric> preview(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TRubric rubric = rubricMapper.selectById(id);
        if (rubric == null) {
            throw BusinessException.notFound("评分标准不存在");
        }
        accessControlService.requireAssignmentAccess(rubric.getAssignmentId());
        return ApiResponse.ok(rubric);
    }

    @GetMapping("/{id}/dimension-items")
    public ApiResponse<List<TRubricDimensionItem>> dimensionItems(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TRubric rubric = rubricMapper.selectById(id);
        if (rubric == null) {
            throw BusinessException.notFound("评分标准不存在");
        }
        accessControlService.requireAssignmentAccess(rubric.getAssignmentId());
        return ApiResponse.ok(dimensionItemMapper.selectList(new LambdaQueryWrapper<TRubricDimensionItem>()
                .eq(TRubricDimensionItem::getRubricId, id)
                .orderByAsc(TRubricDimensionItem::getDimensionOrder)
                .orderByAsc(TRubricDimensionItem::getPointOrder)));
    }

    @GetMapping("/template.xlsx")
    public ResponseEntity<byte[]> excelTemplate() {
        AuthContext.requireTeacher();
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("rubric");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("评分维度");
            header.createCell(1).setCellValue("权重");
            header.createCell(2).setCellValue("子项");
            header.createCell(3).setCellValue("子项分值");
            header.createCell(4).setCellValue("评分标准");
            Object[][] rows = {
                    {"代码规范", 30, "变量命名", 10, "驼峰命名，见名知意"},
                    {"代码规范", 30, "缩进格式", 10, "统一 4 空格缩进"},
                    {"代码规范", 30, "注释完整度", 10, "关键逻辑有注释"},
                    {"功能完整性", 40, "测试用例通过", 25, "主要功能正确运行"},
                    {"功能完整性", 40, "异常处理", 15, "覆盖常见异常场景"},
                    {"创新性", 30, "代码设计", 30, "结构清晰，可维护性好"}
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i + 1);
                for (int j = 0; j < rows[i].length; j++) {
                    Object value = rows[i][j];
                    if (value instanceof Number number) {
                        row.createCell(j).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(j).setCellValue(value.toString());
                    }
                }
            }
            for (int i = 0; i < 5; i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return downloadBytes(out.toByteArray(), "Rubric评分标准模板.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        } catch (Exception ex) {
            throw new BusinessException(500, "生成 Rubric Excel 模板失败: " + ex.getMessage());
        }
    }

    @GetMapping("/template.docx")
    public ResponseEntity<byte[]> wordTemplate() {
        AuthContext.requireTeacher();
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            addParagraph(document, "## 代码规范 (30分)");
            addParagraph(document, "- 变量命名规范 (10分)");
            addParagraph(document, "- 缩进格式统一 (10分)");
            addParagraph(document, "- 注释完整度 (10分)");
            addParagraph(document, "");
            addParagraph(document, "## 功能完整性 (40分)");
            addParagraph(document, "- 通过所有测试用例 (25分)");
            addParagraph(document, "- 异常处理 (15分)");
            addParagraph(document, "");
            addParagraph(document, "## 创新性 (30分)");
            addParagraph(document, "- 算法优化 (15分)");
            addParagraph(document, "- 代码设计 (15分)");
            document.write(out);
            return downloadBytes(out.toByteArray(), "Rubric评分标准模板.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } catch (Exception ex) {
            throw new BusinessException(500, "生成 Rubric Word 模板失败: " + ex.getMessage());
        }
    }

    private void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    private ResponseEntity<byte[]> downloadBytes(byte[] bytes, String filename, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(bytes);
    }
}
