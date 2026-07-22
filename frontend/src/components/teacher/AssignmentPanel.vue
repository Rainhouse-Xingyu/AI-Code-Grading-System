<template>
  <section class="teacher-module-panel assignment-page-panel">
    <header class="module-intro assignment-module-intro">
      <div class="module-intro-copy">
        <span class="module-kicker">教学内容</span>
        <h3>作业管理</h3>
        <p>创建课程作业、配置评分规则，并跟踪每个作业的提交与发布进度。</p>
      </div>
      <div class="module-summary-grid assignment-overview-grid" aria-label="作业概览">
        <MetricCard label="全部作业" :value="assignmentOverview.total" />
        <MetricCard label="已发布" :value="assignmentOverview.published" />
        <MetricCard label="草稿" :value="assignmentOverview.draft" />
      </div>
    </header>

    <div class="grid two assignment-management-grid" :class="{ 'assignment-list-only-grid': !isAdmin }">
      <el-card v-if="isAdmin" shadow="never" class="assignment-form-card data-panel">
        <template #header>
          <div class="panel-toolbar assignment-form-heading">
            <div class="panel-toolbar-main">
              <span class="panel-eyebrow">{{ editingAssignmentId ? "正在编辑" : "新建内容" }}</span>
              <strong>{{ editingAssignmentId ? "编辑作业" : "创建作业" }}</strong>
              <small>{{ editingAssignmentId ? "修改后保存，已发布作业的关键规则将保持锁定。" : "填写基本信息并选择保存为草稿或直接发布。" }}</small>
            </div>
            <el-tag v-if="editingAssignmentId" :type="assignmentStatusType(selectedAssignmentRecord?.status)" effect="light">
              {{ assignmentStatusText(selectedAssignmentRecord?.status) }}
            </el-tag>
          </div>
        </template>
        <el-form label-position="top" class="assignment-form" @submit.prevent="$emit('save-assignment')">
          <el-alert
            v-if="isPublishedAssignment"
            class="assignment-form-alert"
            title="作业已发布，仅可修改标题、课程名称和描述"
            type="info"
            :closable="false"
            show-icon
          />
          <section class="assignment-form-section">
            <div class="form-section-heading">
              <strong>基本信息</strong>
              <span>用于学生识别作业及所属课程</span>
            </div>
            <div class="assignment-field-grid assignment-basic-fields">
              <el-form-item label="标题" class="assignment-title-field">
                <el-input v-model="assignmentForm.title" placeholder="输入作业标题" />
              </el-form-item>
              <el-form-item label="课程名称">
                <el-input v-model="assignmentForm.courseName" placeholder="输入课程名称" />
              </el-form-item>
              <el-form-item label="语言">
                <el-select v-model="assignmentForm.language" :disabled="isPublishedAssignment" placeholder="选择语言">
                  <el-option label="Java" value="java" />
                  <el-option label="Python" value="python" />
                  <el-option label="C++" value="cpp" />
                  <el-option label="JavaScript" value="javascript" />
                </el-select>
              </el-form-item>
              <el-form-item label="发布班级" class="assignment-class-field">
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
              <el-form-item label="描述" class="assignment-description-field">
                <el-input v-model="assignmentForm.description" type="textarea" :rows="3" placeholder="说明作业目标、要求或注意事项" />
              </el-form-item>
            </div>
          </section>

          <section class="assignment-form-section">
            <div class="form-section-heading">
              <strong>提交规则</strong>
              <span>设置截止时间与迟交处理方式</span>
            </div>
            <div class="assignment-field-grid assignment-rule-fields">
              <el-form-item label="截止时间" class="assignment-deadline-field">
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
            </div>
          </section>

          <section class="assignment-form-section assignment-rubric-section">
            <div class="form-section-heading">
              <strong>评分设置</strong>
              <span>从全局模板中选择本次使用的评分点</span>
            </div>
            <el-form-item label="评分模板" class="assignment-template-field">
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
            <div v-if="assignmentForm.rubricTemplateId" class="rubric-template-picker">
              <div class="template-picker-head">
                <div>
                  <strong>{{ selectedRubricTemplate?.templateName || "已选评分模板" }}</strong>
                  <span>{{ selectedRubricTemplate?.description || "选择需要用于本作业的评分点" }}</span>
                </div>
                <el-tag effect="plain">满分 100</el-tag>
              </div>
              <div class="rubric-picker-summary">
                <span>已选 <strong>{{ assignmentForm.selectedRubricItemIds.length }}</strong> 个评分点</span>
                <el-button :disabled="isPublishedAssignment" @click="rubricPickerVisible = true">选择评分点</el-button>
              </div>
            </div>
          </section>

          <div class="assignment-actions">
            <div class="assignment-primary-actions">
              <template v-if="editingAssignmentId">
                <el-button type="primary" native-type="submit">保存修改</el-button>
                <el-button v-if="selectedAssignmentRecord?.status === 'draft'" type="success" @click="$emit('publish-assignment')">
                  发布作业
                </el-button>
                <el-button @click="$emit('reset-assignment-form')">取消编辑</el-button>
              </template>
              <template v-else>
                <el-button type="primary" @click="$emit('create-assignment', true)">创建并发布</el-button>
                <el-button @click="$emit('create-assignment', false)">保存为草稿</el-button>
                <el-button text @click="$emit('reset-assignment-form')">清空表单</el-button>
              </template>
            </div>
            <div v-if="editingAssignmentId" class="destructive-zone assignment-destructive-zone">
              <span>删除后无法恢复</span>
              <el-button type="danger" plain @click="$emit('delete-assignment', selectedAssignmentRecord)">
                {{ selectedAssignmentRecord?.status === "draft" ? "删除草稿" : "删除作业" }}
              </el-button>
            </div>
          </div>
        </el-form>
      </el-card>

      <el-card shadow="never" class="assignment-list-card data-panel">
        <template #header>
          <div class="panel-toolbar assignment-list-heading">
            <div class="panel-toolbar-main">
              <span class="panel-eyebrow">当前学期</span>
              <strong>作业列表</strong>
              <small>选择一项可查看统计{{ isAdmin ? "或编辑作业设置" : "" }}。</small>
            </div>
            <div class="panel-toolbar-actions">
              <el-tag effect="plain">共 {{ assignments.length }} 项</el-tag>
            </div>
          </div>
        </template>
        <div v-if="selectedAssignmentRecord" class="assignment-selection-bar">
          <div class="assignment-selection-copy">
            <span class="selection-indicator" aria-hidden="true" />
            <div>
              <small>当前选择</small>
              <strong>{{ selectedAssignmentRecord.title || "未命名作业" }}</strong>
            </div>
          </div>
          <el-tag :type="assignmentStatusType(selectedAssignmentRecord.status)" effect="light">
            {{ assignmentStatusText(selectedAssignmentRecord.status) }}
          </el-tag>
        </div>
        <div class="assignment-table-wrap">
          <el-table
            :data="assignments"
            height="420"
            highlight-current-row
            :row-class-name="assignmentRowClassName"
            @current-change="$emit('select-assignment', $event)"
          >
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
            <template #empty>
              <el-empty class="empty-panel" description="当前学期还没有作业">
                <el-button v-if="isAdmin" type="primary" plain @click="$emit('reset-assignment-form')">开始创建作业</el-button>
              </el-empty>
            </template>
          </el-table>
        </div>
        <div v-if="assignmentStats" class="assignment-stats">
          <div class="assignment-stats-heading">
            <div>
              <strong>提交概览</strong>
              <span>{{ selectedAssignmentRecord?.title || "所选作业" }}的班级与批改进度</span>
            </div>
          </div>
          <div class="assignment-stats-grid module-summary-grid">
            <MetricCard label="班级" :value="assignmentStats.className || '-'" />
            <MetricCard label="应交" :value="assignmentStats.studentTotal" />
            <MetricCard label="已交" :value="assignmentStats.submitted" />
            <MetricCard label="未交" :value="assignmentStats.unsubmitted" />
            <MetricCard label="已批改" :value="assignmentStats.reviewed || assignmentStats.scored" />
            <MetricCard label="已发布" :value="assignmentStats.published" />
          </div>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="rubricPickerVisible" class="rubric-picker-dialog" title="选择评分点" width="720px">
      <div class="rubric-dialog-head">
        <div>
          <strong>{{ selectedRubricTemplate?.templateName || "评分模板" }}</strong>
          <span>发布后按所选评分点的比例折算为 100 分</span>
        </div>
        <el-tag type="primary" effect="light">已选 {{ assignmentForm.selectedRubricItemIds.length }} 项</el-tag>
      </div>
      <el-checkbox-group v-if="rubricTemplateItems.length" v-model="assignmentForm.selectedRubricItemIds" class="rubric-dialog-list">
        <el-checkbox
          v-for="item in rubricTemplateItems"
          :key="item.id"
          :label="item.id"
          :disabled="item.enabled === 0 || item.enabled === false"
        >
          <span>{{ cleanPointName(item) }}</span>
        </el-checkbox>
      </el-checkbox-group>
      <el-empty v-else class="empty-panel rubric-dialog-empty" description="该模板还没有可选评分点" />
      <template #footer>
        <el-button :disabled="!assignmentForm.selectedRubricItemIds.length" @click="assignmentForm.selectedRubricItemIds = []">清空</el-button>
        <el-button :disabled="!rubricTemplateItems.length" @click="selectAllEnabled">全选可用项</el-button>
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
const assignmentOverview = computed(() => ({
  total: props.assignments.length,
  published: props.assignments.filter((assignment) => assignment.status === "published").length,
  draft: props.assignments.filter((assignment) => assignment.status === "draft").length
}));

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

function assignmentRowClassName({ row }) {
  if (props.selectedAssignmentRecord?.id == null || row?.id == null) return "";
  return String(row.id) === String(props.selectedAssignmentRecord.id) ? "is-selected-assignment" : "";
}
</script>
