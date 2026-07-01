package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TRubricDimensionItem;
import com.rainexis.backend.entity.TRubricTemplateItem;
import com.rainexis.backend.mapper.TRubricDimensionItemMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RubricDimensionItemService {
    private final TRubricDimensionItemMapper itemMapper;
    private final ObjectMapper objectMapper;

    public RubricDimensionItemService(TRubricDimensionItemMapper itemMapper, ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.objectMapper = objectMapper;
    }

    public void replaceItems(Long rubricId, Long assignmentId, String rubricJson) {
        itemMapper.delete(new LambdaQueryWrapper<TRubricDimensionItem>().eq(TRubricDimensionItem::getRubricId, rubricId));
        try {
            Map<String, Object> rubric = objectMapper.readValue(rubricJson, new TypeReference<>() {
            });
            List<Map<String, Object>> dimensions = (List<Map<String, Object>>) rubric.getOrDefault("dimensions", List.of());
            BigDecimal total = dimensions.stream()
                    .flatMap(dimension -> itemsOf(dimension).stream().map(item -> scoreOf(item, dimension)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }
            int dimensionOrder = 1;
            for (Map<String, Object> dimension : dimensions) {
                String dimensionName = text(dimension.get("name"), "评分维度");
                int pointOrder = 1;
                for (Map<String, Object> item : itemsOf(dimension)) {
                    BigDecimal score = scoreOf(item, dimension);
                    TRubricDimensionItem record = new TRubricDimensionItem();
                    record.setRubricId(rubricId);
                    record.setAssignmentId(assignmentId);
                    record.setDimensionOrder(dimensionOrder);
                    record.setDimensionName(dimensionName);
                    record.setPointOrder(pointOrder);
                    record.setPointName(text(item.get("name"), dimensionName));
                    record.setPointScore(score);
                    record.setPointRatio(score.multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP));
                    record.setCriteria(text(item.get("criteria"), text(dimension.get("criteria"), "")));
                    record.setCreatedAt(LocalDateTime.now());
                    itemMapper.insert(record);
                    pointOrder++;
                }
                dimensionOrder++;
            }
        } catch (Exception ex) {
            throw new BusinessException(500, "评分标准明细入库失败: " + ex.getMessage());
        }
    }

    public void replaceAssignmentTemplateItems(Long assignmentId, List<TRubricTemplateItem> selectedItems) {
        clearAssignmentTemplateItems(assignmentId);
        if (selectedItems == null || selectedItems.isEmpty()) {
            return;
        }
        insertAssignmentTemplateItems(assignmentId, selectedItems);
    }

    public void clearAssignmentTemplateItems(Long assignmentId) {
        itemMapper.delete(new LambdaQueryWrapper<TRubricDimensionItem>()
                .eq(TRubricDimensionItem::getAssignmentId, assignmentId)
                .isNull(TRubricDimensionItem::getRubricId));
    }

    private void insertAssignmentTemplateItems(Long assignmentId, List<TRubricTemplateItem> selectedItems) {
        BigDecimal total = selectedItems.stream()
                .map(TRubricTemplateItem::getPointScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("勾选评分点原始总分必须大于 0");
        }
        BigDecimal normalizedSum = BigDecimal.ZERO;
        for (int i = 0; i < selectedItems.size(); i++) {
            TRubricTemplateItem item = selectedItems.get(i);
            BigDecimal normalizedScore = i == selectedItems.size() - 1
                    ? BigDecimal.valueOf(100).subtract(normalizedSum)
                    : item.getPointScore().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            normalizedSum = normalizedSum.add(normalizedScore);
            TRubricDimensionItem record = new TRubricDimensionItem();
            record.setRubricId(null);
            record.setAssignmentId(assignmentId);
            record.setDimensionOrder(item.getDimensionOrder());
            record.setDimensionName(item.getDimensionName());
            record.setPointOrder(item.getPointOrder());
            record.setPointName(item.getPointName());
            record.setPointScore(normalizedScore);
            record.setPointRatio(normalizedScore);
            record.setCriteria(item.getCriteria());
            record.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(record);
        }
    }

    private List<Map<String, Object>> itemsOf(Map<String, Object> dimension) {
        Object items = dimension.get("items");
        if (items instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of(dimension);
    }

    private BigDecimal scoreOf(Map<String, Object> item, Map<String, Object> dimension) {
        Object value = item.getOrDefault("max_score", item.get("score"));
        if (value == null) {
            value = dimension.getOrDefault("max_score", dimension.getOrDefault("weight", 0));
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private String text(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
