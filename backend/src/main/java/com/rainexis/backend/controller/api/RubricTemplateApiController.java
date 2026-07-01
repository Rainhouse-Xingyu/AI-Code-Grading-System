package com.rainexis.backend.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TRubricTemplate;
import com.rainexis.backend.entity.TRubricTemplateItem;
import com.rainexis.backend.mapper.TRubricTemplateItemMapper;
import com.rainexis.backend.mapper.TRubricTemplateMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.RubricParserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/rubric-templates")
public class RubricTemplateApiController {
    private final TRubricTemplateMapper templateMapper;
    private final TRubricTemplateItemMapper itemMapper;
    private final RubricParserService rubricParserService;
    private final ObjectMapper objectMapper;

    public RubricTemplateApiController(TRubricTemplateMapper templateMapper,
                                       TRubricTemplateItemMapper itemMapper,
                                       RubricParserService rubricParserService,
                                       ObjectMapper objectMapper) {
        this.templateMapper = templateMapper;
        this.itemMapper = itemMapper;
        this.rubricParserService = rubricParserService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        AuthContext.requireTeacher();
        boolean admin = "admin".equals(AuthContext.get().role());
        LambdaQueryWrapper<TRubricTemplate> query = new LambdaQueryWrapper<TRubricTemplate>()
                .orderByDesc(TRubricTemplate::getCreatedAt);
        if (!admin) {
            query.eq(TRubricTemplate::getEnabled, (byte) 1);
        }
        return ApiResponse.ok(templateMapper.selectList(query).stream().map(this::payload).toList());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody TemplateRequest request) {
        AuthContext.requireAdmin();
        if (request.templateName() == null || request.templateName().isBlank()) {
            throw BusinessException.badRequest("模板名称不能为空");
        }
        TRubricTemplate template = new TRubricTemplate();
        template.setTemplateName(request.templateName());
        template.setDescription(request.description());
        template.setEnabled(request.enabled() == null || request.enabled() ? (byte) 1 : (byte) 0);
        template.setCreatedBy(AuthContext.get().id());
        template.setUpdatedBy(AuthContext.get().id());
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(template);
        replaceItems(template.getId(), request.items());
        return ApiResponse.ok(payload(template));
    }

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam MultipartFile file,
                                                   @RequestParam(required = false) String templateName,
                                                   @RequestParam(defaultValue = "true") boolean enabled) {
        AuthContext.requireAdmin();
        RubricParserService.ParsedRubric parsed = rubricParserService.parse(file);
        TemplateRequest request = new TemplateRequest(
                firstText(templateName, cleanFilename(file.getOriginalFilename()), "评分模板"),
                "由文件导入：" + (file.getOriginalFilename() == null ? "评分标准文件" : file.getOriginalFilename()),
                enabled,
                parsedItems(parsed)
        );
        return create(request);
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody TemplateRequest request) {
        AuthContext.requireAdmin();
        TRubricTemplate template = requiredTemplate(id);
        template.setTemplateName(request.templateName());
        template.setDescription(request.description());
        template.setEnabled(request.enabled() == null || request.enabled() ? (byte) 1 : (byte) 0);
        template.setUpdatedBy(AuthContext.get().id());
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);
        replaceItems(id, request.items());
        return ApiResponse.ok(payload(template));
    }

    @PatchMapping("/{id}/enabled")
    public ApiResponse<TRubricTemplate> enabled(@PathVariable Long id, @RequestParam boolean enabled) {
        AuthContext.requireAdmin();
        TRubricTemplate template = requiredTemplate(id);
        template.setEnabled(enabled ? (byte) 1 : (byte) 0);
        template.setUpdatedBy(AuthContext.get().id());
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);
        return ApiResponse.ok(template);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AuthContext.requireAdmin();
        requiredTemplate(id);
        itemMapper.delete(new LambdaQueryWrapper<TRubricTemplateItem>().eq(TRubricTemplateItem::getTemplateId, id));
        templateMapper.deleteById(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<TRubricTemplateItem>> items(@PathVariable Long id) {
        AuthContext.requireTeacher();
        TRubricTemplate template = requiredTemplate(id);
        if (!"admin".equals(AuthContext.get().role()) && !Byte.valueOf((byte) 1).equals(template.getEnabled())) {
            throw BusinessException.forbidden("模板已停用");
        }
        return ApiResponse.ok(templateItems(id));
    }

    private TRubricTemplate requiredTemplate(Long id) {
        TRubricTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw BusinessException.notFound("评分模板不存在");
        }
        return template;
    }

    private void replaceItems(Long templateId, List<ItemRequest> items) {
        itemMapper.delete(new LambdaQueryWrapper<TRubricTemplateItem>().eq(TRubricTemplateItem::getTemplateId, templateId));
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("评分模板至少需要一个评分点");
        }
        BigDecimal total = items.stream()
                .map(item -> item.pointScore() == null ? BigDecimal.ZERO : item.pointScore())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("评分点总分必须大于 0");
        }
        int index = 1;
        for (ItemRequest item : items) {
            if (item.pointScore() == null || item.pointScore().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("评分点分数必须大于 0");
            }
            TRubricTemplateItem record = new TRubricTemplateItem();
            record.setTemplateId(templateId);
            record.setDimensionOrder(item.dimensionOrder() == null ? index : item.dimensionOrder());
            record.setDimensionName(firstText(item.dimensionName(), item.pointName(), "评分维度"));
            record.setPointOrder(item.pointOrder() == null ? 1 : item.pointOrder());
            record.setPointName(firstText(item.pointName(), item.dimensionName(), "评分点"));
            record.setPointScore(item.pointScore());
            record.setPointRatio(item.pointScore().multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP));
            record.setCriteria(item.criteria());
            record.setEnabled(item.enabled() == null || item.enabled() ? (byte) 1 : (byte) 0);
            record.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(record);
            index++;
        }
    }

    private Map<String, Object> payload(TRubricTemplate template) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", template.getId());
        payload.put("templateName", template.getTemplateName());
        payload.put("description", template.getDescription());
        payload.put("enabled", Byte.valueOf((byte) 1).equals(template.getEnabled()));
        payload.put("items", templateItems(template.getId()));
        payload.put("createdAt", template.getCreatedAt());
        payload.put("updatedAt", template.getUpdatedAt());
        return payload;
    }

    private List<ItemRequest> parsedItems(RubricParserService.ParsedRubric parsed) {
        try {
            JsonNode dimensions = objectMapper.readTree(parsed.rubricJson()).path("dimensions");
            if (!dimensions.isArray() || dimensions.isEmpty()) {
                throw BusinessException.badRequest("评分标准文件未解析出评分点");
            }
            List<ItemRequest> items = new ArrayList<>();
            int dimensionOrder = 1;
            for (JsonNode dimension : dimensions) {
                String dimensionName = firstText(dimension.path("name").asText(), "评分维度", "评分维度");
                BigDecimal dimensionScore = decimalNode(dimension.path("max_score"), "评分点分数格式不正确");
                String dimensionCriteria = dimension.path("criteria").asText("");
                JsonNode rubricItems = dimension.path("items");
                if (rubricItems.isArray() && !rubricItems.isEmpty()) {
                    int pointOrder = 1;
                    for (JsonNode item : rubricItems) {
                        BigDecimal pointScore = item.hasNonNull("max_score")
                                ? decimalNode(item.path("max_score"), "评分点分数格式不正确")
                                : dimensionScore;
                        items.add(new ItemRequest(
                                dimensionOrder,
                                dimensionName,
                                pointOrder++,
                                firstText(item.path("name").asText(), dimensionName, "评分点"),
                                pointScore,
                                firstText(item.path("criteria").asText(), dimensionCriteria, ""),
                                true
                        ));
                    }
                } else {
                    items.add(new ItemRequest(dimensionOrder, dimensionName, 1, dimensionName, dimensionScore, dimensionCriteria, true));
                }
                dimensionOrder++;
            }
            return items;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.badRequest("评分标准文件解析为模板失败: " + ex.getMessage());
        }
    }

    private BigDecimal decimalNode(JsonNode node, String message) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            throw BusinessException.badRequest(message);
        }
        return new BigDecimal(node.asText());
    }

    private List<TRubricTemplateItem> templateItems(Long templateId) {
        return itemMapper.selectList(new LambdaQueryWrapper<TRubricTemplateItem>()
                .eq(TRubricTemplateItem::getTemplateId, templateId)
                .orderByAsc(TRubricTemplateItem::getDimensionOrder)
                .orderByAsc(TRubricTemplateItem::getPointOrder)
                .orderByAsc(TRubricTemplateItem::getId));
    }

    private String firstText(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return fallback;
    }

    private String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "评分模板";
        }
        return filename.replaceAll("(?i)\\.(docx?|xlsx?)$", "").trim();
    }

    public record TemplateRequest(String templateName, String description, Boolean enabled, List<ItemRequest> items) {
    }

    public record ItemRequest(Integer dimensionOrder, String dimensionName, Integer pointOrder, String pointName,
                              BigDecimal pointScore, String criteria, Boolean enabled) {
    }
}
