<template>
  <section class="desk student-desk">
    <div class="page-heading">
      <div>
        <h2>学生端</h2>
        <p>查看已发布作业、上传 ZIP、查看最终评分报告</p>
      </div>
      <el-button @click="refresh">刷新</el-button>
    </div>

    <div class="grid two">
      <el-card shadow="never">
        <template #header>作业列表</template>
        <el-table :data="assignments" height="300" highlight-current-row @current-change="selectAssignment">
          <el-table-column prop="title" label="标题" min-width="160" />
          <el-table-column prop="language" label="语言" width="100" />
          <el-table-column prop="endTime" label="截止时间" min-width="170" />
          <el-table-column label="迟交策略" width="130">
            <template #default="{ row }">{{ latePolicyText(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="assignmentStatusType(row)" size="small">{{ assignmentStatusText(row) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-upload :show-file-list="false" :auto-upload="false" :on-change="upload" accept=".zip" class="student-upload">
          <el-button type="primary" :disabled="!selectedAssignment || selectedAssignmentExpired">上传 ZIP 作业</el-button>
        </el-upload>
        <el-progress
          v-if="uploadProgress > 0 && uploadProgress < 100"
          :percentage="uploadProgress"
          class="upload-progress"
        />
      </el-card>

      <el-card shadow="never">
        <template #header>我的提交</template>
        <el-table :data="submissions" height="300">
          <el-table-column prop="fileName" label="文件" min-width="160" />
          <el-table-column prop="submissionVersion" label="版本" width="70" />
          <el-table-column prop="uploadTime" label="提交时间" min-width="170" />
          <el-table-column prop="fileCount" label="代码文件" width="90" />
          <el-table-column label="当前" width="70">
            <template #default="{ row }">{{ row.current ? "是" : "否" }}</template>
          </el-table-column>
          <el-table-column label="迟交" width="70">
            <template #default="{ row }">{{ row.late ? "是" : "否" }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成绩" width="90">
            <template #default="{ row }">{{ row.currentScore ?? "待评分" }}</template>
          </el-table-column>
        </el-table>
        <el-button type="primary" class="student-grade-button" @click="loadGrade">查看成绩</el-button>
      </el-card>
    </div>

    <el-card shadow="never" class="report-panel">
      <template #header>最终报告</template>
      <div v-if="grade" class="student-report">
        <aside class="student-score-panel">
          <div class="student-score-grid">
            <MetricCard label="教师最终分" :value="finalScoreText" />
            <MetricCard label="AI 初评分" :value="aiOriginalScoreText" />
          </div>

          <el-alert
            v-if="scoreChanged"
            type="warning"
            :closable="false"
            title="教师已调整 AI 初评分"
            :description="scoreChangeDescription"
          />

          <section v-if="teacherComment" class="teacher-comment">
            <h3>教师评语</h3>
            <p>{{ teacherComment }}</p>
          </section>

          <section v-if="dimensionScores.length" class="student-dimension-block">
            <h3>分项得分</h3>
            <el-table :data="dimensionScores" size="small" border>
              <el-table-column prop="name" label="维度" min-width="120" />
              <el-table-column label="得分" width="110">
                <template #default="{ row }">{{ formatScore(row.score) }} / {{ formatScore(row.max_score) }}</template>
              </el-table-column>
              <el-table-column prop="comment" label="评语" min-width="180" show-overflow-tooltip />
            </el-table>
          </section>
        </aside>
        <article class="markdown-preview rendered-markdown" v-html="reportHtml"></article>
      </div>
      <el-empty v-else description="成绩发布后可在这里查看总分和 Markdown 报告" />
    </el-card>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { messageOf } from "../JS/api.js";
import { renderMarkdown } from "../JS/markdown.js";
import MetricCard from "./MetricCard.vue";

const props = defineProps({
  api: { type: Object, required: true }
});

const assignments = ref([]);
const submissions = ref([]);
const selectedAssignment = ref(null);
const uploadProgress = ref(0);
const grade = ref(null);
const selectedAssignmentRow = computed(() => assignments.value.find((item) => item.id === selectedAssignment.value) || null);
const selectedAssignmentExpired = computed(() => isExpired(selectedAssignmentRow.value));
const teacherReview = computed(() => {
  const review = grade.value?.teacherReview;
  return review && typeof review === "object" && !Array.isArray(review) ? review : null;
});
const reportMarkdown = computed(() => teacherReview.value?.modifiedMarkdown || grade.value?.aiReport?.reportMarkdown || "");
const reportHtml = computed(() => renderMarkdown(reportMarkdown.value));
const finalScore = computed(() => grade.value?.publish?.finalScore ?? teacherReview.value?.finalScore ?? "");
const aiOriginalScore = computed(() => grade.value?.aiReport?.totalScore ?? "");
const finalScoreText = computed(() => formatScore(finalScore.value));
const aiOriginalScoreText = computed(() => formatScore(aiOriginalScore.value));
const teacherComment = computed(() => teacherReview.value?.finalComment || "");
const scoreChanged = computed(() => {
  const finalValue = Number(finalScore.value);
  const aiValue = Number(aiOriginalScore.value);
  return Number.isFinite(finalValue) && Number.isFinite(aiValue) && Math.abs(finalValue - aiValue) > 0.005;
});
const scoreChangeDescription = computed(() => `AI 初评分 ${aiOriginalScoreText.value}，教师最终分 ${finalScoreText.value}`);
const dimensionScores = computed(() => {
  const reviewScores = parseDimensionScores(teacherReview.value?.modifiedJson);
  if (reviewScores.length) return reviewScores;
  return parseDimensionScores(grade.value?.aiReport?.scoreDetailJson || grade.value?.aiReport?.scoreJson);
});

