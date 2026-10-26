export function scoringMethodLabel(value) {
  return ({ ai_rule_average: "AI＋规则平均", ai_only: "仅 AI", manual: "教师评分" })[value] || "按评分标准";
}
