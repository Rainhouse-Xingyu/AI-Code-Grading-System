<template>
  <section class="teacher-module-panel rubric-template-page-panel">
    <header class="module-intro rubric-module-intro">
      <div class="module-intro-copy">
        <span class="module-kicker">评分标准</span>
        <h3>评分模板管理</h3>
        <p>统一维护评分维度、评分点与分值，创建作业时可直接复用。</p>
      </div>
      <div class="module-summary-grid rubric-overview-grid" aria-label="评分模板概览">
        <MetricCard label="全部模板" :value="templateOverview.total" />
        <MetricCard label="已启用" :value="templateOverview.enabled" />
        <MetricCard label="当前评分点" :value="templateOverview.items" />
      </div>
    </header>

    <el-card shadow="never" class="rubric-workspace-card data-panel">
      <template #header>
        <div class="panel-toolbar rubric-workspace-toolbar">
          <div class="panel-toolbar-main">
            <span class="panel-eyebrow">模板工作区</span>
            <strong>{{ templateForm.id ? "编辑评分模板" : "创建评分模板" }}</strong>
            <small>左侧选择模板，右侧维护模板信息和评分点。</small>
          </div>
          <div class="panel-toolbar-actions rubric-toolbar-actions">
            <a :href="rubricTemplateDownloadUrl" download="评分模板.xlsx" class="template-download-link">
              <el-button>下载模板</el-button>
            </a>
            <el-upload
              :show-file-list="false"
              :auto-upload="false"
              :on-change="(file) => $emit('upload-template', file)"
              accept=".doc,.docx,.xls,.xlsx"
            >
              <el-button :loading="templateImporting">导入 Word/Excel</el-button>
            </el-upload>
            <el-button @click="$emit('reset-template-form')">新建模板</el-button>
            <el-button type="primary" :loading="templateSaving" @click="$emit('save-template')">保存模板</el-button>
          </div>
        </div>
      </template>
      <div class="template-admin-grid">
        <aside class="template-list-pane">
          <div class="template-pane-heading">
            <div>
              <strong>模板列表</strong>
              <span>共 {{ rubricTemplates.length }} 个模板</span>
            </div>
            <el-tag effect="plain">{{ templateOverview.enabled }} 个启用</el-tag>
          </div>
          <div class="template-list-table-wrap">
            <el-table
              :data="rubricTemplates"
              height="520"
              highlight-current-row
              :row-class-name="templateRowClassName"
              @current-change="$emit('edit-template', $event)"
            >
              <el-table-column prop="templateName" label="模板" min-width="150" show-overflow-tooltip />
              <el-table-column label="状态" width="104">
                <template #default="{ row }">
                  <div class="template-status-control">
                    <el-switch v-model="row.enabled" @change="$emit('toggle-template', row)" />
                    <span>{{ isEnabled(row.enabled) ? "启用" : "停用" }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="64" align="right">
                <template #default="{ row }">
                  <el-button link type="danger" @click.stop="$emit('delete-template', row)">删除</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty class="empty-panel template-list-empty" description="还没有评分模板">
                  <el-button type="primary" plain @click="$emit('reset-template-form')">新建模板</el-button>
                </el-empty>
              </template>
            </el-table>
          </div>
        </aside>

        <div class="template-editor">
          <section class="template-editor-section template-meta-section">
            <div class="template-section-heading">
              <div>
                <strong>模板信息</strong>
                <span>设置易于识别的名称和使用说明</span>
              </div>
              <div class="template-editor-status">
                <span>模板状态</span>
                <el-switch v-model="templateForm.enabled" inline-prompt active-text="开" inactive-text="关" />
              </div>
            </div>
            <el-form label-position="top" class="template-meta-form">
              <el-form-item label="模板名称">
                <el-input v-model="templateForm.templateName" placeholder="例如：Java 程序设计综合评分" />
              </el-form-item>
              <el-form-item label="说明">
                <el-input v-model="templateForm.description" placeholder="说明模板的适用课程或评分重点" />
              </el-form-item>
            </el-form>
          </section>

          <section class="template-editor-section template-items-section">
            <div class="template-item-toolbar">
              <div>
                <strong>评分点</strong>
                <span>逐项设置评分维度、标准与分值</span>
              </div>
              <div class="template-item-actions">
                <el-tag effect="plain">{{ templateForm.items?.length || 0 }} 项</el-tag>
                <el-tag :type="templateScoreTotal === 100 ? 'success' : 'warning'" effect="light">合计 {{ formatScore(templateScoreTotal) }} 分</el-tag>
                <el-button type="primary" plain @click="$emit('add-template-item')">添加评分点</el-button>
              </div>
            </div>
            <div class="template-item-table-wrap">
              <el-alert title="模板文件格式不变。评分点分别计分；档位只是同一评分点的标准，不重复累计满分。" type="info" :closable="false" />
              <el-collapse>
              <el-collapse-item v-for="group in templateGroups" :key="group.name" :name="group.name" :title="`${group.name} · ${group.items.length} 个评分点 · 满分 ${formatScore(group.total)} 分`">
              <el-collapse class="rubric-point-tree">
                <el-collapse-item v-for="row in group.items" :key="templateForm.items.indexOf(row)" :title="`${row.pointName || '未命名评分点'} · ${row.pointScore} 分 · ${scoringMethodLabel(row.scoringMethod)}`">
                  <div class="rubric-point-card">
                    <el-form label-position="top" class="rubric-point-fields">
                      <el-form-item label="所属维度"><el-input :model-value="row.dimensionName" @change="row.dimensionName = $event" /></el-form-item>
                      <el-form-item label="评分点"><el-input v-model="row.pointName" /></el-form-item>
                      <el-form-item label="满分"><el-input-number v-model="row.pointScore" :min="0.1" :precision="2" controls-position="right" /></el-form-item>
                      <el-form-item label="评分方式">
                        <el-select v-model="row.scoringMethod" placeholder="自动识别">
                          <el-option label="AI＋规则取平均" value="ai_rule_average" :disabled="`${row.dimensionName} ${row.pointName}`.includes('过程性应用')" />
                          <el-option label="仅 AI 评分" value="ai_only" />
                          <el-option label="教师评分" value="manual" />
                        </el-select>
                      </el-form-item>
                      <el-form-item label="显示顺序"><el-input-number v-model="row.dimensionOrder" :min="1" controls-position="right" /></el-form-item>
                    </el-form>
                    <div class="rubric-point-actions"><el-switch v-model="row.enabled" active-text="启用评分点" /><el-button link type="danger" @click="$emit('remove-template-item', templateForm.items.indexOf(row))">删除评分点</el-button></div>
                    <el-input v-if="!row.scoreBands?.length" v-model="row.criteria" type="textarea" :rows="3" placeholder="描述得分标准" />
                    <details v-else class="rubric-bands">
                      <summary>{{ row.scoreBands.length }} 个评分档位（展开查看，不单独计分）</summary>
                      <div v-for="(band, index) in row.scoreBands" :key="index" class="rubric-band-row">
                        <div class="rubric-band-range"><el-input-number v-model="band.score_min" :min="0" :max="row.pointScore" controls-position="right" size="small" /><span>～</span><el-input-number v-model="band.score_max" :min="0" :max="row.pointScore" controls-position="right" size="small" /><span>分</span></div>
                        <el-input v-model="band.criteria" type="textarea" :rows="3" placeholder="这一档次的标准" />
                        <el-button link type="danger" @click="removeBand(row, index)">删除档位</el-button>
                      </div>
                    </details>
                    <el-button class="rubric-add-band" size="small" @click="addBand(row)">添加档位说明</el-button>
                  </div>
                </el-collapse-item>
              </el-collapse>
              </el-collapse-item>
              </el-collapse>
            </div>
          </section>
        </div>
      </div>
    </el-card>
  </section>
</template>

<script setup>
import { computed } from "vue";
import MetricCard from "../MetricCard.vue";
import { scoringMethodLabel } from "../../JS/rubric.js";

const rubricTemplateDownloadUrl = `${import.meta.env.BASE_URL}rubric-templates/评分模板.xlsx`;

const props = defineProps({
  rubricTemplates: { type: Array, default: () => [] },
  templateForm: { type: Object, required: true },
  templateSaving: { type: Boolean, default: false },
  templateImporting: { type: Boolean, default: false }
});

defineEmits([
  "upload-template",
  "reset-template-form",
  "save-template",
  "edit-template",
  "toggle-template",
  "delete-template",
  "add-template-item",
  "remove-template-item"
]);

const templateOverview = computed(() => ({
  total: props.rubricTemplates.length,
  enabled: props.rubricTemplates.filter((template) => isEnabled(template.enabled)).length,
  items: props.templateForm.items?.length || 0
}));

const templateScoreTotal = computed(() => (props.templateForm.items || []).reduce(
  (sum, item) => sum + (Number(item.pointScore) || 0),
  0
));

function isEnabled(value) {
  return value === true || value === 1;
}

const templateGroups = computed(() => {
  const groups = new Map();
  for (const item of props.templateForm.items || []) {
    const name = item.dimensionName || "未命名维度";
    if (!groups.has(name)) groups.set(name, { name, items: [], total: 0 });
    groups.get(name).items.push(item);
    groups.get(name).total += Number(item.pointScore || 0);
  }
  return [...groups.values()];
});

function addBand(row) {
  row.scoreBands ||= [];
  row.scoreBands.push({ score_min: 0, score_max: Number(row.pointScore), criteria: "" });
}

function removeBand(row, index) {
  row.scoreBands.splice(index, 1);
  if (!row.scoreBands.length) row.criteria = (row.criteria || "").split("评分档位")[0].trim();
}

function formatScore(value) {
  return Number.isInteger(value) ? value : value.toFixed(1);
}

function templateRowClassName({ row }) {
  if (props.templateForm?.id == null || row?.id == null) return "";
  return String(row.id) === String(props.templateForm.id) ? "is-selected-template" : "";
}
</script>

<style scoped>
.rubric-point-tree { padding-left: 14px; }
.rubric-point-card { padding: 8px 12px 16px; }
.rubric-point-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 0 14px; }
.rubric-point-fields :deep(.el-input-number) { width: 100%; }
.rubric-point-actions { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.rubric-bands summary { cursor: pointer; color: #3555b5; padding: 12px 0; }
.rubric-band-row { padding: 12px; margin: 8px 0; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.rubric-band-range { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.rubric-band-range :deep(.el-input-number) { width: 104px; }
.rubric-add-band { margin-top: 12px; }
</style>
