<template>
  <section class="desk student-desk">
    <header class="module-intro student-intro">
      <div class="module-intro-copy">
        <span class="module-kicker">学习空间</span>
        <h2>我的作业</h2>
        <p>选择已发布的作业，提交 ZIP 代码包，并在教师发布后查看最终成绩与评语。</p>
      </div>
      <div class="module-intro-actions">
        <el-button @click="refresh">刷新数据</el-button>
      </div>
    </header>

    <div class="module-summary-grid student-summary-grid" aria-label="学习概览">
      <MetricCard label="全部作业" :value="assignments.length" />
      <MetricCard label="进行中" :value="activeAssignmentCount" />
      <MetricCard label="提交记录" :value="submissions.length" />
      <MetricCard label="已发布成绩" :value="publishedSubmissionCount" />
    </div>

    <div class="student-workspace-grid">
      <el-card shadow="never" class="data-panel student-assignment-panel">
        <template #header>
          <div class="panel-toolbar">
            <div class="panel-toolbar-main">
              <strong>作业列表</strong>
              <span>点击一行选择要提交或查看成绩的作业</span>
            </div>
            <div class="panel-toolbar-actions">
              <el-tag size="small" type="info">{{ assignments.length }} 项</el-tag>
            </div>
          </div>
        </template>

        <div v-if="selectedAssignmentRow" class="student-current-assignment">
          <div>
            <span>当前选择</span>
            <strong>{{ selectedAssignmentRow.title }}</strong>
            <p>截止时间：{{ selectedAssignmentRow.endTime || "未设置" }}</p>
          </div>
          <el-tag :type="assignmentStatusType(selectedAssignmentRow)" size="small">
            {{ assignmentStatusText(selectedAssignmentRow) }}
          </el-tag>
        </div>

        <div class="student-table-scroll">
          <el-table
            :data="assignments"
            height="310"
            highlight-current-row
            class="student-data-table assignment-table"
            @current-change="selectAssignment"
          >
            <el-table-column prop="title" label="标题" min-width="170" show-overflow-tooltip />
            <el-table-column prop="language" label="语言" width="90" />
            <el-table-column prop="endTime" label="截止时间" min-width="170" />
            <el-table-column label="迟交策略" width="130">
              <template #default="{ row }">{{ latePolicyText(row) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }">
                <el-tag :type="assignmentStatusType(row)" size="small">{{ assignmentStatusText(row) }}</el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂时没有已发布的作业" :image-size="72" />
            </template>
          </el-table>
        </div>

        <div class="student-upload-zone">
          <div class="student-upload-heading">
            <div>
              <strong>提交代码包</strong>
              <span>仅支持 ZIP 文件，建议直接压缩项目中的 src 目录</span>
            </div>
            <el-tag v-if="selectedAssignmentExpired" type="info" size="small">作业已截止</el-tag>
          </div>
          <el-upload
            drag
            :disabled="!selectedAssignment || selectedAssignmentExpired"
            :show-file-list="false"
            :auto-upload="false"
            :on-change="upload"
            accept=".zip"
            class="student-upload"
          >
            <div class="student-upload-content">
              <span class="student-upload-mark">ZIP</span>
              <div>
                <strong>{{ selectedAssignment ? "拖拽 ZIP 到这里，或点击选择文件" : "请先从上方选择一项作业" }}</strong>
                <span>{{ selectedAssignmentExpired ? "截止后无法继续提交" : "上传完成后将自动出现在右侧提交记录中" }}</span>
              </div>
            </div>
          </el-upload>
          <el-progress
            v-if="uploadProgress > 0 && uploadProgress < 100"
            :percentage="uploadProgress"
            class="upload-progress"
          />
        </div>
      </el-card>

      <el-card shadow="never" class="data-panel student-submission-panel">
        <template #header>
          <div class="panel-toolbar">
            <div class="panel-toolbar-main">
              <strong>我的提交</strong>
              <span>保留历史版本，当前版本会用于后续评分</span>
            </div>
            <div class="panel-toolbar-actions">
              <el-tag v-if="currentSubmission" :type="submissionStatusType(currentSubmission.status)" size="small">
                {{ submissionStatusText(currentSubmission.status) }}
              </el-tag>
              <el-tag v-else size="small" type="info">暂无提交</el-tag>
            </div>
          </div>
        </template>

        <div class="student-table-scroll">
          <el-table :data="submissions" height="390" class="student-data-table submission-table">
            <el-table-column prop="fileName" label="文件" min-width="170" show-overflow-tooltip />
            <el-table-column prop="submissionVersion" label="版本" width="70" />
            <el-table-column prop="uploadTime" label="提交时间" min-width="170" />
            <el-table-column prop="fileCount" label="代码文件" width="90" />
            <el-table-column label="当前" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.current" size="small" type="primary">当前</el-tag>
                <span v-else class="student-muted-cell">历史</span>
              </template>
            </el-table-column>
            <el-table-column label="迟交" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.late" size="small" type="warning">迟交</el-tag>
                <span v-else class="student-muted-cell">否</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="成绩" width="90">
              <template #default="{ row }">
                <strong v-if="row.currentScore !== null && row.currentScore !== undefined" class="student-score-cell">
                  {{ row.currentScore }}
                </strong>
                <span v-else class="student-muted-cell">待评分</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="提交 ZIP 后，记录会显示在这里" :image-size="72" />
            </template>
          </el-table>
        </div>

        <div class="student-panel-footer">
          <div>
            <strong>{{ selectedAssignmentRow?.title || "尚未选择作业" }}</strong>
            <span>成绩由教师复核并发布后可查看</span>
          </div>
          <el-button type="primary" class="student-grade-button" :disabled="!selectedAssignment" @click="loadGrade">
            查看最终成绩
          </el-button>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="data-panel report-panel student-report-panel">
      <template #header>
        <div class="panel-toolbar">
          <div class="panel-toolbar-main">
            <strong>最终成绩报告</strong>
            <span>教师最终分、分项得分与完整 Markdown 反馈</span>
          </div>
          <div class="panel-toolbar-actions">
            <el-tag :type="grade ? 'success' : 'info'" size="small">{{ grade ? "已发布" : "等待发布" }}</el-tag>
          </div>
        </div>
      </template>
      <div v-if="grade" class="student-report">
        <aside class="student-score-panel">
          <div class="student-section-heading">
            <div>
              <span>成绩概览</span>
              <h3>{{ selectedAssignmentRow?.title || "当前作业" }}</h3>
            </div>
          </div>

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

          <section class="teacher-comment" :class="{ 'is-empty': !teacherComment }">
            <div class="student-section-heading compact">
              <div>
                <span>教师反馈</span>
                <h3>教师评语</h3>
              </div>
            </div>
            <p>{{ teacherComment || "教师暂未填写补充评语，请以右侧评分报告为准。" }}</p>
          </section>

          <section class="student-dimension-block">
            <div class="student-section-heading compact">
              <div>
                <span>评分明细</span>
                <h3>分项得分</h3>
              </div>
              <el-tag size="small" type="info">{{ dimensionScores.length }} 项</el-tag>
            </div>
            <div v-if="dimensionScores.length" class="student-table-scroll dimension-table-scroll">
              <el-table :data="dimensionScores" size="small" class="dimension-score-table">
                <el-table-column prop="name" label="维度" min-width="120" />
                <el-table-column label="得分" width="120">
                  <template #default="{ row }">
                    <strong class="student-dimension-score">
                      {{ formatScore(row.score) }} <span>/ {{ formatScore(row.max_score) }}</span>
                    </strong>
                  </template>
                </el-table-column>
                <el-table-column prop="comment" label="评语" min-width="180" show-overflow-tooltip />
              </el-table>
            </div>
            <div v-else class="student-inline-empty">报告中暂无结构化分项得分</div>
          </section>
        </aside>

        <article class="student-markdown-panel">
          <div class="student-section-heading">
            <div>
              <span>完整反馈</span>
              <h3>评分报告</h3>
            </div>
            <el-tag size="small" type="primary">Markdown</el-tag>
          </div>
          <div v-if="reportMarkdown" class="markdown-preview rendered-markdown" v-html="reportHtml"></div>
          <el-empty v-else description="本次成绩没有附带 Markdown 报告" :image-size="88" />
        </article>
      </div>
      <div v-else class="empty-panel student-report-empty">
        <el-empty :description="reportEmptyDescription" :image-size="104" />
        <el-button v-if="selectedAssignment" type="primary" plain @click="loadGrade">重新查询成绩</el-button>
      </div>
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
let gradeRequestId = 0;
const selectedAssignmentRow = computed(() => assignments.value.find((item) => item.id === selectedAssignment.value) || null);
const selectedAssignmentExpired = computed(() => isExpired(selectedAssignmentRow.value));
const activeAssignmentCount = computed(() => assignments.value.filter((item) => !isExpired(item) && item.status === "published").length);
const publishedSubmissionCount = computed(() => submissions.value.filter((item) => item.status === "published").length);
const currentSubmission = computed(() => submissions.value.find((item) => item.current) || submissions.value[0] || null);
const reportEmptyDescription = computed(() =>
  selectedAssignment.value
    ? "该作业的成绩尚未发布，发布后可在这里查看总分和完整报告"
    : "请先选择一项作业，再查询教师发布的最终成绩"
);
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
  gradeRequestId++;
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
  const assignmentId = selectedAssignment.value;
  if (!assignmentId) return;
  const requestId = ++gradeRequestId;
  try {
    const nextGrade = await props.api.get(`/api/v1/grade-publish/my-grade/${assignmentId}`);
    if (requestId === gradeRequestId && String(selectedAssignment.value) === String(assignmentId)) {
      grade.value = nextGrade;
    }
  } catch (error) {
    if (requestId === gradeRequestId && String(selectedAssignment.value) === String(assignmentId)) {
      ElMessage.error(messageOf(error));
    }
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
