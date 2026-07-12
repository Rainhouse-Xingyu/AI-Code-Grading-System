<template>
  <section class="desk teacher-desk">
    <div class="page-heading">
      <div>
        <h2>教师工作台</h2>
        <p>{{ teacherSubtitle }} · 作业、评分与发布</p>
      </div>
      <el-button @click="refreshAssignments">刷新</el-button>
    </div>

    <div class="teacher-module-content">
        <AssignmentPanel
          v-show="activeTeacherModule === 'assignments'"
          :is-admin="isAdmin"
          :editing-assignment-id="editingAssignmentId"
          :assignment-form="assignmentForm"
          :rubric-templates="rubricTemplates"
          :rubric-template-items="rubricTemplateItems"
          :selected-rubric-template="selectedRubricTemplate"
          :assignments="assignments"
          :assignment-stats="assignmentStats"
          :class-options="classOptions"
          :selected-assignment-record="selectedAssignmentRecord"
          @save-assignment="saveAssignment"
          @create-assignment="createAssignment"
          @publish-assignment="publishAssignment"
          @delete-assignment="deleteAssignment"
          @reset-assignment-form="resetAssignmentForm"
          @select-assignment="selectAssignment"
          @template-change="onAssignmentTemplateChange"
        />

        <section v-show="activeTeacherModule === 'students'" class="teacher-module-panel">
      <el-card shadow="never">
        <template #header>学生管理</template>
        <div class="action-grid">
          <el-input v-model="studentSearch" class="student-search-input" clearable placeholder="按学号/姓名/班级搜索" />
          <el-button @click="downloadStudentTemplate">学生模板</el-button>
          <el-upload :show-file-list="false" :auto-upload="false" :on-change="selectStudentFile" accept=".xls,.xlsx">
            <el-button type="success">导入学生 Excel</el-button>
          </el-upload>
          <el-button type="warning" :disabled="!canResetFilteredStudents" @click="resetAllStudentPasswords">重置密码</el-button>
          <el-button :disabled="!rubric" @click="rubricPreviewVisible = true">预览 Rubric</el-button>
        </div>
        <div class="upload-progress-stack">
          <el-progress v-if="studentUploadProgress > 0 && studentUploadProgress < 100" :percentage="studentUploadProgress" />
        </div>
        <el-table :data="filteredStudents" height="160" class="student-table">
          <el-table-column prop="username" label="学号" min-width="100" />
          <el-table-column prop="realName" label="姓名" min-width="90" />
          <el-table-column prop="className" label="班级" min-width="110" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="resetStudentPassword(row)">重置</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="metric-row">
          <MetricCard label="学生" :value="students.length" />
          <MetricCard label="作业" :value="assignments.length" />
          <MetricCard label="Rubric" :value="rubric ? '已配置' : '待选择'" />
        </div>
        <div class="storage-cleanup-panel">
          <div class="card-head">
            <span>文件清理</span>
            <div class="toolbar">
              <el-input-number v-model="cleanupOlderThanDays" :min="1" :max="3650" size="small" />
              <el-button size="small" @click="previewFileCleanup">预览</el-button>
              <el-button size="small" type="danger" :disabled="!cleanupPreview?.candidateCount" @click="executeFileCleanup">
                清理
              </el-button>
            </div>
          </div>
          <div v-if="cleanupPreview" class="cleanup-summary">
            <MetricCard label="候选文件" :value="cleanupPreview.candidateCount" />
            <MetricCard label="候选容量" :value="formatBytes(cleanupPreview.candidateBytes)" />
            <MetricCard label="已删文件" :value="cleanupPreview.deletedCount || 0" />
          </div>
          <el-empty v-else class="cleanup-empty" description="点击预览后显示可清理文件数量和容量" />
        </div>
      </el-card>
        </section>

        <RubricTemplatePanel
          v-if="isAdmin"
          v-show="activeTeacherModule === 'templates'"
          :rubric-templates="rubricTemplates"
          :template-form="templateForm"
          :template-saving="templateSaving"
          :template-importing="templateImporting"
          @upload-template="uploadRubricTemplate"
          @reset-template-form="resetRubricTemplateForm"
          @save-template="saveRubricTemplate"
          @edit-template="editRubricTemplate"
          @toggle-template="toggleRubricTemplate"
          @delete-template="deleteRubricTemplate"
          @add-template-item="addRubricTemplateItem"
          @remove-template-item="removeRubricTemplateItem"
        />

        <section v-show="activeTeacherModule === 'submissions'" class="teacher-module-panel submissions-module-panel">
      <el-card shadow="never" class="submissions-card">
        <template #header>
          <div class="card-head">
            <span>提交记录</span>
            <div class="toolbar">
              <el-input
                v-model="submissionQuery.studentNo"
                size="small"
                class="submission-query-input"
                clearable
                placeholder="学号"
                @keyup.enter="refreshSubmissions"
              />
              <el-select
                v-model="submissionQuery.status"
                size="small"
                clearable
                class="submission-query-select"
                placeholder="状态"
              >
                <el-option
                  v-for="item in submissionStatusOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-button size="small" @click="refreshSubmissions">查询</el-button>
              <el-checkbox v-model="jointReviewEnabled" size="small">联合评审</el-checkbox>
              <el-button size="small" type="primary" @click="startScoring">批量评分</el-button>
              <el-button size="small" @click="openManualScoring">手动评分</el-button>
              <el-button size="small" type="success" @click="publishAllGrades">一键推送</el-button>
            </div>
          </div>
        </template>
        <el-table
          ref="scoringTable"
          class="submissions-table"
          :data="submissions"
          height="100%"
          row-key="id"
          highlight-current-row
          @current-change="selectSubmission"
          @selection-change="selectScoringRows"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column prop="studentUsername" label="学号" width="100" />
          <el-table-column prop="studentRealName" label="姓名" width="90" />
          <el-table-column prop="fileName" label="文件" min-width="150" />
          <el-table-column prop="submissionVersion" label="版本" width="70" />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="迟交" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.late" type="warning" size="small">是</el-tag>
              <span v-else>否</span>
            </template>
          </el-table-column>
          <el-table-column label="发布" width="90">
            <template #default="{ row }">
              <el-tag :type="row.publishStatus === 1 ? 'success' : row.publishStatus === 2 ? 'info' : 'warning'" size="small">
                {{ publishStatusText(row.publishStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="downloadSubmission(row.id)">ZIP</el-button>
              <el-button link type="warning" @click.stop="returnSubmission(row)">打回</el-button>
              <el-button v-if="row.publishStatus !== 1" link type="primary" @click.stop="publishGrades([row])">推送</el-button>
              <el-button v-if="row.publishStatus === 1" link type="danger" @click.stop="retractGrades([row])">撤回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
        </section>

        <section v-show="activeTeacherModule === 'tasks'" class="teacher-module-panel">
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span>AI 任务</span>
            <el-tag v-if="scoringPolling" size="small" type="warning">轮询中</el-tag>
          </div>
        </template>
        <div v-if="taskProgress" class="task-progress-grid">
          <MetricCard label="全部任务" :value="taskProgress.total || 0" />
          <MetricCard label="等待中" :value="taskProgress.statusCounts?.pending || 0" />
          <MetricCard label="执行中" :value="`${taskProgress.statusCounts?.running || 0}/${taskProgress.maxConcurrentTasks || 0}`" />
          <MetricCard label="已完成" :value="taskProgress.statusCounts?.success || 0" />
          <MetricCard label="失败" :value="taskProgress.statusCounts?.failed || 0" />
        </div>
        <el-progress
          v-if="taskProgress?.latestBatchTotal"
          class="task-batch-progress"
          :percentage="latestBatchPercent"
          :status="taskProgress.latestBatchCounts?.failed ? 'exception' : undefined"
        />
        <el-table :data="tasks" height="210">
          <el-table-column prop="id" label="任务" width="70" />
          <el-table-column prop="batchId" label="批次" min-width="120" show-overflow-tooltip />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="taskStatusType(row.status)" size="small">{{ taskStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="loadTaskLogs(row.id)">日志</el-button>
              <el-button v-if="row.status === 'failed'" link type="danger" @click="retryTask(row.id)">重试</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="tokenQuota" class="token-quota-grid">
          <MetricCard label="DeepSeek 已用" :value="formatTokens(tokenQuota.usedTokens)" />
          <MetricCard label="剩余配额" :value="formatTokens(tokenQuota.remainingTokens)" />
          <MetricCard label="使用率" :value="`${tokenQuota.usagePercent || 0}%`" />
        </div>
        <el-alert
          v-if="tokenQuota && tokenQuota.warningLevel !== 'normal'"
          class="quota-alert"
          :type="tokenQuota.quotaExceeded ? 'error' : 'warning'"
          :closable="false"
          :title="quotaWarningText"
        />
        <div v-if="tokenStats" class="token-stats-panel">
          <div class="token-stats-head">
            <span>Token 统计</span>
            <strong>{{ formatTokens(tokenStats.totalTokens) }} / {{ tokenStats.reportCount || 0 }} 份</strong>
          </div>
          <el-table :data="tokenStats.byModel || []" size="small" max-height="150">
            <el-table-column prop="modelName" label="模型" min-width="150" show-overflow-tooltip />
            <el-table-column label="Token" width="92">
              <template #default="{ row }">{{ formatTokens(row.tokenUsage) }}</template>
            </el-table-column>
            <el-table-column prop="reportCount" label="报告" width="70" />
          </el-table>
          <el-table :data="tokenStats.byAssignment || []" size="small" max-height="150">
            <el-table-column prop="title" label="作业" min-width="140" show-overflow-tooltip />
            <el-table-column label="Token" width="92">
              <template #default="{ row }">{{ formatTokens(row.tokenUsage) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <div v-if="taskLogs.length" class="log-box">
          <p v-for="log in taskLogs" :key="log.id">
            <strong>{{ log.level }}</strong>
            <span v-if="log.modelName">[{{ log.modelName }}]</span>
            {{ log.message }}
            <span v-if="log.durationMs">· {{ log.durationMs }}ms</span>
          </p>
        </div>
      </el-card>
        </section>

        <section v-show="activeTeacherModule === 'downloads'" class="teacher-module-panel">
      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span>文件下载</span>
            <div class="toolbar">
              <el-select
                v-model="selectedAssignment"
                size="small"
                filterable
                class="review-assignment-select"
                placeholder="选择作业"
              >
                <el-option
                  v-for="assignment in assignments"
                  :key="assignment.id"
                  :label="assignment.title"
                  :value="assignment.id"
                />
              </el-select>
              <el-button size="small" :loading="packageDownloading" @click="downloadAllReports">全部报告</el-button>
              <el-button size="small" :loading="packageDownloading" @click="downloadAllCodes">全部代码</el-button>
              <el-button size="small" type="primary" :loading="packageDownloading" @click="downloadAllPackage">合并下载</el-button>
              <el-button size="small" type="success" :loading="packageDownloading" @click="downloadScoreSheet">成绩表 Excel</el-button>
              <el-button size="small" :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedReports">
                选中报告
              </el-button>
              <el-button size="small" :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedCodes">
                选中代码
              </el-button>
              <el-button size="small" :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedPackage">
                选中合并
              </el-button>
            </div>
          </div>
          <el-progress
            v-if="packageDownloadProgress > 0 && packageDownloadProgress < 100"
            class="package-download-progress"
            :percentage="packageDownloadProgress"
          />
        </template>
        <el-table
          ref="downloadTable"
          :data="submissions"
          height="360"
          row-key="id"
          @selection-change="selectDownloadRows"
        >
          <el-table-column type="selection" width="44" />
          <el-table-column prop="studentUsername" label="学号" width="120" />
          <el-table-column prop="studentRealName" label="姓名" width="110" />
          <el-table-column prop="fileName" label="文件" min-width="170" show-overflow-tooltip />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="downloadReport(row.id)">报告</el-button>
              <el-button link type="primary" @click.stop="downloadSubmission(row.id)">代码</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
        </section>

        <section v-show="activeTeacherModule === 'review'" class="teacher-module-panel">
    <el-card shadow="never" class="report-panel">
      <template #header>
        <div class="card-head">
          <span>评分详情与复核</span>
          <div class="toolbar">
            <el-select
              v-model="selectedAssignment"
              size="small"
              filterable
              class="review-assignment-select"
              placeholder="选择作业"
            >
              <el-option
                v-for="assignment in assignments"
                :key="assignment.id"
                :label="assignment.title"
                :value="assignment.id"
              />
            </el-select>
            <el-button @click="loadReport">查看报告</el-button>
            <el-button @click="loadReportHistory">历史报告</el-button>
            <el-button @click="openReportCompare">模型对比</el-button>
            <el-button @click="loadReviewHistory">复核历史</el-button>
            <el-button @click="saveReview">保存复核</el-button>
            <el-button type="primary" @click="publishGrades">{{ publishButtonText }}</el-button>
            <el-button type="warning" @click="retractGrades()">撤回成绩</el-button>
            <el-button @click="downloadSingle">单份 PDF</el-button>
            <el-button @click="downloadBatch">批量 PDF</el-button>
          </div>
        </div>
      </template>
      <div class="review-workspace">
        <aside class="review-submission-list">
          <div class="review-list-head">
            <strong>AI 已评分作业</strong>
            <span>{{ reviewSubmissionRows.length }} 份</span>
          </div>
          <el-table
            :data="reviewSubmissionRows"
            height="360"
            highlight-current-row
            :row-class-name="reviewRowClassName"
            @row-click="openReviewSubmission"
          >
            <el-table-column prop="studentUsername" label="学号" min-width="92" show-overflow-tooltip />
            <el-table-column prop="studentRealName" label="姓名" min-width="82" show-overflow-tooltip />
            <el-table-column label="AI分" width="72">
              <template #default="{ row }">{{ scoreText(row.currentScore) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!reviewSubmissionRows.length" description="当前作业还没有 AI 评分报告" />
        </aside>

        <div v-if="report" class="review-detail">
          <section class="review-meta-panel">
            <div class="review-current-student">
              <strong>{{ selectedSubmissionRow?.studentRealName || "已选学生" }}</strong>
              <span>{{ selectedSubmissionRow?.studentUsername || "" }}</span>
            </div>
            <MetricCard label="AI 总分" :value="report.totalScore" />
            <el-form label-position="top">
              <el-form-item label="最终分">
                <el-input v-model="finalScore" />
              </el-form-item>
              <el-form-item label="教师评语">
                <el-input v-model="reviewComment" type="textarea" :rows="6" />
              </el-form-item>
            </el-form>
          </section>
          <section class="markdown-editor-grid">
            <el-input v-model="reportMarkdown" type="textarea" :rows="18" class="markdown-editor" />
            <article class="markdown-preview rendered-markdown" v-html="reportHtml"></article>
          </section>
          <section class="review-score-panel">
            <el-table :data="dimensionScores" class="dimension-table" size="small" border>
              <el-table-column prop="name" label="评分维度" min-width="110" />
              <el-table-column label="得分" width="120">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.score"
                    :min="0"
                    :max="Number(row.max_score || row.maxScore || 100)"
                    :precision="2"
                    controls-position="right"
                    size="small"
                  />
                </template>
              </el-table-column>
              <el-table-column label="满分" width="70">
                <template #default="{ row }">{{ row.max_score ?? row.maxScore ?? 100 }}</template>
              </el-table-column>
              <el-table-column prop="comment" label="评语" min-width="360">
                <template #default="{ row }">
                  <el-input v-model="row.comment" type="textarea" :rows="2" size="small" />
                </template>
              </el-table-column>
            </el-table>
            <section v-if="issueRows.length" class="issue-block">
              <h3>问题列表</h3>
              <el-table :data="issueRows" class="issue-table" size="small" border>
                <el-table-column label="级别" width="80">
                  <template #default="{ row }">
                    <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="file" label="文件" min-width="140" show-overflow-tooltip />
                <el-table-column prop="line" label="行" width="64" />
                <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
              </el-table>
            </section>
          </section>
        </div>
        <el-empty v-else description="点击左侧学生作业查看 AI 评分报告" />
      </div>
    </el-card>
        </section>
      </div>

    <el-dialog v-model="scoringPreviewVisible" title="确认评分列表" width="720px">
      <el-table :data="scoringSelection" height="300">
        <el-table-column prop="studentUsername" label="学号" width="120" />
        <el-table-column prop="studentRealName" label="姓名" width="110" />
        <el-table-column prop="fileName" label="文件名" min-width="170" />
        <el-table-column prop="uploadTime" label="提交时间" min-width="170" />
      </el-table>
      <template #footer>
        <el-button @click="scoringPreviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="scoringSubmitting" @click="confirmScoring">发起 AI 评分</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rubricPreviewVisible" title="Rubric JSON 预览" width="760px">
      <pre class="rubric-preview">{{ rubricPreviewJson }}</pre>
    </el-dialog>

    <el-dialog v-model="manualScoringVisible" title="手动评分" width="1080px">
      <div class="review-current-student manual-scoring-student">
        <strong>{{ selectedSubmissionRow?.studentRealName || "已选学生" }}</strong>
        <span>{{ selectedSubmissionRow?.studentUsername || "" }}</span>
      </div>
      <el-table :data="manualScoreRows" class="dimension-table" size="small" border max-height="360">
        <el-table-column prop="name" label="评分点" min-width="180" />
        <el-table-column label="得分" width="130">
          <template #default="{ row }">
            <el-input-number
              v-model="row.score"
              :min="0"
              :max="Number(row.max_score || row.maxScore || 100)"
              :precision="2"
              controls-position="right"
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column label="满分" width="76">
          <template #default="{ row }">{{ row.max_score ?? row.maxScore ?? 100 }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="评语" min-width="360">
          <template #default="{ row }">
            <el-input v-model="row.comment" type="textarea" :rows="2" size="small" />
          </template>
        </el-table-column>
      </el-table>
      <section class="issue-block manual-issue-block">
        <div class="card-head">
          <h3>问题列表</h3>
          <el-button size="small" @click="addManualIssue">添加问题</el-button>
        </div>
        <el-table :data="manualIssueRows" class="issue-table" size="small" border max-height="220">
          <el-table-column label="级别" width="120">
            <template #default="{ row }">
              <el-select v-model="row.severity" size="small">
                <el-option label="错误" value="error" />
                <el-option label="警告" value="warning" />
                <el-option label="建议" value="suggestion" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="文件" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.file" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="行" width="92">
            <template #default="{ row }">
              <el-input v-model="row.line" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }">
              <el-input v-model="row.description" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="76">
            <template #default="{ $index }">
              <el-button link type="danger" @click="removeManualIssue($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
      <template #footer>
        <span class="manual-score-total">合计 {{ manualScoreTotal.toFixed(2) }} / 100</span>
        <el-button @click="manualScoringVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualScoringSubmitting" @click="saveManualScoring">保存并进入复核发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reportHistoryVisible" title="AI 报告历史" width="820px">
      <el-table :data="reportHistory" size="small" border>
        <el-table-column prop="id" label="报告" width="72" />
        <el-table-column prop="taskId" label="任务" width="72" />
        <el-table-column prop="modelName" label="模型" min-width="150" />
        <el-table-column prop="totalScore" label="总分" width="90" />
        <el-table-column prop="tokenUsage" label="Token" width="90" />
        <el-table-column prop="createdAt" label="时间" min-width="160" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="useHistoryReport(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="reportCompareVisible" title="不同模型评分对比" width="1180px">
      <div v-if="reportHistory.length >= 2" class="compare-panel">
        <div class="compare-selectors">
          <el-select v-model="compareLeftId" placeholder="选择左侧报告">
            <el-option
              v-for="item in reportHistory"
              :key="item.id"
              :label="reportOptionLabel(item)"
              :value="item.id"
            />
          </el-select>
          <el-select v-model="compareRightId" placeholder="选择右侧报告">
            <el-option
              v-for="item in reportHistory"
              :key="item.id"
              :label="reportOptionLabel(item)"
              :value="item.id"
            />
          </el-select>
        </div>
        <div class="compare-summary-grid">
          <MetricCard :label="compareLeftReport?.modelName || '左侧模型'" :value="scoreText(compareLeftReport?.totalScore)" />
          <MetricCard label="分差" :value="scoreDeltaText(compareLeftReport, compareRightReport)" />
          <MetricCard :label="compareRightReport?.modelName || '右侧模型'" :value="scoreText(compareRightReport?.totalScore)" />
        </div>
        <el-table :data="compareDimensionRows" size="small" border class="compare-table">
          <el-table-column prop="name" label="评分维度" min-width="150" />
          <el-table-column label="左侧得分" width="110">
            <template #default="{ row }">{{ scoreText(row.leftScore) }}</template>
          </el-table-column>
          <el-table-column label="右侧得分" width="110">
            <template #default="{ row }">{{ scoreText(row.rightScore) }}</template>
          </el-table-column>
          <el-table-column label="差值" width="100">
            <template #default="{ row }">{{ signedNumber(row.delta) }}</template>
          </el-table-column>
          <el-table-column prop="leftComment" label="左侧评语" min-width="180" show-overflow-tooltip />
          <el-table-column prop="rightComment" label="右侧评语" min-width="180" show-overflow-tooltip />
        </el-table>
        <div class="compare-columns">
          <section>
            <h3>左侧问题</h3>
            <el-table :data="compareLeftIssues" size="small" max-height="220" border>
              <el-table-column prop="severity" label="级别" width="82" />
              <el-table-column prop="file" label="文件" min-width="120" show-overflow-tooltip />
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </section>
          <section>
            <h3>右侧问题</h3>
            <el-table :data="compareRightIssues" size="small" max-height="220" border>
              <el-table-column prop="severity" label="级别" width="82" />
              <el-table-column prop="file" label="文件" min-width="120" show-overflow-tooltip />
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </section>
        </div>
        <div class="compare-columns">
          <article class="markdown-preview rendered-markdown" v-html="compareLeftMarkdown"></article>
          <article class="markdown-preview rendered-markdown" v-html="compareRightMarkdown"></article>
        </div>
      </div>
      <el-empty v-else description="同一提交至少需要两份 AI 报告才能对比" />
    </el-dialog>

    <el-dialog v-model="reviewHistoryVisible" title="复核修改历史" width="820px">
      <el-table :data="reviewHistory" size="small" border>
        <el-table-column prop="id" label="复核" width="72" />
        <el-table-column prop="aiReportId" label="AI报告" width="90" />
        <el-table-column prop="finalScore" label="最终分" width="90" />
        <el-table-column prop="finalComment" label="教师评语" min-width="170" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" min-width="160" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="useReviewHistory(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { messageOf } from "../JS/api.js";
import { renderMarkdown } from "../JS/markdown.js";
import MetricCard from "./MetricCard.vue";
import AssignmentPanel from "./teacher/AssignmentPanel.vue";
import RubricTemplatePanel from "./teacher/RubricTemplatePanel.vue";

const props = defineProps({
  api: { type: Object, required: true },
  user: { type: Object, required: true },
  activeModule: { type: String, default: "assignments" }
});
const emit = defineEmits(["switch-module"]);

const assignments = ref([]);
const submissions = ref([]);
const tasks = ref([]);
const taskLogs = ref([]);
const taskProgress = ref(null);
const students = ref([]);
const rubricTemplates = ref([]);
const rubricTemplateItems = ref([]);
const activeTeacherModule = computed(() => props.activeModule || "assignments");
const studentUploadProgress = ref(0);
const cleanupOlderThanDays = ref(180);
const cleanupPreview = ref(null);
const scoringTable = ref(null);
const downloadTable = ref(null);
const scoringSelection = ref([]);
const downloadSelection = ref([]);
const scoringPreviewVisible = ref(false);
const scoringSubmitting = ref(false);
const manualScoringVisible = ref(false);
const manualScoringSubmitting = ref(false);
const manualScoreRows = ref([]);
const manualIssueRows = ref([]);
const jointReviewEnabled = ref(false);
const scoringWatchIds = ref([]);
const scoringPolling = ref(false);
const packageDownloading = ref(false);
const packageDownloadProgress = ref(0);
let scoringPollTimer = null;
let syncingSubmissionSelection = false;
const tokenQuota = ref(null);
const tokenStats = ref(null);
const templateSaving = ref(false);
const templateImporting = ref(false);
const selectedAssignment = ref(null);
const editingAssignmentId = ref(null);
const selectedSubmission = ref(null);
const assignmentStats = ref(null);
const report = ref(null);
const rubric = ref(null);
const rubricPreviewVisible = ref(false);
const reportHistoryVisible = ref(false);
const reportHistory = ref([]);
const reportCompareVisible = ref(false);
const compareLeftId = ref(null);
const compareRightId = ref(null);
const reviewHistoryVisible = ref(false);
const reviewHistory = ref([]);
const dimensionScores = ref([]);
const finalScore = ref("");
const reviewComment = ref("");
const reportMarkdown = ref("");
const studentSearch = ref("");
const submissionQuery = reactive({
  studentNo: "",
  status: ""
});
const submissionStatusOptions = [
  { label: "已上传", value: "uploaded" },
  { label: "已解析", value: "parsed" },
  { label: "解析失败", value: "parse_failed" },
  { label: "评分中", value: "scoring" },
  { label: "已评分", value: "scored" },
  { label: "评分失败", value: "failed" },
  { label: "已复核", value: "reviewed" },
  { label: "已发布", value: "published" }
];
const assignmentForm = reactive({
  title: "Java OOP Homework",
  courseName: "",
  description: "请上传 zip 格式代码包",
  language: "java",
  classNames: props.user.className ? [props.user.className] : [],
  endTime: "",
  latePolicy: "forbid",
  latePenaltyPercent: 0,
  rubricTemplateId: null,
  selectedRubricItemIds: []
});
const templateForm = reactive({
  id: null,
  templateName: "",
  description: "",
  enabled: true,
  items: [emptyRubricTemplateItem()]
});
const reportHtml = computed(() => renderMarkdown(reportMarkdown.value || report.value?.reportMarkdown || ""));
const issueRows = computed(() => parseIssueRows(report.value?.suggestion));
const rubricPreviewJson = computed(() => formatJson(rubric.value?.rubricJson || rubric.value?.parsedJson || rubric.value));
const dimensionTotal = computed(() =>
  dimensionScores.value.reduce((sum, item) => sum + Number(item.score || 0), 0)
);
const manualScoreTotal = computed(() =>
  manualScoreRows.value.reduce((sum, item) => sum + Number(item.score || 0), 0)
);
const isAdmin = computed(() => props.user.role === "admin");
const teacherSubtitle = computed(() => {
  const parts = [];
  if (props.user.college) parts.push(props.user.college);
  if (props.user.teachingClass || props.user.className) parts.push(`教授班级 ${props.user.teachingClass || props.user.className}`);
  return parts.join(" · ") || "教师资料待完善";
});
const classOptions = computed(() => {
  const values = new Set();
  if (props.user.className) values.add(props.user.className);
  students.value.forEach((student) => {
    if (student.className) values.add(student.className);
  });
  assignments.value.forEach((assignment) => {
    assignmentClassNames(assignment).forEach((className) => values.add(className));
  });
  return Array.from(values).sort();
});
const filteredStudents = computed(() => {
  const keyword = studentSearch.value.trim().toLowerCase();
  if (!keyword) return students.value;
  return students.value.filter((student) =>
    [student.username, student.realName, student.className]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword))
  );
});
const canResetFilteredStudents = computed(() => filteredStudents.value.length > 0);
const publishTargets = computed(() => selectedRowsOrCurrent());
const publishButtonText = computed(() => (publishTargets.value.length > 1 ? "批量推送" : "推送给学生"));
const selectedAssignmentRecord = computed(() => assignments.value.find((item) => item.id === editingAssignmentId.value) || null);
const rubricStoragePrefix = computed(() => `ai-code-grading:rubric:${props.user.id || props.user.username || "teacher"}`);
const quotaWarningText = computed(() => {
  if (!tokenQuota.value) return "";
  if (tokenQuota.value.quotaExceeded) return "DeepSeek token 配额已用尽，请暂停远程评分或切换本地模型。";
  if (tokenQuota.value.warningLevel === "critical") return "DeepSeek token 配额已超过 90%，请控制远程评分用量。";
  return "DeepSeek token 配额已超过 80%，请关注后续评分用量。";
});
const compareLeftReport = computed(() => reportHistory.value.find((item) => item.id === compareLeftId.value) || null);
const compareRightReport = computed(() => reportHistory.value.find((item) => item.id === compareRightId.value) || null);
const compareDimensionRows = computed(() => buildCompareDimensionRows(compareLeftReport.value, compareRightReport.value));
const compareLeftIssues = computed(() => parseIssueRows(compareLeftReport.value?.suggestion));
const compareRightIssues = computed(() => parseIssueRows(compareRightReport.value?.suggestion));
const compareLeftMarkdown = computed(() => renderMarkdown(compareLeftReport.value?.reportMarkdown || ""));
const compareRightMarkdown = computed(() => renderMarkdown(compareRightReport.value?.reportMarkdown || ""));
const reviewSubmissionRows = computed(() =>
  submissions.value.filter((item) => item.currentReportId || ["scored", "reviewed", "published"].includes(item.status))
);
const selectedSubmissionRow = computed(() =>
  submissions.value.find((item) => item.id === selectedSubmission.value) || null
);
const selectedRubricTemplate = computed(() =>
  rubricTemplates.value.find((item) => item.id === assignmentForm.rubricTemplateId) || null
);
const latestBatchPercent = computed(() => {
  const total = Number(taskProgress.value?.latestBatchTotal || 0);
  if (!total) return 0;
  const counts = taskProgress.value?.latestBatchCounts || {};
  const done = Number(counts.success || 0) + Number(counts.failed || 0);
  return Math.min(100, Math.round((done / total) * 100));
});
watch(dimensionTotal, (value) => {
  if (dimensionScores.value.length) {
    finalScore.value = value.toFixed(2);
  }
});

onMounted(() => {
  refreshAssignments();
  refreshStudents();
  refreshRubricTemplates();
  refreshTokenQuota();
  refreshTokenStats();
});

onUnmounted(() => stopScoringPolling());

watch(selectedAssignment, () => {
  selectedSubmission.value = null;
  scoringSelection.value = [];
  downloadSelection.value = [];
  report.value = null;
  reportMarkdown.value = "";
  refreshSubmissions();
  refreshActiveRubric();
});

async function refreshAssignments() {
  try {
    assignments.value = await props.api.get("/api/v1/assignments");
    if (!selectedAssignment.value && assignments.value[0]) {
      selectedAssignment.value = assignments.value[0].id;
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function refreshSubmissions() {
  if (!selectedAssignment.value) {
    assignmentStats.value = null;
    return;
  }
  try {
    const [nextSubmissions, nextTasks, nextStats, nextProgress] = await Promise.all([
      props.api.get(submissionListUrl()),
      props.api.get(`/api/v1/ai-tasks?assignment_id=${selectedAssignment.value}`),
      props.api.get(`/api/v1/assignments/${selectedAssignment.value}/stats`),
      props.api.get(`/api/v1/ai-tasks/progress?assignment_id=${selectedAssignment.value}`)
    ]);
    await replaceSubmissionRows(nextSubmissions);
    await syncSubmissionTableSelections();
    tasks.value = nextTasks;
    assignmentStats.value = nextStats;
    taskProgress.value = nextProgress;
    if (!selectedSubmission.value && nextSubmissions[0]) {
      selectedSubmission.value = nextSubmissions[0].id;
    }
    watchRunningTasks(nextTasks);
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function submissionListUrl() {
  const params = new URLSearchParams();
  params.set("assignment_id", selectedAssignment.value);
  if (submissionQuery.studentNo.trim()) {
    params.set("student_no", submissionQuery.studentNo.trim());
  }
  if (submissionQuery.status) {
    params.set("status", submissionQuery.status);
  }
  return `/api/v1/submissions?${params.toString()}`;
}

function watchRunningTasks(nextTasks) {
  const activeIds = nextTasks
    .filter((task) => ["pending", "running"].includes(task.status))
    .map((task) => task.id)
    .filter(Boolean);
  if (!activeIds.length || scoringPolling.value) return;
  scoringWatchIds.value = activeIds;
  startScoringPolling();
}

async function refreshActiveRubric() {
  if (!selectedAssignment.value) {
    rubric.value = null;
    return;
  }
  const assignmentId = selectedAssignment.value;
  rubric.value = readCachedRubric(assignmentId);
  try {
    const activeRubric = await props.api.get(`/api/v1/rubrics/active?assignmentId=${assignmentId}`);
    if (selectedAssignment.value !== assignmentId) return;
    rubric.value = activeRubric || null;
    if (activeRubric) {
      cacheRubric(assignmentId, activeRubric);
    } else {
      removeCachedRubric(assignmentId);
    }
  } catch (error) {
    if (!rubric.value) {
      ElMessage.error(messageOf(error));
    }
  }
}

async function refreshStudents() {
  try {
    students.value = await props.api.get("/api/v1/users/students");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function refreshRubricTemplates() {
  try {
    rubricTemplates.value = await props.api.get("/api/v1/rubric-templates");
  } catch {
    rubricTemplates.value = [];
  }
}

async function refreshTokenQuota() {
  try {
    tokenQuota.value = await props.api.get("/api/v1/ai-reports/token-quota");
  } catch (error) {
    tokenQuota.value = null;
  }
}

async function refreshTokenStats() {
  try {
    tokenStats.value = await props.api.get("/api/v1/ai-reports/token-stats");
  } catch (error) {
    tokenStats.value = null;
  }
}

async function createAssignment(published) {
  if (!isAdmin.value) return;
  if (!validateAssignmentTemplateSelection(published)) return;
  if (!validateAssignmentClasses()) return;
  try {
    const created = await props.api.post("/api/v1/assignments", { ...assignmentForm, published });
    assignments.value = [created, ...assignments.value];
    selectedAssignment.value = created.id;
    editingAssignmentId.value = created.id;
    ElMessage.success(published ? "作业已创建并发布" : "草稿已创建");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function selectAssignment(row) {
  selectedAssignment.value = row?.id || null;
  assignmentStats.value = null;
  if (row) {
    editingAssignmentId.value = row.id;
    assignmentForm.title = row.title || "";
    assignmentForm.courseName = row.courseName || "";
    assignmentForm.description = row.description || "";
    assignmentForm.language = row.language || "java";
    assignmentForm.classNames = assignmentClassNames(row);
    assignmentForm.endTime = row.endTime || "";
    assignmentForm.latePolicy = row.latePolicy || "forbid";
    assignmentForm.latePenaltyPercent = row.latePenaltyPercent || 0;
    assignmentForm.rubricTemplateId = row.rubricTemplateId || null;
    assignmentForm.selectedRubricItemIds = parseSelectedRubricItemIds(row.selectedRubricItemIds);
    if (assignmentForm.rubricTemplateId) {
      loadRubricTemplateItems(assignmentForm.rubricTemplateId, false);
    } else {
      rubricTemplateItems.value = [];
    }
  }
  selectedSubmission.value = null;
  report.value = null;
}

async function saveAssignment() {
  if (!isAdmin.value) return;
  if (!editingAssignmentId.value) {
    await createAssignment(true);
    return;
  }
  if (!validateAssignmentTemplateSelection(false)) return;
  if (!validateAssignmentClasses()) return;
  try {
    const updated = await props.api.put(`/api/v1/assignments/${editingAssignmentId.value}`, {
      ...assignmentForm,
      published: false
    });
    assignments.value = assignments.value.map((item) => (item.id === updated.id ? updated : item));
    ElMessage.success("作业已保存");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function publishAssignment() {
  if (!isAdmin.value) return;
  if (!editingAssignmentId.value) return;
  try {
    const updated = await props.api.patch(`/api/v1/assignments/${editingAssignmentId.value}/publish`, {});
    assignments.value = assignments.value.map((item) => (item.id === updated.id ? updated : item));
    selectedAssignment.value = updated.id;
    ElMessage.success("作业已发布，学生端可见");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function deleteAssignment(row) {
  if (!isAdmin.value || !row?.id) return;
  try {
    await ElMessageBox.confirm(`确认删除作业“${row.title}”？相关提交、AI 报告和发布记录也会一并删除。`, "删除作业", {
      type: "warning",
      confirmButtonText: "确认删除",
      cancelButtonText: "取消"
    });
    await props.api.delete(`/api/v1/assignments/${row.id}`);
    assignments.value = assignments.value.filter((item) => item.id !== row.id);
    if (editingAssignmentId.value === row.id) {
      resetAssignmentForm();
    }
    selectedAssignment.value = assignments.value[0]?.id || null;
    await refreshSubmissions();
    ElMessage.success("作业已删除");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

function resetAssignmentForm() {
  editingAssignmentId.value = null;
  selectedAssignment.value = null;
  assignmentForm.title = "";
  assignmentForm.courseName = "";
  assignmentForm.description = "";
  assignmentForm.language = "java";
  assignmentForm.classNames = props.user.className ? [props.user.className] : [];
  assignmentForm.endTime = "";
  assignmentForm.latePolicy = "forbid";
  assignmentForm.latePenaltyPercent = 0;
  assignmentForm.rubricTemplateId = null;
  assignmentForm.selectedRubricItemIds = [];
  rubricTemplateItems.value = [];
}

function parseSelectedRubricItemIds(value) {
  if (!value) return [];
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return Array.isArray(parsed) ? parsed.map(Number).filter(Boolean) : [];
  } catch {
    return [];
  }
}

function validateAssignmentTemplateSelection(requireTemplate = false) {
  if (requireTemplate && !assignmentForm.rubricTemplateId) {
    ElMessage.warning("发布作业前请选择评分模板");
    return false;
  }
  if (assignmentForm.rubricTemplateId && assignmentForm.selectedRubricItemIds.length === 0) {
    ElMessage.warning("请选择至少一个评分点");
    return false;
  }
  return true;
}

function validateAssignmentClasses() {
  assignmentForm.classNames = assignmentForm.classNames
    .map((item) => (item || "").trim())
    .filter(Boolean)
    .filter((item, index, all) => all.indexOf(item) === index);
  if (!assignmentForm.classNames.length) {
    ElMessage.warning("请选择至少一个发布班级");
    return false;
  }
  return true;
}

function assignmentClassNames(assignment) {
  if (!assignment) return [];
  if (Array.isArray(assignment.classNames) && assignment.classNames.length) {
    return assignment.classNames.filter(Boolean);
  }
  if (!assignment.className) return [];
  return String(assignment.className)
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function displayAssignmentClasses(assignment) {
  return assignmentClassNames(assignment).join("、");
}

async function onAssignmentTemplateChange(templateId) {
  assignmentForm.selectedRubricItemIds = [];
  if (!templateId) {
    rubricTemplateItems.value = [];
    return;
  }
  await loadRubricTemplateItems(templateId, true);
}

async function loadRubricTemplateItems(templateId, selectEnabled) {
  try {
    rubricTemplateItems.value = await props.api.get(`/api/v1/rubric-templates/${templateId}/items`);
    if (selectEnabled) {
      assignmentForm.selectedRubricItemIds = rubricTemplateItems.value
        .filter((item) => item.enabled === 1 || item.enabled === true)
        .map((item) => item.id);
    }
  } catch (error) {
    rubricTemplateItems.value = [];
    ElMessage.error(messageOf(error));
  }
}

function emptyRubricTemplateItem(order = 1) {
  return {
    dimensionOrder: order,
    dimensionName: "",
    pointOrder: 1,
    pointName: "",
    pointScore: 10,
    criteria: "",
    enabled: true
  };
}

function resetRubricTemplateForm() {
  templateForm.id = null;
  templateForm.templateName = "";
  templateForm.description = "";
  templateForm.enabled = true;
  templateForm.items = [emptyRubricTemplateItem()];
}

function editRubricTemplate(row) {
  if (!row) return;
  templateForm.id = row.id;
  templateForm.templateName = row.templateName || "";
  templateForm.description = row.description || "";
  templateForm.enabled = Boolean(row.enabled);
  templateForm.items = (row.items && row.items.length ? row.items : [emptyRubricTemplateItem()]).map((item, index) => ({
    dimensionOrder: item.dimensionOrder || index + 1,
    dimensionName: item.dimensionName || "",
    pointOrder: item.pointOrder || 1,
    pointName: item.pointName || "",
    pointScore: Number(item.pointScore || 10),
    criteria: item.criteria || "",
    enabled: item.enabled === undefined ? true : Boolean(item.enabled)
  }));
}

function addRubricTemplateItem() {
  templateForm.items.push(emptyRubricTemplateItem(templateForm.items.length + 1));
}

function removeRubricTemplateItem(index) {
  if (templateForm.items.length === 1) {
    ElMessage.warning("至少保留一个评分点");
    return;
  }
  templateForm.items.splice(index, 1);
}

async function saveRubricTemplate() {
  if (!isAdmin.value) return;
  if (!templateForm.templateName.trim()) {
    ElMessage.warning("请输入模板名称");
    return;
  }
  templateSaving.value = true;
  const payload = {
    templateName: templateForm.templateName,
    description: templateForm.description,
    enabled: templateForm.enabled,
    items: templateForm.items.map((item, index) => ({
      ...item,
      dimensionOrder: Number(item.dimensionOrder || index + 1),
      pointOrder: Number(item.pointOrder || 1),
      pointScore: Number(item.pointScore || 0)
    }))
  };
  try {
    if (templateForm.id) {
      await props.api.put(`/api/v1/rubric-templates/${templateForm.id}`, payload);
    } else {
      await props.api.post("/api/v1/rubric-templates", payload);
    }
    await refreshRubricTemplates();
    resetRubricTemplateForm();
    ElMessage.success("评分模板已保存");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    templateSaving.value = false;
  }
}

async function uploadRubricTemplate(uploadFile) {
  if (!isAdmin.value || !uploadFile.raw) return;
  templateImporting.value = true;
  const form = new FormData();
  form.append("file", uploadFile.raw);
  try {
    const created = await props.api.upload("/api/v1/rubric-templates/upload", form);
    await refreshRubricTemplates();
    editRubricTemplate(created);
    ElMessage.success(`已导入 ${created.items?.length || 0} 个评分点`);
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    templateImporting.value = false;
  }
}

async function toggleRubricTemplate(row) {
  try {
    await props.api.patch(`/api/v1/rubric-templates/${row.id}/enabled?enabled=${row.enabled}`, {});
    ElMessage.success(row.enabled ? "模板已启用" : "模板已停用");
  } catch (error) {
    row.enabled = !row.enabled;
    ElMessage.error(messageOf(error));
  }
}

async function deleteRubricTemplate(row) {
  try {
    await ElMessageBox.confirm(`确认删除评分模板“${row.templateName}”？`, "删除模板", {
      type: "warning",
      confirmButtonText: "确认删除",
      cancelButtonText: "取消"
    });
    await props.api.delete(`/api/v1/rubric-templates/${row.id}`);
    await refreshRubricTemplates();
    if (templateForm.id === row.id) {
      resetRubricTemplateForm();
    }
    ElMessage.success("评分模板已删除");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

function selectSubmission(row) {
  selectedSubmission.value = row?.id || null;
  report.value = null;
  reportMarkdown.value = "";
}

async function openReviewSubmission(row) {
  if (!row?.id) return;
  selectedSubmission.value = row.id;
  await loadReport(row.id);
}

function reviewRowClassName({ row }) {
  return row.id === selectedSubmission.value ? "active-review-row" : "";
}

function selectScoringRows(rows) {
  if (syncingSubmissionSelection) return;
  scoringSelection.value = rows;
}

function selectDownloadRows(rows) {
  if (syncingSubmissionSelection) return;
  downloadSelection.value = rows;
}

function keepRowsById(selectedRows, nextRows) {
  const selectedIds = new Set(selectedRows.map((row) => String(row.id)));
  return nextRows.filter((row) => selectedIds.has(String(row.id)));
}

async function replaceSubmissionRows(nextRows) {
  const previousScoringSelection = scoringSelection.value;
  const previousDownloadSelection = downloadSelection.value;
  syncingSubmissionSelection = true;
  try {
    submissions.value = nextRows;
    scoringSelection.value = keepRowsById(previousScoringSelection, nextRows);
    downloadSelection.value = keepRowsById(previousDownloadSelection, nextRows);
    await nextTick();
  } finally {
    syncingSubmissionSelection = false;
  }
}

async function syncSubmissionTableSelections() {
  await nextTick();
  syncingSubmissionSelection = true;
  try {
    syncTableSelection(scoringTable.value, scoringSelection.value);
    syncTableSelection(downloadTable.value, downloadSelection.value);
    await nextTick();
  } finally {
    syncingSubmissionSelection = false;
  }
}

function syncTableSelection(table, selectedRows) {
  if (!table) return;
  table.clearSelection();
  selectedRows.forEach((row) => table.toggleRowSelection(row, true));
}

async function selectStudentFile(uploadFile) {
  if (!uploadFile.raw) return;
  const form = new FormData();
  form.append("file", uploadFile.raw);
  studentUploadProgress.value = 1;
  try {
    await props.api.upload("/api/v1/users/batch-import", form, {
      onProgress: (percent) => {
        studentUploadProgress.value = percent;
      }
    });
    await refreshStudents();
    ElMessage.success("学生导入完成");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    resetProgressLater(studentUploadProgress);
  }
}

function resetProgressLater(progressRef) {
  window.setTimeout(() => {
    progressRef.value = 0;
  }, 600);
}

function rubricStorageKey(assignmentId) {
  return `${rubricStoragePrefix.value}:${assignmentId}`;
}

function cacheRubric(assignmentId, value) {
  if (!assignmentId || !value) return;
  try {
    localStorage.setItem(rubricStorageKey(assignmentId), JSON.stringify(value));
  } catch {
    // localStorage may be unavailable in private browsing; backend restore still works.
  }
}

function readCachedRubric(assignmentId) {
  if (!assignmentId) return null;
  try {
    const raw = localStorage.getItem(rubricStorageKey(assignmentId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function removeCachedRubric(assignmentId) {
  if (!assignmentId) return;
  try {
    localStorage.removeItem(rubricStorageKey(assignmentId));
  } catch {
    // ignore storage cleanup errors
  }
}

async function resetStudentPassword(student) {
  try {
    await props.api.post(`/api/v1/users/reset-password/${student.id}`, {});
    await refreshStudents();
    ElMessage.success("密码已重置");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function resetAllStudentPasswords() {
  try {
    await ElMessageBox.confirm(
      `确认将当前可见的 ${filteredStudents.value.length} 名学生全部重置密码？此操作会让学生旧登录状态失效。`,
      "全部重置密码",
      {
        type: "warning",
        confirmButtonText: "确认全部重置",
        cancelButtonText: "取消"
      }
    );
    const result = await props.api.post("/api/v1/users/reset-password-all", {
      studentIds: filteredStudents.value.map((student) => student.id)
    });
    await refreshStudents();
    ElMessage.success(`已重置 ${result.resetCount || 0} 名学生密码`);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function previewFileCleanup() {
  try {
    cleanupPreview.value = await props.api.get(`/api/v1/files/cleanup-preview?olderThanDays=${cleanupOlderThanDays.value}`);
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function executeFileCleanup() {
  if (!cleanupPreview.value?.candidateCount) return;
  try {
    await ElMessageBox.confirm(
      `确认清理 ${cleanupPreview.value.candidateCount} 个早于 ${cleanupOlderThanDays.value} 天的上传文件？`,
      "文件清理",
      {
        type: "warning",
        confirmButtonText: "确认清理",
        cancelButtonText: "取消"
      }
    );
    cleanupPreview.value = await props.api.post(`/api/v1/files/cleanup?olderThanDays=${cleanupOlderThanDays.value}`, {});
    ElMessage.success(`已清理 ${cleanupPreview.value.deletedCount || 0} 个文件`);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function startScoring() {
  if (!selectedAssignment.value) return;
  if (scoringSelection.value.length === 0) {
    ElMessage.warning("请先勾选待评分提交");
    return;
  }
  scoringPreviewVisible.value = true;
}

async function openManualScoring() {
  const targets = selectedRowsOrCurrent();
  if (targets.length !== 1) {
    ElMessage.warning("请先选择一份提交进行手动评分");
    return;
  }
  const row = targets[0];
  if (row.publishStatus === 1) {
    ElMessage.warning("成绩已发布，需撤回后才能手动评分");
    return;
  }
  selectedSubmission.value = row.id;
  if (!rubric.value) {
    await refreshActiveRubric();
  }
  const rows = rubricScoreRows();
  if (!rows.length) {
    ElMessage.warning("当前作业没有可用评分点");
    return;
  }
  manualScoreRows.value = rows;
  manualIssueRows.value = [];
  manualScoringVisible.value = true;
}

function rubricScoreRows() {
  const raw = rubric.value?.rubricJson || rubric.value?.parsedJson || rubric.value;
  if (!raw) return [];
  try {
    const parsed = typeof raw === "string" ? JSON.parse(raw) : raw;
    const dimensions = Array.isArray(parsed?.dimensions) ? parsed.dimensions : [];
    return dimensions.map((item) => ({
      name: item.name || item.dimension || "评分点",
      score: 0,
      max_score: Number(item.max_score ?? item.maxScore ?? item.weight ?? 100),
      comment: item.criteria ? `依据评分标准：${item.criteria}` : "教师手动评分。"
    }));
  } catch {
    return [];
  }
}

function addManualIssue() {
  manualIssueRows.value.push({
    severity: "suggestion",
    file: "project",
    line: 1,
    description: ""
  });
}

function removeManualIssue(index) {
  manualIssueRows.value.splice(index, 1);
}

async function saveManualScoring() {
  if (!selectedSubmission.value || !manualScoreRows.value.length) return;
  manualScoringSubmitting.value = true;
  try {
    const saved = await props.api.post(`/api/v1/ai-reports/${selectedSubmission.value}/manual`, {
      totalScore: Number(manualScoreTotal.value.toFixed(2)),
      dimensionScores: manualScoreRows.value,
      issues: manualIssueRows.value.filter((item) => (item.description || "").trim())
    });
    manualScoringVisible.value = false;
    report.value = saved;
    dimensionScores.value = parseDimensionScores(saved.scoreDetailJson || saved.scoreJson);
    finalScore.value = dimensionScores.value.length
      ? dimensionTotal.value.toFixed(2)
      : String(saved.totalScore ?? "");
    reviewComment.value = "教师手动评分。";
    reportMarkdown.value = saved.reportMarkdown || "";
    await refreshSubmissions();
    emit("switch-module", "review");
    ElMessage.success("手动评分已保存，可继续复核发布");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    manualScoringSubmitting.value = false;
  }
}

async function confirmScoring() {
  if (!selectedAssignment.value || scoringSelection.value.length === 0) return;
  scoringSubmitting.value = true;
  try {
    const createdTasks = await props.api.post("/api/v1/ai-tasks/batch-score", {
      assignmentId: selectedAssignment.value,
      submissionIds: scoringSelection.value.map((item) => item.id),
      jointReview: jointReviewEnabled.value
    });
    scoringPreviewVisible.value = false;
    scoringWatchIds.value = createdTasks.map((task) => task.id).filter(Boolean);
    await refreshSubmissions();
    startScoringPolling();
    ElMessage.success("AI 评分任务已发起");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    scoringSubmitting.value = false;
  }
}

function startScoringPolling() {
  if (!scoringWatchIds.value.length) return;
  stopScoringPolling(false);
  scoringPolling.value = true;
  scoringPollTimer = window.setInterval(checkScoringProgress, 3000);
  checkScoringProgress();
}

function stopScoringPolling(resetWatch = true) {
  if (scoringPollTimer) {
    window.clearInterval(scoringPollTimer);
    scoringPollTimer = null;
  }
  scoringPolling.value = false;
  if (resetWatch) {
    scoringWatchIds.value = [];
  }
}

async function checkScoringProgress() {
  if (!selectedAssignment.value || !scoringWatchIds.value.length) {
    stopScoringPolling();
    return;
  }
  try {
    const [nextTasks, nextSubmissions, nextStats, nextProgress] = await Promise.all([
      props.api.get(`/api/v1/ai-tasks?assignment_id=${selectedAssignment.value}`),
      props.api.get(submissionListUrl()),
      props.api.get(`/api/v1/assignments/${selectedAssignment.value}/stats`),
      props.api.get(`/api/v1/ai-tasks/progress?assignment_id=${selectedAssignment.value}`)
    ]);
    tasks.value = nextTasks;
    await replaceSubmissionRows(nextSubmissions);
    await syncSubmissionTableSelections();
    assignmentStats.value = nextStats;
    taskProgress.value = nextProgress;
    refreshTokenQuota();
    refreshTokenStats();
    const watched = nextTasks.filter((task) => scoringWatchIds.value.includes(task.id));
    if (!watched.length) return;
    const done = watched.filter((task) => ["success", "failed"].includes(task.status));
    if (done.length === scoringWatchIds.value.length) {
      stopScoringPolling();
      const failed = done.filter((task) => task.status === "failed").length;
      if (failed) {
        ElMessage.warning(`AI 评分完成，${failed} 个任务失败`);
      } else {
        ElMessage.success("AI 评分已全部完成");
      }
    }
  } catch (error) {
    stopScoringPolling(false);
    ElMessage.error(messageOf(error));
  }
}

async function retryTask(taskId) {
  try {
    await props.api.post(`/api/v1/ai-tasks/${taskId}/retry`, {});
    await refreshSubmissions();
    ElMessage.success("失败任务已重试");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function returnSubmission(row) {
  if (!row?.id) return;
  try {
    await ElMessageBox.confirm(
      `确认将 ${row.studentUsername || ""}-${row.studentRealName || ""} 的作业打回重交？之前提交的文件、AI 报告和日志会被物理删除。`,
      "打回重交",
      {
        type: "warning",
        confirmButtonText: "确认打回",
        cancelButtonText: "取消"
      }
    );
    await props.api.post(`/api/v1/submissions/${row.id}/return`, {});
    await refreshSubmissions();
    ElMessage.success("已打回重交");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function loadTaskLogs(taskId) {
  try {
    taskLogs.value = await props.api.get(`/api/v1/ai-tasks/${taskId}/logs`);
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function loadReport(submissionId = selectedSubmission.value) {
  if (!submissionId) return;
  selectedSubmission.value = submissionId;
  try {
    report.value = await props.api.get(`/api/v1/ai-reports/${submissionId}`);
    dimensionScores.value = parseDimensionScores(report.value.scoreDetailJson || report.value.scoreJson);
    finalScore.value = dimensionScores.value.length
      ? dimensionTotal.value.toFixed(2)
      : String(report.value.totalScore ?? "");
    reviewComment.value = "已复核 AI 初评。";
    reportMarkdown.value = report.value.reportMarkdown || "";
    await loadExistingReview();
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function loadReportHistory() {
  if (!selectedSubmission.value) {
    ElMessage.warning("请先选择一条提交记录");
    return;
  }
  try {
    reportHistory.value = await props.api.get(`/api/v1/ai-reports/${selectedSubmission.value}/history`);
    reportHistoryVisible.value = true;
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function openReportCompare() {
  if (!selectedSubmission.value) {
    ElMessage.warning("请先选择一条提交记录");
    return;
  }
  try {
    reportHistory.value = await props.api.get(`/api/v1/ai-reports/${selectedSubmission.value}/history`);
    if (reportHistory.value.length < 2) {
      reportCompareVisible.value = true;
      return;
    }
    compareLeftId.value = reportHistory.value[0]?.id || null;
    compareRightId.value = reportHistory.value.find((item) => item.id !== compareLeftId.value)?.id || null;
    reportCompareVisible.value = true;
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function useHistoryReport(row) {
  report.value = row;
  dimensionScores.value = parseDimensionScores(row.scoreDetailJson || row.scoreJson);
  finalScore.value = dimensionScores.value.length
    ? dimensionTotal.value.toFixed(2)
    : String(row.totalScore ?? "");
  reportCommentFromHistory();
  reportMarkdown.value = row.reportMarkdown || "";
  reportHistoryVisible.value = false;
}

async function loadReviewHistory() {
  if (!selectedSubmission.value) {
    ElMessage.warning("请先选择一条提交记录");
    return;
  }
  try {
    reviewHistory.value = await props.api.get(`/api/v1/teacher-reviews/${selectedSubmission.value}/history`);
    reviewHistoryVisible.value = true;
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function useReviewHistory(row) {
  dimensionScores.value = parseDimensionScores(row.modifiedJson || report.value?.scoreDetailJson || report.value?.scoreJson);
  finalScore.value = String(row.finalScore ?? finalScore.value);
  reviewComment.value = row.finalComment || "";
  reportMarkdown.value = row.modifiedMarkdown || reportMarkdown.value;
  reviewHistoryVisible.value = false;
}

function reportCommentFromHistory() {
  if (!reviewComment.value) {
    reviewComment.value = "已复核 AI 初评。";
  }
}

async function loadExistingReview() {
  if (!selectedSubmission.value) return;
  try {
    const review = await props.api.get(`/api/v1/teacher-reviews/${selectedSubmission.value}`);
    dimensionScores.value = parseDimensionScores(review.modifiedJson || report.value?.scoreDetailJson || report.value?.scoreJson);
    finalScore.value = String(review.finalScore ?? finalScore.value);
    reviewComment.value = review.finalComment || reviewComment.value;
    reportMarkdown.value = review.modifiedMarkdown || reportMarkdown.value;
  } catch {
    // A submission without a saved review should still open the AI report for first-time review.
  }
}

async function saveReview() {
  if (!selectedSubmission.value || !report.value) return;
  try {
    await props.api.put(`/api/v1/teacher-reviews/${selectedSubmission.value}`, {
      finalScore: Number(finalScore.value || report.value.totalScore),
      finalComment: reviewComment.value,
      modifiedJson: JSON.stringify(dimensionScores.value.length ? dimensionScores.value : parseDimensionScores(report.value.scoreJson)),
      modifiedMarkdown: reportMarkdown.value || report.value.reportMarkdown || ""
    });
    await refreshSubmissions();
    ElMessage.success("教师复核已保存");
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
      score: Number(item.score || 0),
      max_score: Number(item.max_score ?? item.maxScore ?? item.weight ?? 100),
      comment: item.comment || item.feedback || ""
    }));
  } catch {
    return [];
  }
}

function parseIssueRows(value) {
  if (!value) return [];
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    if (!Array.isArray(parsed)) return [];
    return parsed.map((item) => ({
      severity: item.severity || "suggestion",
      file: item.file || "project",
      line: item.line ?? "-",
      description: item.description || item.message || ""
    }));
  } catch {
    return [];
  }
}

function buildCompareDimensionRows(leftReport, rightReport) {
  const left = parseDimensionScores(leftReport?.scoreDetailJson || leftReport?.scoreJson);
  const right = parseDimensionScores(rightReport?.scoreDetailJson || rightReport?.scoreJson);
  const names = [...new Set([...left.map((item) => item.name), ...right.map((item) => item.name)])];
  return names.map((name) => {
    const leftItem = left.find((item) => item.name === name) || {};
    const rightItem = right.find((item) => item.name === name) || {};
    const leftScore = Number(leftItem.score ?? 0);
    const rightScore = Number(rightItem.score ?? 0);
    return {
      name,
      leftScore,
      rightScore,
      delta: rightScore - leftScore,
      leftComment: leftItem.comment || "",
      rightComment: rightItem.comment || ""
    };
  });
}

function reportOptionLabel(item) {
  return `#${item.id} · ${item.modelName || "unknown"} · ${scoreText(item.totalScore)}分`;
}

function scoreText(value) {
  if (value === null || value === undefined || value === "") return "-";
  const number = Number(value);
  if (!Number.isFinite(number)) return String(value);
  return Number.isInteger(number) ? String(number) : number.toFixed(2);
}

function scoreDeltaText(left, right) {
  if (!left || !right) return "-";
  return signedNumber(Number(right.totalScore || 0) - Number(left.totalScore || 0));
}

function signedNumber(value) {
  const number = Number(value || 0);
  if (!Number.isFinite(number)) return "-";
  if (Math.abs(number) < 0.005) return "0";
  const text = Math.abs(number) >= 10 ? number.toFixed(1) : number.toFixed(2);
  return number > 0 ? `+${text}` : text;
}

function severityType(severity) {
  if (severity === "error") return "danger";
  if (severity === "warning") return "warning";
  return "info";
}

function formatJson(value) {
  if (!value) return "";
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return JSON.stringify(parsed, null, 2);
  } catch {
    return String(value);
  }
}

async function publishGrades(rows = null) {
  const targets = rows || publishTargets.value;
  const ids = targets.map((item) => item.id);
  if (ids.length === 0) {
    ElMessage.warning("请先选择或勾选已复核的提交");
    return;
  }
  try {
    await ElMessageBox.confirm(`确认推送 ${ids.length} 份已复核成绩给学生？`, "发布成绩", {
      type: "warning",
      confirmButtonText: "确认推送",
      cancelButtonText: "取消"
    });
    await props.api.post("/api/v1/grade-publish/push", { submissionIds: ids });
    await refreshSubmissions();
    ElMessage.success("成绩已发布");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function publishAllGrades() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  const assignment = assignments.value.find((item) => item.id === selectedAssignment.value);
  const reviewedCount = submissions.value.filter((item) => ["reviewed", "published"].includes(item.status)).length;
  try {
    await ElMessageBox.confirm(
      `确认推送当前作业${assignment?.className ? `（${assignment.className}）` : ""}的全部已复核成绩？当前列表中已复核 ${reviewedCount} 份。`,
      "一键推送",
      {
        type: "warning",
        confirmButtonText: "确认推送",
        cancelButtonText: "取消"
      }
    );
    const result = await props.api.post("/api/v1/grade-publish/push-all", { assignmentId: selectedAssignment.value });
    await refreshSubmissions();
    ElMessage.success(`已推送 ${result.length} 份成绩`);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function retractGrades(rows = null) {
  const targets = (rows || selectedPublishedRows()).filter((item) => item.publishStatus === 1 && item.publishId);
  if (targets.length === 0) {
    ElMessage.warning("请先选择已发布的成绩");
    return;
  }
  try {
    await ElMessageBox.confirm(`确认撤回 ${targets.length} 份已发布成绩？撤回后学生将不能查看该成绩。`, "撤回成绩", {
      type: "warning",
      confirmButtonText: "确认撤回",
      cancelButtonText: "取消"
    });
    await Promise.all(targets.map((item) => props.api.post(`/api/v1/grade-publish/retract/${item.publishId}`, {})));
    await refreshSubmissions();
    ElMessage.success("成绩已撤回");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

function selectedRowsOrCurrent() {
  if (activeTeacherModule.value === "review") {
    const current = submissions.value.find((item) => item.id === selectedSubmission.value);
    return current ? [current] : [];
  }
  if (scoringSelection.value.length) {
    return scoringSelection.value;
  }
  const current = submissions.value.find((item) => item.id === selectedSubmission.value);
  return current ? [current] : [];
}

function selectedPublishedRows() {
  return selectedRowsOrCurrent();
}

function publishStatusText(status) {
  if (status === 1) return "已发布";
  if (status === 2) return "已撤回";
  return "未发布";
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
  if (status === "failed") return "danger";
  if (status === "parse_failed") return "danger";
  return "info";
}

function taskStatusText(status) {
  if (status === "pending") return "等待中";
  if (status === "running") return "执行中";
  if (status === "success") return "成功";
  if (status === "failed") return "失败";
  return status || "未知";
}

function taskStatusType(status) {
  if (status === "success") return "success";
  if (status === "running") return "warning";
  if (status === "failed") return "danger";
  return "info";
}

function formatTokens(value) {
  const tokens = Number(value || 0);
  if (tokens >= 100000000) return `${(tokens / 100000000).toFixed(2)}亿`;
  if (tokens >= 10000) return `${(tokens / 10000).toFixed(2)}万`;
  return String(tokens);
}

function formatBytes(value) {
  const bytes = Number(value || 0);
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(2)} KB`;
  return `${bytes} B`;
}

function downloadStudentTemplate() {
  props.api.downloadGet("/api/v1/users/import-template", "students-template.xlsx");
}

function downloadSubmission(submissionId) {
  const row = submissions.value.find((item) => item.id === submissionId);
  props.api.downloadGet(`/api/v1/submissions/${submissionId}/download`, `${studentDownloadBase(row)}.zip`);
}

function downloadReport(submissionId) {
  const row = submissions.value.find((item) => item.id === submissionId);
  props.api.downloadGet(`/api/v1/submissions/${submissionId}/download-report`, `${studentDownloadBase(row)}-报告.pdf`);
}

async function downloadAllPackage() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-all`);
}

async function downloadAllReports() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-reports`, "报告包");
}

async function downloadAllCodes() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-codes`, "代码包");
}

async function downloadScoreSheet() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  const assignment = assignments.value.find((item) => item.id === selectedAssignment.value);
  packageDownloading.value = true;
  packageDownloadProgress.value = 1;
  try {
    await props.api.downloadGet(
      `/api/v1/assignments/${selectedAssignment.value}/score-sheet`,
      `${safeFilename(assignment?.title || "作业")}_成绩表.xlsx`,
      {
        onProgress: (percent) => {
          packageDownloadProgress.value = percent;
        }
      }
    );
    ElMessage.success("成绩表下载已开始");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    packageDownloading.value = false;
    resetProgressLater(packageDownloadProgress);
  }
}

async function downloadSelectedPackage() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  const studentIds = selectedDownloadStudentIds();
  if (studentIds.length === 0) {
    ElMessage.warning("请先勾选要下载的提交");
    return;
  }
  const params = new URLSearchParams();
  params.set("studentIds", studentIds.join(","));
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-selected?${params.toString()}`);
}

async function downloadSelectedReports() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  const studentIds = selectedDownloadStudentIds();
  if (!studentIds.length) {
    ElMessage.warning("请先勾选要下载的提交");
    return;
  }
  const params = new URLSearchParams();
  params.set("studentIds", studentIds.join(","));
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-reports?${params.toString()}`, "报告包");
}

async function downloadSelectedCodes() {
  if (!selectedAssignment.value) {
    ElMessage.warning("请先选择作业");
    return;
  }
  const studentIds = selectedDownloadStudentIds();
  if (!studentIds.length) {
    ElMessage.warning("请先勾选要下载的提交");
    return;
  }
  const params = new URLSearchParams();
  params.set("studentIds", studentIds.join(","));
  await downloadAssignmentPackage(`/api/v1/assignments/${selectedAssignment.value}/download-codes?${params.toString()}`, "代码包");
}

function selectedDownloadStudentIds() {
  return [...new Set(downloadSelection.value.map((item) => item.studentId).filter(Boolean))];
}

async function downloadAssignmentPackage(url, label = "提交包") {
  const assignment = assignments.value.find((item) => item.id === selectedAssignment.value);
  packageDownloading.value = true;
  packageDownloadProgress.value = 1;
  const loading = ElMessage({
    message: "正在打包下载，请稍候...",
    type: "info",
    duration: 0,
    showClose: true
  });
  try {
    await props.api.downloadGet(url, assignmentPackageFilename(assignment, label), {
      onProgress: (percent) => {
        packageDownloadProgress.value = percent;
      }
    });
    ElMessage.success("下载已开始");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    loading.close();
    packageDownloading.value = false;
    resetProgressLater(packageDownloadProgress);
  }
}

function assignmentPackageFilename(assignment, label = "提交包") {
  const title = safeFilename(assignment?.title || "作业提交");
  return `${title}_${label}_${new Date().toISOString().slice(0, 10)}.zip`;
}

function downloadSingle() {
  if (!selectedSubmission.value) {
    ElMessage.warning("请先选择一条提交记录");
    return;
  }
  const row = submissions.value.find((item) => item.id === selectedSubmission.value);
  const assignment = assignments.value.find((item) => item.id === selectedAssignment.value);
  const className = assignmentClassNames(assignment)[0] || "班级";
  const filename = row
    ? `${safeFilename(row.studentUsername || "student")}-${safeFilename(className)}-${safeFilename(row.studentRealName || row.id)}.pdf`
    : `submission-${selectedSubmission.value}.pdf`;
  props.api.download(`/api/v1/exports/pdf/single/${selectedSubmission.value}`, {}, filename);
}

function downloadBatch() {
  if (scoringSelection.value.length === 0) {
    ElMessage.warning("请先勾选要导出的提交");
    return;
  }
  props.api.download(
    "/api/v1/exports/pdf/batch",
    { submissionIds: scoringSelection.value.map((item) => item.id) },
    batchExportFilename()
  );
}

function batchExportFilename() {
  const assignment = assignments.value.find((item) => item.id === selectedAssignment.value);
  const title = safeFilename(assignment?.title || "评分报告");
  return `${title}_评分报告_${new Date().toISOString().slice(0, 10)}.zip`;
}

function safeFilename(value) {
  return String(value || "file").replace(/[\\/:*?"<>|\s]+/g, "_");
}

function studentDownloadBase(row) {
  if (!row) return "student";
  return `${safeFilename(row.studentUsername || row.studentId || "student")}-${safeFilename(row.studentRealName || row.id)}`;
}
</script>
