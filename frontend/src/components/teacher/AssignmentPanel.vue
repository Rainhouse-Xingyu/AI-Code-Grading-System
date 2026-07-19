<template>
  <section class="teacher-module-panel">
    <div class="grid two assignment-management-grid" :class="{ 'assignment-list-only-grid': !isAdmin }">
      <el-card v-if="isAdmin" shadow="never" class="assignment-form-card">
        <template #header>{{ editingAssignmentId ? "编辑作业" : "创建作业" }}</template>
        <el-form label-position="top" class="assignment-form" @submit.prevent="$emit('save-assignment')">
          <el-alert
            v-if="isPublishedAssignment"
            class="wide-field"
            title="作业已发布，仅可修改标题、课程名称和描述"
            type="info"
            :closable="false"
            show-icon
          />
          <el-form-item label="标题">
            <el-input v-model="assignmentForm.title" />
          </el-form-item>
          <el-form-item label="课程名称">
            <el-input v-model="assignmentForm.courseName" />
          </el-form-item>
          <el-form-item label="语言">
            <el-select v-model="assignmentForm.language" :disabled="isPublishedAssignment">
              <el-option label="Java" value="java" />
              <el-option label="Python" value="python" />
              <el-option label="C++" value="cpp" />
              <el-option label="JavaScript" value="javascript" />
            </el-select>
          </el-form-item>
          <el-form-item label="发布班级" class="wide-field">
            <el-select
              v-model="assignmentForm.classNames"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入班级"
              :disabled="isPublishedAssignment"
            >
              <el-option
                v-for="className in classOptions"
                :key="className"
                :label="className"
                :value="className"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="截止时间" class="wide-field">
            <el-date-picker
              v-model="assignmentForm.endTime"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择截止时间"
              :disabled="isPublishedAssignment"
            />
          </el-form-item>
          <el-form-item label="迟交策略">
            <el-select v-model="assignmentForm.latePolicy" :disabled="isPublishedAssignment">
              <el-option label="禁止迟交" value="forbid" />
              <el-option label="允许迟交并标记" value="allow_mark" />
              <el-option label="允许迟交并扣分" value="allow_penalty" />
            </el-select>
          </el-form-item>
          <el-form-item label="迟交扣分%">
            <el-input-number
              v-model="assignmentForm.latePenaltyPercent"
              :min="0"
              :max="100"
              :disabled="isPublishedAssignment || assignmentForm.latePolicy !== 'allow_penalty'"
            />
          </el-form-item>
          <el-form-item label="描述" class="wide-field">
            <el-input v-model="assignmentForm.description" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="评分模板" class="wide-field">
            <el-select
              v-model="assignmentForm.rubricTemplateId"
              clearable
              filterable
              placeholder="选择全局评分模板"
              :disabled="isPublishedAssignment"
              @change="$emit('template-change', $event)"
            >
              <el-option
                v-for="template in rubricTemplates"
                :key="template.id"
                :label="template.templateName"
                :value="template.id"
              />
            </el-select>
          </el-form-item>
          <div v-if="assignmentForm.rubricTemplateId" class="wide-field rubric-template-picker">
            <div class="template-picker-head">
              <span>{{ selectedRubricTemplate?.description || selectedRubricTemplate?.templateName }}</span>
              <strong>作业满分 100</strong>
            </div>
            <div class="rubric-picker-summary">
              <span>已选 {{ assignmentForm.selectedRubricItemIds.length }} 个评分点</span>
              <el-button :disabled="isPublishedAssignment" @click="rubricPickerVisible = true">选择评分点</el-button>
            </div>
          </div>
          <div class="assignment-actions wide-field">
            <template v-if="editingAssignmentId">
              <el-button type="primary" native-type="submit">保存修改</el-button>
              <el-button v-if="selectedAssignmentRecord?.status === 'draft'" type="success" @click="$emit('publish-assignment')">
                发布作业
              </el-button>
              <el-button type="danger" plain @click="$emit('delete-assignment', selectedAssignmentRecord)">
                {{ selectedAssignmentRecord?.status === "draft" ? "删除草稿" : "删除作业" }}
              </el-button>
              <el-button @click="$emit('reset-assignment-form')">取消编辑</el-button>
            </template>
            <template v-else>
              <el-button @click="$emit('create-assignment', false)">创建草稿</el-button>
              <el-button type="primary" @click="$emit('create-assignment', true)">创建并发布</el-button>
              <el-button @click="$emit('reset-assignment-form')">清空</el-button>
            </template>
          </div>
        </el-form>
      </el-card>

      <el-card shadow="never" class="assignment-list-card">
        <template #header>作业列表</template>
        <el-table :data="assignments" height="360" highlight-current-row @current-change="$emit('select-assignment', $event)">
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="courseName" label="课程" min-width="130" show-overflow-tooltip />
          <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
          <el-table-column label="班级" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ displayAssignmentClasses(row) || "-" }}</template>
          </el-table-column>
          <el-table-column label="截止时间" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
          </el-table-column>
          <el-table-column prop="language" label="语言" width="90" />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="assignmentStatusType(row.status)" size="small">{{ assignmentStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="isAdmin" label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="$emit('select-assignment', row)">修改</el-button>
              <el-button link type="danger" @click.stop="$emit('delete-assignment', row)">
                {{ row.status === "draft" ? "删除草稿" : "删除作业" }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="assignmentStats" class="assignment-stats">
          <MetricCard label="班级" :value="assignmentStats.className || '-'" />
          <MetricCard label="应交" :value="assignmentStats.studentTotal" />
          <MetricCard label="已交" :value="assignmentStats.submitted" />
          <MetricCard label="未交" :value="assignmentStats.unsubmitted" />
          <MetricCard label="已批改" :value="assignmentStats.reviewed || assignmentStats.scored" />
          <MetricCard label="已发布" :value="assignmentStats.published" />
        </div>
      </el-card>
    </div>

    <el-dialog v-model="rubricPickerVisible" title="选择评分点" width="720px">
      <div class="rubric-dialog-head">
        <strong>{{ selectedRubricTemplate?.templateName || "评分模板" }}</strong>
        <span>已选 {{ assignmentForm.selectedRubricItemIds.length }} 项，发布后按比例折算为 100 分</span>
      </div>
      <el-checkbox-group v-model="assignmentForm.selectedRubricItemIds" class="rubric-dialog-list">
        <el-checkbox
          v-for="item in rubricTemplateItems"
          :key="item.id"
          :label="item.id"
          :disabled="item.enabled === 0 || item.enabled === false"
        >
          <span>{{ cleanPointName(item) }}</span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="assignmentForm.selectedRubricItemIds = []">清空</el-button>
        <el-button @click="selectAllEnabled">全选</el-button>
        <el-button type="primary" @click="rubricPickerVisible = false">确定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, ref } from "vue";
import MetricCard from "../MetricCard.vue";

const props = defineProps({
  isAdmin: { type: Boolean, required: true },
  editingAssignmentId: { type: [Number, String, null], default: null },
  assignmentForm: { type: Object, required: true },
  rubricTemplates: { type: Array, default: () => [] },
  rubricTemplateItems: { type: Array, default: () => [] },
  selectedRubricTemplate: { type: Object, default: null },
  assignments: { type: Array, default: () => [] },
  assignmentStats: { type: Object, default: null },
  classOptions: { type: Array, default: () => [] },
  selectedAssignmentRecord: { type: Object, default: null }
});

defineEmits([
  "save-assignment",
  "create-assignment",
  "publish-assignment",
  "delete-assignment",
  "reset-assignment-form",
  "select-assignment",
  "template-change"
]);

const rubricPickerVisible = ref(false);
const isPublishedAssignment = computed(() => props.selectedAssignmentRecord?.status === "published");

function cleanPointName(item) {
  const raw = item?.pointName || item?.dimensionName || "评分点";
  return String(raw)
    .replace(/^\s*\d+[\.\u3001\-\s]*/, "")
    .split(/[\/／]/)
    .pop()
    .trim() || "评分点";
}

function selectAllEnabled() {
  props.assignmentForm.selectedRubricItemIds = props.rubricTemplateItems
    .filter((item) => item.enabled === 1 || item.enabled === true)
    .map((item) => item.id);
}

function assignmentClassNames(assignment) {
  if (!assignment) return [];
  if (Array.isArray(assignment.classNames) && assignment.classNames.length) {
    return normalizedClassNames(assignment.classNames);
  }
  if (!assignment.className) return [];
  return normalizedClassNames([assignment.className]);
}

function normalizedClassNames(values) {
  return [...new Set(values
    .flatMap((value) => String(value || "").split(/[,，]/))
    .map((item) => item.trim())
    .filter(Boolean))];
}

function displayAssignmentClasses(assignment) {
  return assignmentClassNames(assignment).join("、");
}

function formatDateTime(value) {
  if (!value) return "-";
  return String(value).replace("T", " ").slice(0, 16);
}

function assignmentStatusText(status) {
  if (status === "draft") return "草稿";
  if (status === "published") return "已发布";
  if (status === "closed") return "已截止";
  return status || "未知";
}

function assignmentStatusType(status) {
  if (status === "published") return "success";
  if (status === "closed") return "info";
  if (status === "draft") return "warning";
  return "info";
}
</script>
