package com.rainexis.backend.service.business;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TRubricTemplateItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/** 兼容旧模板：档位行归为一个评分项，不改动旧 ID 或历史报告。 */
@Service
public class RubricTemplateStructureService {
    private static final Pattern BAND = Pattern.compile("(?m)^\\s*-?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*[~～—至到-]\\s*(\\d+(?:\\.\\d+)?))?\\s*分[：:]\\s*([^\\n]*)");
    private final ObjectMapper mapper;
    public RubricTemplateStructureService(ObjectMapper mapper) { this.mapper = mapper; }

    public List<Map<String, Object>> bands(TRubricTemplateItem item) {
        try {
            if (item.getScoreBandsJson() != null && !item.getScoreBandsJson().isBlank()) {
                return mapper.readValue(item.getScoreBandsJson(), new TypeReference<>() {});
            }
            List<Map<String, Object>> result = new ArrayList<>();
            String criteria = item.getCriteria() == null ? "" : item.getCriteria();
            if (!criteria.contains("评分档位")) return result;
            var matches = BAND.matcher(criteria);
            while (matches.find()) {
                BigDecimal min = new BigDecimal(matches.group(1));
                BigDecimal max = matches.group(2) == null ? min : new BigDecimal(matches.group(2));
                result.add(new LinkedHashMap<>(Map.of("score_min", min, "score_max", max, "criteria", matches.group(3).trim())));
            }
            return result;
        } catch (Exception ex) {
            throw BusinessException.badRequest("评分档位格式不正确");
        }
    }

    public void prepare(TRubricTemplateItem item) {
        String text = (item.getDimensionName() == null ? "" : item.getDimensionName()) + " " + (item.getPointName() == null ? "" : item.getPointName());
        item.setScoringMethod(RubricScoringPolicy.method(item.getScoringMethod(), text));
        if ((text.contains("过程性应用") || text.toLowerCase().contains("ai过程")) && RubricScoringPolicy.AVERAGE.equals(item.getScoringMethod())) {
            throw BusinessException.badRequest("AI 交互记录评分支持仅 AI 或教师评分，不支持源码关键词计分");
        }
        List<Map<String, Object>> bands = bands(item);
        for (Map<String, Object> band : bands) {
            BigDecimal min = new BigDecimal(String.valueOf(band.get("score_min")));
            BigDecimal max = new BigDecimal(String.valueOf(band.get("score_max")));
            if (min.signum() < 0 || min.compareTo(max) > 0 || max.compareTo(item.getPointScore()) > 0) {
                throw BusinessException.badRequest("评分档位必须在该评分项满分范围内");
            }
        }
        try {
            item.setScoreBandsJson(mapper.writeValueAsString(bands));
        } catch (Exception ex) { throw BusinessException.badRequest("评分档位格式不正确"); }
        if (!bands.isEmpty()) {
            String criteria = item.getCriteria() == null ? "" : item.getCriteria();
            int marker = criteria.indexOf("评分档位");
            StringBuilder builder = new StringBuilder(marker < 0 ? criteria : criteria.substring(0, marker));
            builder.append("\n评分档位：");
            for (var band : bands) builder.append("\n- ").append(band.get("score_min")).append("~").append(band.get("score_max")).append("分：").append(band.getOrDefault("criteria", ""));
            item.setCriteria(builder.toString().trim());
        }
    }

    public List<TRubricTemplateItem> normalize(List<TRubricTemplateItem> raw) {
        Map<String, TRubricTemplateItem> groups = new LinkedHashMap<>();
        List<TRubricTemplateItem> result = new ArrayList<>();
        for (TRubricTemplateItem source : raw) {
            TRubricTemplateItem item = new TRubricTemplateItem();
            BeanUtils.copyProperties(source, item);
            item.setLegacyItemIds(item.getId() == null ? new ArrayList<>() : new ArrayList<>(List.of(item.getId())));
            List<Map<String, Object>> bands = bands(item);
            if (!bands.isEmpty()) {
                String baseName = item.getPointName().replaceFirst("[\\d０-９]+$", "").trim();
                String key = item.getDimensionName() + "\u0001" + baseName;
                TRubricTemplateItem prior = groups.get(key);
                if (prior != null) {
                    List<Map<String, Object>> combined = new ArrayList<>(bands(prior));
                    combined.addAll(bands);
                    prior.getLegacyItemIds().addAll(item.getLegacyItemIds());
                    prior.setPointScore(prior.getPointScore().max(item.getPointScore()));
                    try { prior.setScoreBandsJson(mapper.writeValueAsString(combined)); }
                    catch (Exception ex) { throw BusinessException.badRequest("评分档位格式不正确"); }
                    prepare(prior);
                    continue;
                }
                item.setPointName(baseName);
                groups.put(key, item);
            }
            prepare(item);
            result.add(item);
        }
        BigDecimal total = result.stream().map(TRubricTemplateItem::getPointScore).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (var item : result) if (total.signum() > 0) item.setPointRatio(item.getPointScore().multiply(BigDecimal.valueOf(100)).divide(total, 4, java.math.RoundingMode.HALF_UP));
        return result;
    }
}
