<template>
  <section class="teacher-module-panel">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>评分模板管理</span>
          <div class="toolbar">
            <a :href="rubricTemplateDownloadUrl" download="评分模板.xlsx" class="template-download-link">
              <el-button size="small">下载评分模板</el-button>
            </a>
            <el-upload
              :show-file-list="false"
              :auto-upload="false"
              :on-change="(file) => $emit('upload-template', file)"
              accept=".doc,.docx,.xls,.xlsx"
            >
              <el-button size="small" :loading="templateImporting">导入 Word/Excel</el-button>
            </el-upload>
            <el-button size="small" @click="$emit('reset-template-form')">新建模板</el-button>
            <el-button size="small" type="primary" :loading="templateSaving" @click="$emit('save-template')">保存模板</el-button>
          </div>
        </div>
      </template>
      <div class="template-admin-grid">
        <el-table :data="rubricTemplates" height="420" highlight-current-row @current-change="$emit('edit-template', $event)">
          <el-table-column prop="templateName" label="模板" min-width="150" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" @change="$emit('toggle-template', row)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="danger" @click.stop="$emit('delete-template', row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="template-editor">
          <el-form label-position="top">
            <el-form-item label="模板名称">
              <el-input v-model="templateForm.templateName" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="templateForm.description" />
            </el-form-item>
            <el-form-item label="启用">
              <el-switch v-model="templateForm.enabled" />
            </el-form-item>
          </el-form>
          <div class="template-item-toolbar">
            <strong>评分点</strong>
            <el-button size="small" @click="$emit('add-template-item')">添加评分点</el-button>
          </div>
          <el-table :data="templateForm.items" size="small" class="template-item-table">
            <el-table-column label="维度" min-width="130">
              <template #default="{ row }">
                <el-input v-model="row.dimensionName" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="序号" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.dimensionOrder" :min="1" size="small" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="评分点" min-width="130">
              <template #default="{ row }">
                <el-input v-model="row.pointName" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="分数" width="88">
              <template #default="{ row }">
                <el-input-number v-model="row.pointScore" :min="0.1" :precision="1" size="small" controls-position="right" />
              </template>
            </el-table-column>
            <el-table-column label="标准" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.criteria" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="启用" width="70">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ $index }">
                <el-button link type="danger" @click="$emit('remove-template-item', $index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-card>
  </section>
</template>

<script setup>
const rubricTemplateDownloadUrl = `${import.meta.env.BASE_URL}rubric-templates/评分模板.xlsx`;

defineProps({
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
</script>
