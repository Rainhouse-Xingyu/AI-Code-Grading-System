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
              <el-table :data="templateForm.items" size="small" class="template-item-table">
                <el-table-column label="维度" min-width="130">
                  <template #default="{ row }">
                    <el-input v-model="row.dimensionName" size="small" placeholder="评分维度" />
                  </template>
                </el-table-column>
                <el-table-column label="序号" width="110">
                  <template #default="{ row }">
                    <el-input-number v-model="row.dimensionOrder" :min="1" size="small" controls-position="right" />
                  </template>
                </el-table-column>
                <el-table-column label="评分点" min-width="140">
                  <template #default="{ row }">
                    <el-input v-model="row.pointName" size="small" placeholder="评分点名称" />
                  </template>
                </el-table-column>
                <el-table-column label="分数" width="96">
                  <template #default="{ row }">
                    <el-input-number v-model="row.pointScore" :min="0.1" :precision="1" size="small" controls-position="right" />
                  </template>
                </el-table-column>
                <el-table-column label="标准" min-width="180">
                  <template #default="{ row }">
                    <el-input v-model="row.criteria" size="small" placeholder="描述得分标准" />
                  </template>
                </el-table-column>
                <el-table-column label="启用" width="70" align="center">
                  <template #default="{ row }">
                    <el-switch v-model="row.enabled" size="small" />
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="64" align="right">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="$emit('remove-template-item', $index)">删除</el-button>
                  </template>
                </el-table-column>
                <template #empty>
                  <el-empty class="empty-panel template-items-empty" description="还没有评分点">
                    <el-button type="primary" plain @click="$emit('add-template-item')">添加第一个评分点</el-button>
                  </el-empty>
                </template>
              </el-table>
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

function formatScore(value) {
  return Number.isInteger(value) ? value : value.toFixed(1);
}

function templateRowClassName({ row }) {
  if (props.templateForm?.id == null || row?.id == null) return "";
  return String(row.id) === String(props.templateForm.id) ? "is-selected-template" : "";
}
</script>
