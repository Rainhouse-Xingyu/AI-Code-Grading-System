package com.rainexis.backend.service.business;

import com.rainexis.backend.common.BusinessException;
import java.util.Map;
import java.util.Set;

public final class RubricScoringPolicy {
    public static final String AVERAGE = "ai_rule_average";
    public static final String AI = "ai_only";
    public static final String MANUAL = "manual";
    private RubricScoringPolicy() {}

    public static String method(String explicit, String text) {
        if (explicit != null && !explicit.isBlank()) {
            if (!Set.of(AVERAGE, AI, MANUAL).contains(explicit)) {
                throw BusinessException.badRequest("评分方式无效");
            }
            return explicit;
        }
        if (text.contains("系统交互")) return MANUAL;
        if (text.toLowerCase().contains("ai过程") || text.contains("过程性应用") || text.contains("新技术") || text.contains("创新")) return AI;
        return AVERAGE;
    }

    public static String method(Map<String, Object> dimension) {
        String text = String.valueOf(dimension.getOrDefault("name", ""));
        String explicit = dimension.get("scoring_method") instanceof String value ? value : "";
        if (explicit.isBlank() && Boolean.TRUE.equals(dimension.get("manual_review_required"))) return MANUAL;
        return method(explicit, text);
    }
}