onMounted(refresh);

async function refresh() {
  try {
    const [nextAssignments, nextSubmissions] = await Promise.all([
      props.api.get("/api/v1/assignments"),
      props.api.get("/api/v1/submissions/my")
    ]);
    assignments.value = nextAssignments;
    submissions.value = nextSubmissions;
    if (!selectedAssignment.value && nextAssignments[0]) {
      selectedAssignment.value = nextAssignments[0].id;
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function selectAssignment(row) {
  if (isExpired(row)) {
    selectedAssignment.value = null;
    grade.value = null;
    ElMessage.warning("作业已截止，不能选择或提交");
    return;
  }
  selectedAssignment.value = row?.id || null;
  grade.value = null;
}

async function upload(uploadFile) {
  if (!uploadFile.raw || !selectedAssignment.value) return;
  if (selectedAssignmentExpired.value) {
    ElMessage.warning("作业已截止，不能提交");
    return;
  }
  const form = new FormData();
  form.append("assignmentId", String(selectedAssignment.value));
  form.append("file", uploadFile.raw);
  uploadProgress.value = 1;
  try {
    await props.api.upload("/api/v1/submissions", form, {
      onProgress: (percent) => {
        uploadProgress.value = percent;
      }
    });
    await refresh();
    ElMessage.success("作业已提交，等待教师评分");
  } catch (error) {
    const message = messageOf(error);
    if (message.includes("src目录") || message.includes("未发现受支持的代码文件")) {
      ElMessageBox.alert("同学，可以直接将src目录进行压缩上传哦～", "上传提醒", { confirmButtonText: "知道了" });
    } else {
      ElMessage.error(message);
    }
  } finally {
    window.setTimeout(() => {
      uploadProgress.value = 0;
    }, 600);
  }
}

async function loadGrade() {
  if (!selectedAssignment.value) return;
  try {
    grade.value = await props.api.get(`/api/v1/grade-publish/my-grade/${selectedAssignment.value}`);
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function parseDimensionScores(value) {
  if (!value) return [];
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    if (!Array.isArray(parsed)) return [];
    return parsed.map((item) => ({
      name: item.name || item.dimension || "评分维度",
      score: Number(item.score ?? 0),
      max_score: Number(item.max_score ?? item.maxScore ?? item.weight ?? 100),
      comment: item.comment || item.feedback || ""
    }));
  } catch {
    return [];
  }
}

function formatScore(value) {
  if (value === null || value === undefined || value === "") return "待发布";
  const numberValue = Number(value);
  if (!Number.isFinite(numberValue)) return String(value);
  return Number.isInteger(numberValue) ? String(numberValue) : numberValue.toFixed(2);
}

function latePolicyText(row) {
  if (row.latePolicy === "allow_penalty") {
    return `可迟交，扣 ${row.latePenaltyPercent || 0}%`;
  }
  if (row.latePolicy === "allow_mark") {
    return "可迟交";
  }
  return "禁止迟交";
}

function isExpired(row) {
  return Boolean(row?.endTime && new Date(row.endTime).getTime() <= Date.now());
}

function assignmentStatusText(row) {
  if (isExpired(row)) return "已截止";
  if (row?.status === "published") return "进行中";
  if (row?.status === "draft") return "草稿";
  return row?.status || "未知";
}

function assignmentStatusType(row) {
  if (isExpired(row)) return "info";
  if (row?.status === "published") return "success";
  if (row?.status === "draft") return "warning";
  return "info";
}

function submissionStatusText(status) {
  if (status === "published") return "已发布";
  if (status === "reviewed") return "已复核";
  if (status === "scored") return "已评分";
  if (status === "scoring") return "评分中";
  if (status === "parsed") return "已解析";
  if (status === "uploaded") return "已上传";
  if (status === "parse_failed") return "解析失败";
  if (status === "failed") return "失败";
  return status || "未知";
}

function submissionStatusType(status) {
  if (status === "published") return "success";
  if (status === "reviewed") return "primary";
  if (status === "scored") return "warning";
  if (status === "failed" || status === "parse_failed") return "danger";
  return "info";
}
</script>
