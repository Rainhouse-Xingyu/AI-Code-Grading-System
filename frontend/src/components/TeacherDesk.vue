<template>
  <section class="desk teacher-desk">
    <div class="page-heading teacher-page-heading">
      <div class="page-heading-copy">
        <h2>{{ activeTeacherModuleLabel }}</h2>
        <p>{{ teacherSubtitle }} · {{ selectedSemester?.name || "未选择学期" }} · 作业、评分与发布</p>
      </div>
      <div class="toolbar semester-toolbar">
        <el-select v-model="selectedSemesterId" size="small" placeholder="选择学期" style="width: 160px" @change="changeSemester">
          <el-option v-for="semester in semesters" :key="semester.id" :label="`${semester.name}${semester.status === 'archived' ? '（已归档）' : ''}`" :value="semester.id" />
        </el-select>
        <el-button size="small" @click="createSemester">新建学期</el-button>
        <el-button size="small" type="warning" :disabled="selectedSemester?.status !== 'active'" @click="archiveSemester">归档本学期</el-button>
        <el-button
          v-if="isAdmin && selectedSemester?.status === 'archived'"
          size="small"
          type="danger"
          plain
          :loading="semesterFileCleanupLoading"
          @click="openSemesterFileCleanup"
        >
          清理归档文件
        </el-button>
        <el-button @click="refreshAll">刷新</el-button>
      </div>
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

        <section v-show="activeTeacherModule === 'students'" class="teacher-module-panel students-module-panel">
          <div class="module-intro">
            <div class="module-intro-copy">
              <span class="module-kicker">ROSTER MANAGEMENT</span>
              <h3>学生名单与账号维护</h3>
              <p>集中完成学生检索、名单导入和密码维护，并在批量操作前确认影响范围。</p>
            </div>
            <div class="module-summary-grid student-summary-grid">
              <MetricCard label="学生总数" :value="students.length" />
              <MetricCard label="当前结果" :value="filteredStudents.length" />
              <MetricCard label="已选择" :value="studentSelection.length" />
              <MetricCard label="关联作业" :value="assignments.length" />
            </div>
          </div>

          <el-card shadow="never" class="data-panel students-data-panel">
            <template #header>
              <div class="panel-toolbar">
                <div class="panel-toolbar-main">
                  <strong>学生名单</strong>
                  <span>支持按学号、姓名或班级快速筛选</span>
                </div>
                <div class="panel-toolbar-actions">
                  <el-button @click="downloadStudentTemplate">下载模板</el-button>
                  <el-upload :show-file-list="false" :auto-upload="false" :on-change="selectStudentFile" accept=".xls,.xlsx">
                    <el-button type="primary" plain>导入学生 Excel</el-button>
                  </el-upload>
                  <el-button :disabled="!rubric" @click="rubricPreviewVisible = true">预览 Rubric</el-button>
                </div>
              </div>
            </template>

            <div class="student-filter-bar panel-toolbar">
              <div class="panel-toolbar-main student-filter-copy">
                <el-input v-model="studentSearch" class="student-search-input" clearable placeholder="搜索学号、姓名或班级" />
                <span>共 {{ filteredStudents.length }} 条匹配记录</span>
              </div>
              <div class="panel-toolbar-actions">
                <el-button type="warning" plain :disabled="!canResetFilteredStudents" @click="resetAllStudentPasswords">
                  重置当前结果密码
                </el-button>
              </div>
            </div>

            <div v-if="studentSelection.length" class="selection-action-bar">
              <div class="selection-action-copy">
                <strong>已选择 {{ studentSelection.length }} 名学生</strong>
                <span>批量删除会移除所选账号，请确认名单无误。</span>
              </div>
              <el-button type="danger" @click="deleteSelectedStudents">删除所选</el-button>
            </div>

            <div class="upload-progress-stack">
              <el-progress v-if="studentUploadProgress > 0 && studentUploadProgress < 100" :percentage="studentUploadProgress" />
            </div>
            <div class="table-shell student-table-shell">
              <el-table
                :data="filteredStudents"
                height="360"
                class="student-table"
                empty-text="没有符合条件的学生"
                @selection-change="studentSelection = $event"
              >
                <el-table-column type="selection" width="48" />
                <el-table-column prop="username" label="学号" min-width="120" />
                <el-table-column prop="realName" label="姓名" min-width="100" />
                <el-table-column prop="className" label="班级" min-width="140" />
                <el-table-column label="账号操作" width="110" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="resetStudentPassword(row)">重置密码</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>

          <section v-if="isAdmin" class="storage-cleanup-panel destructive-zone">
            <div class="panel-toolbar">
              <div class="panel-toolbar-main destructive-zone-copy">
                <span class="module-kicker">STORAGE CLEANUP</span>
                <strong>历史文件清理</strong>
                <span>先生成预览清单，再执行不可逆的文件删除。</span>
              </div>
              <div class="panel-toolbar-actions cleanup-toolbar">
                <span class="cleanup-age-label">保留最近</span>
                <el-input-number
                  v-model="cleanupOlderThanDays"
                  :min="1"
                  :max="3650"
                  size="small"
                  :disabled="cleanupPreviewLoading || cleanupExecuting"
                  @change="resetCleanupPreview"
                />
                <span class="cleanup-age-label">天</span>
                <el-button size="small" :loading="cleanupPreviewLoading" @click="previewFileCleanup">预览范围</el-button>
                <el-button
                  size="small"
                  type="danger"
                  :loading="cleanupExecuting"
                  :disabled="cleanupPreviewLoading || cleanupExecuting || !cleanupPreviewIsCurrent || !cleanupPreview?.candidateCount"
                  @click="executeFileCleanup"
                >
                  执行清理
                </el-button>
              </div>
            </div>
            <el-alert
              v-if="cleanupPreview"
              :title="cleanupPreviewMessage"
              :type="cleanupPreview.candidateCount ? 'warning' : 'success'"
              :closable="false"
              show-icon
            />
            <div v-if="cleanupPreview" class="cleanup-summary module-summary-grid">
              <MetricCard label="候选文件" :value="cleanupPreview.candidateCount" />
              <MetricCard label="候选容量" :value="formatBytes(cleanupPreview.candidateBytes)" />
              <MetricCard label="已删文件" :value="cleanupPreview.deletedCount || 0" />
            </div>
            <div v-if="cleanupPreview?.candidateFiles?.length" class="table-shell cleanup-table-shell">
              <el-table
                :data="cleanupPreview.candidateFiles"
                class="cleanup-file-table"
                size="small"
                max-height="260"
              >
                <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
                <el-table-column label="类型" width="110">
                  <template #default="{ row }">{{ cleanupFileType(row.fileType) }}</template>
                </el-table-column>
                <el-table-column label="大小" width="100">
                  <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
                </el-table-column>
                <el-table-column prop="createdAt" label="上传时间" min-width="150" />
                <el-table-column prop="relativePath" label="存储位置" min-width="220" show-overflow-tooltip />
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag :type="row.deletable ? 'success' : 'warning'" size="small">
                      {{ row.deletable ? "可清理" : row.skipReason || "已跳过" }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <el-empty
              v-else
              class="cleanup-empty empty-panel"
              :description="cleanupPreview ? '没有符合条件的可清理文件' : '点击预览后显示可清理文件明细'"
            />
          </section>
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
          <div class="module-intro">
            <div class="module-intro-copy">
              <span class="module-kicker">GRADING QUEUE</span>
              <h3>提交评分工作区</h3>
              <p>筛选本次作业提交，选择学生后发起 AI 评分、手动评分或批量发布。</p>
            </div>
            <div class="submission-overview-grid module-summary-grid">
              <MetricCard label="总提交" :value="submissionOverview.total" />
              <MetricCard label="已评分" :value="submissionOverview.scored" />
              <MetricCard label="AI 评分中" :value="submissionOverview.running" />
              <MetricCard label="待处理" :value="submissionOverview.pending" />
            </div>
          </div>

          <el-card shadow="never" class="submissions-card data-panel">
            <template #header>
              <div class="panel-toolbar submissions-toolbar">
                <div class="panel-toolbar-main submission-filters">
                  <div class="submission-assignment-picker">
                    <span>评分作业</span>
                    <el-select
                      v-model="selectedAssignment"
                      :loading="assignmentsLoading"
                      :disabled="assignmentsLoading || !assignments.length"
                      size="small"
                      filterable
                      class="submission-assignment-select"
                      placeholder="选择评分作业"
                      no-data-text="当前学期暂无作业"
                    >
                      <el-option
                        v-for="assignment in assignments"
                        :key="assignment.id"
                        :label="`${assignment.title || '未命名作业'}${assignment.status === 'draft' ? '（草稿）' : ''}`"
                        :value="assignment.id"
                      />
                    </el-select>
                  </div>
                  <el-input
                    v-model="submissionQuery.studentNo"
                    size="small"
                    class="submission-query-input"
                    clearable
                    placeholder="搜索学号"
                    @keyup.enter="refreshSubmissions"
                  />
                  <el-select
                    v-model="submissionQuery.status"
                    size="small"
                    clearable
                    class="submission-query-select"
                    placeholder="全部状态"
                  >
                    <el-option
                      v-for="item in submissionStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </el-select>
                  <el-button
                    size="small"
                    :loading="submissionsLoading"
                    :disabled="!selectedAssignment"
                    @click="refreshSubmissions"
                  >查询</el-button>
                </div>
                <div class="panel-toolbar-actions">
                  <el-button
                    size="small"
                    type="success"
                    :disabled="!selectedAssignment || submissionsLoading || !submissions.length"
                    @click="publishAllGrades"
                  >一键推送全部成绩</el-button>
                </div>
              </div>
            </template>

            <div class="selection-action-bar submission-selection-bar">
              <div class="selection-action-copy">
                <strong>已选择 {{ scoringSelection.length }} 份提交</strong>
                <span>{{ selectedSubmissionRow ? `当前：${selectedSubmissionRow.studentRealName || selectedSubmissionRow.studentUsername}` : "单击一行可设为当前提交" }}</span>
              </div>
              <div class="selection-action-controls">
                <el-checkbox v-model="jointReviewEnabled" size="small">联合评审</el-checkbox>
                <el-button size="small" type="primary" @click="startScoring">批量 AI 评分</el-button>
                <el-button size="small" @click="openManualScoring">手动评分</el-button>
              </div>
            </div>

            <div v-loading="submissionsLoading" class="table-shell submissions-table-shell">
              <el-table
                ref="scoringTable"
                class="submissions-table"
                :data="submissions"
                height="100%"
                row-key="id"
                highlight-current-row
                :empty-text="submissionEmptyText"
                @current-change="selectSubmission"
                @selection-change="selectScoringRows"
              >
                <el-table-column type="selection" width="44" />
                <el-table-column prop="studentUsername" label="学号" width="110" />
                <el-table-column prop="studentRealName" label="姓名" width="100" />
                <el-table-column prop="fileName" label="提交文件" min-width="180" show-overflow-tooltip />
                <el-table-column prop="submissionVersion" label="版本" width="70" />
                <el-table-column label="评分状态" width="104">
                  <template #default="{ row }">
                    <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="迟交" width="70">
                  <template #default="{ row }">
                    <el-tag v-if="row.late" type="warning" size="small">迟交</el-tag>
                    <span v-else class="muted-cell">正常</span>
                  </template>
                </el-table-column>
                <el-table-column label="发布状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="row.publishStatus === 1 ? 'success' : row.publishStatus === 2 ? 'info' : 'warning'" size="small">
                      {{ publishStatusText(row.publishStatus) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="180" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click.stop="downloadSubmission(row.id)">代码</el-button>
                    <el-button link type="warning" @click.stop="returnSubmission(row)">打回</el-button>
                    <el-button v-if="row.publishStatus !== 1" link type="primary" @click.stop="publishGrades([row])">推送</el-button>
                    <el-button v-if="row.publishStatus === 1" link type="danger" @click.stop="retractGrades([row])">撤回</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>
        </section>

        <section v-show="activeTeacherModule === 'tasks'" class="teacher-module-panel tasks-module-panel">
          <div class="module-intro task-module-intro">
            <div class="module-intro-copy">
              <span class="module-kicker">AI OPERATIONS</span>
              <h3>AI 评分任务监控</h3>
              <p>跟踪队列、批次进度与模型用量；失败任务可在任务列表中直接重试。</p>
            </div>
            <div v-if="taskProgress" class="task-progress-grid module-summary-grid">
              <MetricCard label="全部任务" :value="taskProgress.total || 0" />
              <MetricCard label="等待中" :value="taskProgress.statusCounts?.pending || 0" />
              <MetricCard label="执行中" :value="`${taskProgress.statusCounts?.running || 0}/${taskProgress.maxConcurrentTasks || 0}`" />
              <MetricCard label="已完成" :value="taskProgress.statusCounts?.success || 0" />
              <MetricCard label="失败" :value="taskProgress.statusCounts?.failed || 0" />
              <MetricCard label="已结束" :value="taskProgress.statusCounts?.cancelled || 0" />
            </div>
          </div>

          <el-card shadow="never" class="data-panel task-data-panel">
            <template #header>
              <div class="panel-toolbar">
                <div class="panel-toolbar-main">
                  <strong>任务队列</strong>
                  <span v-if="taskProgress?.latestBatchTotal">最近批次已完成 {{ latestBatchPercent }}%</span>
                  <span v-else>选择任务可查看完整运行日志</span>
                </div>
                <div class="panel-toolbar-actions">
                  <el-tag v-if="scoringPolling" size="small" type="warning" effect="light">实时同步中</el-tag>
                  <el-button
                    v-if="canCancelCurrentScoring"
                    class="task-stop-button"
                    size="small"
                    type="danger"
                    plain
                    :loading="cancellingScoring"
                    @click="cancelCurrentScoring"
                  >结束当前评分</el-button>
                </div>
              </div>
              <div v-if="taskProgress?.latestBatchTotal" class="task-progress-band">
                <div class="task-progress-labels">
                  <span>最近批次进度</span>
                  <strong>{{ latestBatchPercent }}%</strong>
                </div>
                <el-progress
                  class="task-batch-progress"
                  :percentage="latestBatchPercent"
                  :status="taskProgress.latestBatchCounts?.failed ? 'exception' : undefined"
                  :show-text="false"
                />
              </div>
            </template>

            <div class="table-shell task-table-shell">
              <el-table :data="tasks" height="300" empty-text="当前作业还没有 AI 评分任务">
                <el-table-column prop="id" label="任务" width="76" />
                <el-table-column prop="batchId" label="批次" min-width="150" show-overflow-tooltip />
                <el-table-column label="对应学生作业" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tooltip :content="taskStudentDetail(row)" placement="top">
                      <span>{{ taskStudentText(row) }}</span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column label="任务状态" width="104">
                  <template #default="{ row }">
                    <el-tag :type="taskStatusType(row.status)" size="small">{{ taskStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="loadTaskLogs(row.id)">查看日志</el-button>
                    <el-button v-if="row.status === 'failed'" link type="danger" @click="retryTask(row.id)">重试</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>

          <div class="task-insights-grid">
            <el-card shadow="never" class="data-panel token-panel">
              <template #header>
                <div class="panel-toolbar">
                  <div class="panel-toolbar-main">
                    <strong>模型用量</strong>
                    <span>DeepSeek 配额与报告消耗概览</span>
                  </div>
                </div>
              </template>
              <div v-if="tokenQuota" class="token-quota-grid module-summary-grid">
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
                  <strong>{{ formatTokens(tokenStats.totalTokens) }} / {{ tokenStats.reportCount || 0 }} 份报告</strong>
                </div>
                <div class="token-stats-tables">
                  <div class="table-shell">
                    <el-table :data="tokenStats.byModel || []" size="small" max-height="180" empty-text="暂无模型统计">
                      <el-table-column prop="modelName" label="模型" min-width="150" show-overflow-tooltip />
                      <el-table-column label="Token" width="92">
                        <template #default="{ row }">{{ formatTokens(row.tokenUsage) }}</template>
                      </el-table-column>
                      <el-table-column prop="reportCount" label="报告" width="70" />
                    </el-table>
                  </div>
                  <div class="table-shell">
                    <el-table :data="tokenStats.byAssignment || []" size="small" max-height="180" empty-text="暂无作业统计">
                      <el-table-column prop="title" label="作业" min-width="140" show-overflow-tooltip />
                      <el-table-column label="Token" width="92">
                        <template #default="{ row }">{{ formatTokens(row.tokenUsage) }}</template>
                      </el-table-column>
                    </el-table>
                  </div>
                </div>
              </div>
              <el-empty v-else class="empty-panel compact-empty" description="暂无 Token 使用统计" />
            </el-card>

            <el-card shadow="never" class="data-panel task-log-panel">
              <template #header>
                <div class="panel-toolbar">
                  <div class="panel-toolbar-main">
                    <strong>运行日志</strong>
                    <span>展示当前选中任务的执行详情</span>
                  </div>
                </div>
              </template>
              <div v-if="taskLogs.length" class="log-box">
                <p v-for="log in taskLogs" :key="log.id">
                  <strong>{{ log.level }}</strong>
                  <span v-if="log.modelName">[{{ log.modelName }}]</span>
                  {{ log.message }}
                  <span v-if="log.durationMs">· {{ log.durationMs }}ms</span>
                </p>
              </div>
              <el-empty v-else class="empty-panel compact-empty" description="点击任务行中的“查看日志”" />
            </el-card>
          </div>
        </section>

        <section v-show="activeTeacherModule === 'downloads'" class="teacher-module-panel downloads-module-panel">
          <div class="module-intro">
            <div class="module-intro-copy">
              <span class="module-kicker">EXPORT CENTER</span>
              <h3>作业文件与成绩导出</h3>
              <p>按作业导出完整资料，或勾选学生后生成指定范围的报告与代码包。</p>
            </div>
            <div class="module-summary-grid download-summary-grid">
              <MetricCard label="可下载提交" :value="submissions.length" />
              <MetricCard label="已选择" :value="downloadSelection.length" />
              <MetricCard label="作业数量" :value="assignments.length" />
            </div>
          </div>

          <el-card shadow="never" class="data-panel downloads-data-panel">
            <template #header>
              <div class="panel-toolbar downloads-toolbar">
                <div class="panel-toolbar-main download-assignment-picker">
                  <strong>选择导出作业</strong>
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
                </div>
                <div class="panel-toolbar-actions download-all-actions">
                  <el-button size="small" :loading="packageDownloading" @click="downloadAllReports">全部报告</el-button>
                  <el-button size="small" :loading="packageDownloading" @click="downloadAllCodes">全部代码</el-button>
                  <el-button size="small" type="primary" :loading="packageDownloading" @click="downloadAllPackage">合并下载</el-button>
                  <el-button size="small" type="success" :loading="packageDownloading" @click="downloadScoreSheet">成绩表 Excel</el-button>
                </div>
              </div>
              <div v-if="packageDownloadProgress > 0 && packageDownloadProgress < 100" class="download-progress-band">
                <span>正在生成下载文件</span>
                <el-progress
                  class="package-download-progress"
                  :percentage="packageDownloadProgress"
                />
              </div>
            </template>

            <div class="selection-action-bar download-selection-bar">
              <div class="selection-action-copy">
                <strong>已选择 {{ downloadSelection.length }} 份提交</strong>
                <span>选中导出只包含当前勾选学生的最新提交。</span>
              </div>
              <div class="selection-action-controls">
                <el-button size="small" :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedReports">
                  下载选中报告
                </el-button>
                <el-button size="small" :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedCodes">
                  下载选中代码
                </el-button>
                <el-button size="small" type="primary" plain :disabled="!downloadSelection.length" :loading="packageDownloading" @click="downloadSelectedPackage">
                  选中合并下载
                </el-button>
              </div>
            </div>

            <div class="table-shell downloads-table-shell">
              <el-table
                ref="downloadTable"
                :data="submissions"
                height="420"
                row-key="id"
                empty-text="当前作业还没有可下载的提交"
                @selection-change="selectDownloadRows"
              >
                <el-table-column type="selection" width="44" />
                <el-table-column prop="studentUsername" label="学号" width="120" />
                <el-table-column prop="studentRealName" label="姓名" width="110" />
                <el-table-column prop="fileName" label="提交文件" min-width="210" show-overflow-tooltip />
                <el-table-column label="评分状态" width="104">
                  <template #default="{ row }">
                    <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="单份下载" width="160" fixed="right">
                  <template #default="{ row }">
                    <el-button link type="primary" @click.stop="downloadReport(row.id)">评分报告</el-button>
                    <el-button link type="primary" @click.stop="downloadSubmission(row.id)">代码包</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>
        </section>

        <section v-show="activeTeacherModule === 'review'" class="teacher-module-panel review-module-panel">
          <div class="module-intro">
            <div class="module-intro-copy">
              <span class="module-kicker">REVIEW &amp; RELEASE</span>
              <h3>教师复核与成绩发布</h3>
              <p>左侧选择学生，右侧核对 AI 报告、调整分项得分并完成最终发布。</p>
            </div>
            <div class="module-summary-grid review-summary-grid">
              <MetricCard label="待复核报告" :value="reviewSubmissionRows.length" />
              <MetricCard label="当前 AI 分" :value="report ? scoreText(report.totalScore) : '--'" />
              <MetricCard label="分项合计" :value="dimensionScores.length ? dimensionTotal.toFixed(2) : '--'" />
            </div>
          </div>

          <el-card shadow="never" class="report-panel data-panel">
            <template #header>
              <div class="panel-toolbar review-toolbar">
                <div class="panel-toolbar-main review-assignment-picker">
                  <strong>复核作业</strong>
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
                </div>
                <div class="panel-toolbar-actions review-history-actions">
                  <el-button @click="loadReportHistory">AI 报告历史</el-button>
                  <el-button @click="openReportCompare">模型评分对比</el-button>
                  <el-button @click="loadReviewHistory">复核历史</el-button>
                </div>
              </div>
            </template>

            <div class="review-workspace">
              <aside class="review-submission-list">
                <div class="review-list-head">
                  <div>
                    <strong>AI 已评分作业</strong>
                    <span>选择一名学生进入复核</span>
                  </div>
                  <el-tag type="info" effect="plain">{{ reviewSubmissionRows.length }} 份</el-tag>
                </div>
                <div class="table-shell review-list-table-shell">
                  <el-table
                    :data="reviewSubmissionRows"
                    height="620"
                    highlight-current-row
                    empty-text="当前作业还没有 AI 评分报告"
                    :row-class-name="reviewRowClassName"
                    @row-click="openReviewSubmission"
                  >
                    <el-table-column prop="studentUsername" label="学号" min-width="100" show-overflow-tooltip />
                    <el-table-column prop="studentRealName" label="姓名" min-width="90" show-overflow-tooltip />
                    <el-table-column label="AI 分" width="76">
                      <template #default="{ row }"><strong class="score-cell">{{ scoreText(row.currentScore) }}</strong></template>
                    </el-table-column>
                    <el-table-column label="状态" width="88">
                      <template #default="{ row }">
                        <el-tag :type="submissionStatusType(row.status)" size="small">{{ submissionStatusText(row.status) }}</el-tag>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </aside>

              <div v-if="report" class="review-detail">
                <aside class="review-meta-panel sticky-review-panel">
                  <div class="review-current-student">
                    <div>
                      <span class="module-kicker">CURRENT STUDENT</span>
                      <strong>{{ selectedSubmissionRow?.studentRealName || "已选学生" }}</strong>
                    </div>
                    <el-tag
                      :type="selectedSubmissionRow?.publishStatus === 1 ? 'success' : 'warning'"
                      effect="light"
                    >
                      {{ publishStatusText(selectedSubmissionRow?.publishStatus) }}
                    </el-tag>
                  </div>
                  <p class="review-student-id">{{ selectedSubmissionRow?.studentUsername || "" }}</p>
                  <div class="review-score-summary">
                    <MetricCard label="AI 总分" :value="report.totalScore" />
                    <MetricCard label="当前最终分" :value="finalScore || '--'" />
                  </div>
                  <el-form label-position="top" class="review-final-form">
                    <el-form-item label="最终分">
                      <el-input v-model="finalScore" />
                    </el-form-item>
                    <el-form-item label="教师评语">
                      <el-input v-model="reviewComment" type="textarea" :rows="6" placeholder="填写面向学生的复核意见" />
                    </el-form-item>
                  </el-form>
                  <div class="review-primary-actions">
                    <el-button @click="saveReview">保存复核</el-button>
                    <el-button type="primary" @click="publishGrades">{{ publishButtonText }}</el-button>
                  </div>
                  <div class="review-destructive-action destructive-zone">
                    <span>已发布成绩需要修正时，可先撤回再编辑。</span>
                    <el-button type="danger" plain @click="retractGrades()">撤回成绩</el-button>
                  </div>
                </aside>

                <div class="review-content-stack">
                  <section class="review-document-panel review-content-panel">
                    <div class="section-heading">
                      <div>
                        <span class="module-kicker">REPORT</span>
                        <h3>AI 评分报告</h3>
                      </div>
                      <span>左侧编辑 Markdown，右侧实时预览</span>
                    </div>
                    <div class="markdown-editor-grid">
                      <section class="report-editor-pane">
                        <div class="pane-label">报告源内容</div>
                        <el-input v-model="reportMarkdown" type="textarea" :rows="18" class="markdown-editor" />
                      </section>
                      <section class="report-preview-pane">
                        <div class="pane-label">学生端预览</div>
                        <article class="markdown-preview rendered-markdown" v-html="reportHtml"></article>
                      </section>
                    </div>
                  </section>

                  <section class="review-score-panel review-content-panel">
                    <div class="section-heading">
                      <div>
                        <span class="module-kicker">RUBRIC</span>
                        <h3>分项得分与评语</h3>
                      </div>
                      <strong>合计 {{ dimensionTotal.toFixed(2) }} 分</strong>
                    </div>
                    <div class="table-shell score-table-shell">
                      <el-table :data="dimensionScores" class="dimension-table" size="small" border empty-text="报告中没有分项得分">
                        <el-table-column prop="name" label="评分维度" min-width="140" />
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
                    </div>
                    <section v-if="issueRows.length" class="issue-block">
                      <div class="section-heading compact-section-heading">
                        <h3>代码问题列表</h3>
                        <span>{{ issueRows.length }} 项</span>
                      </div>
                      <div class="table-shell">
                        <el-table :data="issueRows" class="issue-table" size="small" border>
                          <el-table-column label="级别" width="82">
                            <template #default="{ row }">
                              <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
                            </template>
                          </el-table-column>
                          <el-table-column prop="file" label="文件" min-width="160" show-overflow-tooltip />
                          <el-table-column prop="line" label="行" width="68" />
                          <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
                        </el-table>
                      </div>
                    </section>
                  </section>
                </div>
              </div>
              <el-empty v-else class="empty-panel review-empty-panel" description="点击左侧学生作业查看 AI 评分报告" />
            </div>
          </el-card>
        </section>
      </div>

    <el-dialog
      v-model="semesterFileCleanupVisible"
      class="teacher-workflow-dialog destructive-dialog"
      :title="`清理归档学期文件${semesterFileCleanupTarget?.name ? `：${semesterFileCleanupTarget.name}` : ''}`"
      width="min(880px, 94vw)"
      :close-on-click-modal="!semesterFileCleanupExecuting"
      :close-on-press-escape="!semesterFileCleanupExecuting"
      :show-close="!semesterFileCleanupExecuting"
      @closed="resetSemesterFileCleanup"
    >
      <div v-loading="semesterFileCleanupLoading" class="semester-file-cleanup-dialog">
        <el-alert
          title="只删除原始提交 ZIP 和该学期的旧评分附件；学期、作业、提交信息、解析代码、AI 报告、复核和成绩记录都会保留。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="semesterFileCleanupResult"
          :title="`上次清理已删除 ${semesterFileCleanupResult.deletedCount || 0} 个文件（${formatBytes(semesterFileCleanupResult.deletedBytes)}）`"
          :description="semesterCleanupErrorSummary"
          :type="semesterFileCleanupResult.errors?.length ? 'warning' : 'success'"
          :closable="false"
          show-icon
        />
        <template v-if="semesterFileCleanupPreview">
          <div class="semester-cleanup-summary">
            <MetricCard label="可删除文件" :value="semesterFileCleanupPreview.candidateCount || 0" />
            <MetricCard label="可释放容量" :value="formatBytes(semesterFileCleanupPreview.candidateBytes)" />
            <MetricCard label="提交 ZIP" :value="semesterFileCleanupPreview.submissionZipCount || 0" />
            <MetricCard label="旧评分附件" :value="semesterFileCleanupPreview.rubricFileCount || 0" />
          </div>
          <el-alert
            v-if="semesterFileCleanupPreview.unrecoverableSubmissionCount"
            :title="`${semesterFileCleanupPreview.unrecoverableSubmissionCount} 份提交没有可用于重建代码包的解析结构，删除原始 ZIP 后将无法恢复其文件内容。`"
            type="error"
            :closable="false"
            show-icon
          />
          <div v-if="semesterFileCleanupPreview.candidateFiles?.length" class="table-shell dialog-table-shell">
            <el-table
              :data="semesterFileCleanupPreview.candidateFiles"
              size="small"
              border
              max-height="280"
            >
              <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
              <el-table-column label="类型" width="110">
                <template #default="{ row }">{{ semesterCleanupFileType(row.fileType) }}</template>
              </el-table-column>
              <el-table-column label="大小" width="100">
                <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row }">
                  <el-tag :type="row.deletable ? 'danger' : row.exists ? 'warning' : 'info'" size="small">
                    {{ row.deletable ? "将删除" : row.skipReason || "已跳过" }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <p v-if="semesterFileCleanupPreview.detailsTruncated" class="cleanup-detail-note">
            文件较多，明细仅展示前 200 条，统计和清理范围包含全部文件。
          </p>
          <el-empty
            v-if="!semesterFileCleanupPreview.candidateCount"
            description="该归档学期没有可删除的原始文件"
          />
          <div v-else class="semester-cleanup-confirm">
            <span>
              此操作不可撤销。请输入学期名称
              <strong>{{ semesterFileCleanupTarget?.name }}</strong>
              以确认：
            </span>
            <el-input
              v-model="semesterFileCleanupConfirmation"
              :placeholder="semesterFileCleanupTarget?.name"
              clearable
            />
            <el-checkbox
              v-if="semesterFileCleanupPreview.unrecoverableSubmissionCount"
              v-model="semesterFileCleanupUnrecoverableConfirmed"
            >
              我确认这 {{ semesterFileCleanupPreview.unrecoverableSubmissionCount }} 份提交删除后无法恢复原始文件内容
            </el-checkbox>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button :disabled="semesterFileCleanupExecuting" @click="semesterFileCleanupVisible = false">关闭</el-button>
        <el-button
          type="danger"
          :loading="semesterFileCleanupExecuting"
          :disabled="
            semesterFileCleanupLoading
            || semesterFileCleanupExecuting
            || !semesterFileCleanupConfirmMatches
            || !semesterFileCleanupPreview?.candidateCount
          "
          @click="executeSemesterFileCleanup"
        >
          确认删除原始文件
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scoringPreviewVisible" class="teacher-workflow-dialog scoring-confirm-dialog" title="确认评分列表" width="720px">
      <div class="dialog-intro">
        <strong>即将发起 {{ scoringSelection.length }} 份 AI 评分任务</strong>
        <span>请确认学生和提交文件无误，任务发起后可在 AI 任务页面查看进度。</span>
      </div>
      <div class="table-shell dialog-table-shell">
        <el-table :data="scoringSelection" height="300" empty-text="尚未选择待评分提交">
          <el-table-column prop="studentUsername" label="学号" width="120" />
          <el-table-column prop="studentRealName" label="姓名" width="110" />
          <el-table-column prop="fileName" label="文件名" min-width="170" />
          <el-table-column prop="uploadTime" label="提交时间" min-width="170" />
        </el-table>
      </div>
      <template #footer>
        <el-button @click="scoringPreviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="scoringSubmitting" @click="confirmScoring">发起 AI 评分</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rubricPreviewVisible" class="teacher-workflow-dialog rubric-dialog" title="Rubric JSON 预览" width="760px">
      <pre class="rubric-preview">{{ rubricPreviewJson }}</pre>
    </el-dialog>

    <el-dialog v-model="manualScoringVisible" class="teacher-workflow-dialog manual-scoring-dialog" title="手动评分" width="1080px">
      <div class="review-current-student manual-scoring-student">
        <strong>{{ selectedSubmissionRow?.studentRealName || "已选学生" }}</strong>
        <span>{{ selectedSubmissionRow?.studentUsername || "" }}</span>
      </div>
      <div class="table-shell dialog-table-shell">
      <el-table :data="manualScoreRows" class="dimension-table" size="small" border max-height="360" empty-text="当前 Rubric 没有评分项">
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
      </div>
      <section class="issue-block manual-issue-block">
        <div class="card-head">
          <h3>问题列表</h3>
          <el-button size="small" @click="addManualIssue">添加问题</el-button>
        </div>
        <div class="table-shell dialog-table-shell">
        <el-table :data="manualIssueRows" class="issue-table" size="small" border max-height="220" empty-text="暂无问题记录，可按需添加">
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
        </div>
      </section>
      <template #footer>
        <span class="manual-score-total">合计 {{ manualScoreTotal.toFixed(2) }} / 100</span>
        <el-button @click="manualScoringVisible = false">取消</el-button>
        <el-button type="primary" :loading="manualScoringSubmitting" @click="saveManualScoring">保存并进入复核发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reportHistoryVisible" class="teacher-workflow-dialog history-dialog" title="AI 报告历史" width="820px">
      <div class="dialog-intro">
        <strong>历次模型评分记录</strong>
        <span>选择一份历史报告可载入当前复核工作区。</span>
      </div>
      <div class="table-shell dialog-table-shell">
      <el-table :data="reportHistory" size="small" border empty-text="当前提交没有历史 AI 报告">
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
      </div>
    </el-dialog>

    <el-dialog v-model="reportCompareVisible" class="teacher-workflow-dialog report-compare-dialog" title="不同模型评分对比" width="1180px">
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
        <div class="table-shell dialog-table-shell">
        <el-table :data="compareDimensionRows" size="small" border class="compare-table" empty-text="两份报告没有可对比的评分维度">
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
        </div>
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

    <el-dialog v-model="reviewHistoryVisible" class="teacher-workflow-dialog history-dialog" title="复核修改历史" width="820px">
      <div class="dialog-intro">
        <strong>教师复核版本</strong>
        <span>载入历史版本后可查看当时的最终分与教师评语。</span>
      </div>
      <div class="table-shell dialog-table-shell">
      <el-table :data="reviewHistory" size="small" border empty-text="当前提交还没有复核历史">
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
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { messageOf } from "../JS/api.js";
import { renderMarkdown } from "../JS/markdown.js";
import "../CSS/teacher-workflow-pages.css";
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
const assignmentsLoading = ref(false);
const submissions = ref([]);
const submissionsLoading = ref(false);
const tasks = ref([]);
const taskLogs = ref([]);
const taskProgress = ref(null);
const students = ref([]);
const semesters = ref([]);
const selectedSemesterId = ref(null);
const studentSelection = ref([]);
const rubricTemplates = ref([]);
const rubricTemplateItems = ref([]);
const activeTeacherModule = computed(() => props.activeModule || "assignments");
const activeTeacherModuleLabel = computed(() => ({
  assignments: "作业管理",
  students: "学生管理",
  templates: "评分模板",
  submissions: "提交评分",
  tasks: "AI 任务",
  downloads: "文件下载",
  review: "复核发布"
})[activeTeacherModule.value] || "教师工作台");
const studentUploadProgress = ref(0);
const cleanupOlderThanDays = ref(180);
const cleanupPreview = ref(null);
const cleanupPreviewLoading = ref(false);
const cleanupExecuting = ref(false);
const cleanupPreviewedOlderThanDays = ref(null);
const semesterFileCleanupVisible = ref(false);
const semesterFileCleanupLoading = ref(false);
const semesterFileCleanupExecuting = ref(false);
const semesterFileCleanupTarget = ref(null);
const semesterFileCleanupPreview = ref(null);
const semesterFileCleanupResult = ref(null);
const semesterFileCleanupConfirmation = ref("");
const semesterFileCleanupUnrecoverableConfirmed = ref(false);
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
const cancellingScoring = ref(false);
const packageDownloading = ref(false);
const packageDownloadProgress = ref(0);
const cleanupPreviewMessage = computed(() => {
  if (!cleanupPreview.value) return "";
  const candidateCount = Number(cleanupPreview.value.candidateCount || 0);
  if (cleanupPreview.value.dryRun === false) {
    return `清理完成：已删除 ${Number(cleanupPreview.value.deletedCount || 0)} 个文件`;
  }
  const olderThanDays = cleanupPreviewedOlderThanDays.value ?? cleanupOlderThanDays.value;
  return candidateCount > 0
    ? `预览完成：找到 ${candidateCount} 个超过 ${olderThanDays} 天的文件`
    : `预览完成：没有超过 ${olderThanDays} 天的可清理文件`;
});
const cleanupPreviewIsCurrent = computed(() =>
  Boolean(
    cleanupPreview.value?.dryRun
    && cleanupPreviewedOlderThanDays.value === Number(cleanupOlderThanDays.value)
  )
);
const semesterFileCleanupConfirmMatches = computed(() =>
  Boolean(
    semesterFileCleanupTarget.value?.name
    && semesterFileCleanupConfirmation.value.trim() === semesterFileCleanupTarget.value.name
    && (
      !semesterFileCleanupPreview.value?.unrecoverableSubmissionCount
      || semesterFileCleanupUnrecoverableConfirmed.value
    )
  )
);
const semesterCleanupErrorSummary = computed(() => {
  const errors = semesterFileCleanupResult.value?.errors || [];
  if (!errors.length) return "";
  const summary = errors.slice(0, 3).join("；");
  return errors.length > 3 ? `${summary}；另有 ${errors.length - 3} 项失败` : summary;
});
let scoringPollTimer = null;
let scoringPollGeneration = 0;
let assignmentRequestId = 0;
let assignmentStatsRequestId = 0;
let submissionRequestId = 0;
let cleanupPreviewRequestId = 0;
let semesterFileCleanupRequestId = 0;
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
  classNames: normalizedClassNames([props.user.className, props.user.teachingClass]),
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
const selectedSemester = computed(() => semesters.value.find((item) => item.id === selectedSemesterId.value) || null);
const teacherSubtitle = computed(() => {
  const parts = [];
  if (props.user.college) parts.push(props.user.college);
  if (props.user.teachingClass || props.user.className) parts.push(`教授班级 ${props.user.teachingClass || props.user.className}`);
  return parts.join(" · ") || "教师资料待完善";
});
const classOptions = computed(() => {
  const values = new Set();
  normalizedClassNames([props.user.className, props.user.teachingClass]).forEach((className) => values.add(className));
  students.value.forEach((student) => {
    normalizedClassNames([student.className]).forEach((className) => values.add(className));
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
const submissionOverview = computed(() => {
  const rows = submissions.value;
  return {
    total: rows.length,
    scored: rows.filter((item) => ["scored", "reviewed", "published"].includes(item.status)).length,
    running: rows.filter((item) => item.status === "scoring").length,
    pending: rows.filter((item) => ["uploaded", "parsed", "parse_failed", "failed"].includes(item.status)).length
  };
});
const submissionEmptyText = computed(() => {
  if (assignmentsLoading.value) return "正在加载作业";
  if (!assignments.value.length) return "当前学期暂无作业";
  if (!selectedAssignment.value) return "请先选择评分作业";
  if (submissionQuery.studentNo.trim() || submissionQuery.status) {
    return "当前作业没有符合筛选条件的提交记录";
  }
  return "当前作业暂无提交记录";
});
const selectedRubricTemplate = computed(() =>
  rubricTemplates.value.find((item) => item.id === assignmentForm.rubricTemplateId) || null
);
const latestBatchPercent = computed(() => {
  const total = Number(taskProgress.value?.latestBatchTotal || 0);
  if (!total) return 0;
  const counts = taskProgress.value?.latestBatchCounts || {};
  const done = Number(counts.success || 0) + Number(counts.failed || 0) + Number(counts.cancelled || 0);
  return Math.min(100, Math.round((done / total) * 100));
});
const canCancelCurrentScoring = computed(() =>
  tasks.value.some((task) => ["pending", "running"].includes(task.status))
);
watch(dimensionTotal, (value) => {
  if (dimensionScores.value.length) {
    finalScore.value = value.toFixed(2);
  }
});

onMounted(async () => {
  await refreshSemesters();
  refreshAssignments();
  refreshStudents();
  refreshRubricTemplates();
  refreshTokenQuota();
  refreshTokenStats();
});

onUnmounted(() => stopScoringPolling());

watch(selectedAssignment, (nextAssignment, previousAssignment) => {
  stopScoringPolling();
  submissionRequestId++;
  selectedSubmission.value = null;
  scoringSelection.value = [];
  downloadSelection.value = [];
  submissions.value = [];
  tasks.value = [];
  taskProgress.value = null;
  report.value = null;
  reportMarkdown.value = "";
  rubric.value = null;
  if (!sameEntityId(nextAssignment, previousAssignment)) {
    submissionQuery.studentNo = "";
    submissionQuery.status = "";
  }
  if (!nextAssignment) {
    submissionsLoading.value = false;
    return;
  }
  refreshSubmissions();
  refreshActiveRubric();
});

watch(activeTeacherModule, (nextModule) => {
  if (nextModule === "assignments" && editingAssignmentId.value) {
    refreshEditingAssignmentStats(editingAssignmentId.value);
  }
});

async function refreshAssignments() {
  const requestId = ++assignmentRequestId;
  const semesterId = selectedSemesterId.value;
  assignmentsLoading.value = true;
  try {
    const query = semesterId ? `?semesterId=${semesterId}` : "";
    const nextAssignments = await props.api.get(`/api/v1/assignments${query}`);
    if (requestId !== assignmentRequestId || !sameEntityId(selectedSemesterId.value, semesterId)) return;
    assignments.value = Array.isArray(nextAssignments) ? nextAssignments : [];
    const currentAssignmentExists = assignments.value.some((item) => sameEntityId(item.id, selectedAssignment.value));
    const nextAssignmentId = currentAssignmentExists
      ? selectedAssignment.value
      : assignments.value[0]?.id ?? null;
    if (!sameEntityId(nextAssignmentId, selectedAssignment.value)) {
      selectedAssignment.value = nextAssignmentId;
    } else if (nextAssignmentId) {
      refreshSubmissions();
      refreshActiveRubric();
    }
    if (editingAssignmentId.value && assignments.value.some((item) => sameEntityId(item.id, editingAssignmentId.value))) {
      refreshEditingAssignmentStats(editingAssignmentId.value);
    }
  } catch (error) {
    if (requestId === assignmentRequestId) {
      ElMessage.error(messageOf(error));
    }
  } finally {
    if (requestId === assignmentRequestId) {
      assignmentsLoading.value = false;
    }
  }
}

async function refreshSubmissions() {
  const assignmentId = selectedAssignment.value;
  const requestId = ++submissionRequestId;
  if (!assignmentId) {
    submissions.value = [];
    tasks.value = [];
    taskProgress.value = null;
    submissionsLoading.value = false;
    return;
  }
  submissionsLoading.value = true;
  try {
    const [nextSubmissions, nextTasks, nextProgress] = await Promise.all([
      props.api.get(submissionListUrl(assignmentId)),
      props.api.get(`/api/v1/ai-tasks?assignment_id=${assignmentId}`),
      props.api.get(`/api/v1/ai-tasks/progress?assignment_id=${assignmentId}`)
    ]);
    if (requestId !== submissionRequestId || !sameEntityId(selectedAssignment.value, assignmentId)) return;
    await replaceSubmissionRows(nextSubmissions);
    if (requestId !== submissionRequestId || !sameEntityId(selectedAssignment.value, assignmentId)) return;
    await syncSubmissionTableSelections();
    tasks.value = nextTasks;
    taskProgress.value = nextProgress;
    if (!selectedSubmission.value && nextSubmissions[0]) {
      selectedSubmission.value = nextSubmissions[0].id;
    }
    watchRunningTasks(nextTasks, assignmentId);
  } catch (error) {
    if (requestId === submissionRequestId && sameEntityId(selectedAssignment.value, assignmentId)) {
      ElMessage.error(messageOf(error));
    }
  } finally {
    if (requestId === submissionRequestId) {
      submissionsLoading.value = false;
    }
  }
}

function submissionListUrl(assignmentId = selectedAssignment.value) {
  const params = new URLSearchParams();
  params.set("assignment_id", assignmentId);
  if (submissionQuery.studentNo.trim()) {
    params.set("student_no", submissionQuery.studentNo.trim());
  }
  if (submissionQuery.status) {
    params.set("status", submissionQuery.status);
  }
  return `/api/v1/submissions?${params.toString()}`;
}

function sameEntityId(left, right) {
  if (left === null || left === undefined || right === null || right === undefined) {
    return left == null && right == null;
  }
  return String(left) === String(right);
}

async function refreshEditingAssignmentStats(assignmentId = editingAssignmentId.value) {
  const requestId = ++assignmentStatsRequestId;
  if (!assignmentId) {
    assignmentStats.value = null;
    return;
  }
  try {
    const nextStats = await props.api.get(`/api/v1/assignments/${assignmentId}/stats`);
    if (requestId === assignmentStatsRequestId && sameEntityId(editingAssignmentId.value, assignmentId)) {
      assignmentStats.value = nextStats;
    }
  } catch (error) {
    if (requestId === assignmentStatsRequestId && sameEntityId(editingAssignmentId.value, assignmentId)) {
      assignmentStats.value = null;
      ElMessage.error(messageOf(error));
    }
  }
}

function watchRunningTasks(nextTasks, assignmentId = selectedAssignment.value) {
  if (!sameEntityId(selectedAssignment.value, assignmentId)) return;
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
    const query = selectedSemesterId.value ? `?semesterId=${selectedSemesterId.value}` : "";
    students.value = await props.api.get(`/api/v1/users/students${query}`);
    studentSelection.value = [];
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function refreshSemesters() {
  try {
    semesters.value = await props.api.get("/api/v1/semesters");
    if (!selectedSemesterId.value) {
      selectedSemesterId.value = semesters.value.find((item) => item.status === "active")?.id || semesters.value[0]?.id || null;
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

function refreshAll() {
  refreshAssignments();
  refreshStudents();
  refreshRubricTemplates();
  refreshTokenQuota();
  refreshTokenStats();
}

function changeSemester() {
  semesterFileCleanupVisible.value = false;
  resetSemesterFileCleanup();
  selectedAssignment.value = null;
  resetAssignmentForm();
  refreshAssignments();
  refreshStudents();
}

async function createSemester() {
  try {
    const { value } = await ElMessageBox.prompt("请输入新学期名称，例如：2026-2027 学年第一学期", "新建学期", {
      inputPlaceholder: "学期名称",
      confirmButtonText: "创建",
      cancelButtonText: "取消"
    });
    const semester = await props.api.post("/api/v1/semesters", { name: value });
    await refreshSemesters();
    selectedSemesterId.value = semester.id;
    changeSemester();
    ElMessage.success("新学期已创建");
  } catch (error) {
    if (error !== "cancel") ElMessage.error(messageOf(error));
  }
}

async function archiveSemester() {
  if (!selectedSemester.value) return;
  try {
    await ElMessageBox.confirm(`确认归档“${selectedSemester.value.name}”？归档后仅可查看该学期历史数据。`, "归档学期", {
      type: "warning",
      confirmButtonText: "确认归档",
      cancelButtonText: "取消"
    });
    await props.api.post(`/api/v1/semesters/${selectedSemester.value.id}/archive`, {});
    await refreshSemesters();
    selectedSemesterId.value = semesters.value.find((item) => item.status === "active")?.id || selectedSemesterId.value;
    changeSemester();
    ElMessage.success("学期已归档，请新建下一个学期后继续使用");
  } catch (error) {
    if (error !== "cancel") ElMessage.error(messageOf(error));
  }
}

async function openSemesterFileCleanup() {
  if (!isAdmin.value || selectedSemester.value?.status !== "archived") {
    ElMessage.warning("请先选择已归档学期");
    return;
  }
  if (semesterFileCleanupLoading.value || semesterFileCleanupExecuting.value) return;
  resetSemesterFileCleanup();
  semesterFileCleanupTarget.value = { ...selectedSemester.value };
  semesterFileCleanupVisible.value = true;
  await refreshSemesterFileCleanupPreview();
}

async function refreshSemesterFileCleanupPreview() {
  const semesterId = semesterFileCleanupTarget.value?.id;
  if (!semesterId) return;
  const requestId = ++semesterFileCleanupRequestId;
  semesterFileCleanupLoading.value = true;
  semesterFileCleanupPreview.value = null;
  semesterFileCleanupUnrecoverableConfirmed.value = false;
  try {
    const preview = await props.api.get(`/api/v1/semesters/${semesterId}/files/cleanup-preview`);
    if (
      requestId !== semesterFileCleanupRequestId
      || semesterFileCleanupTarget.value?.id !== semesterId
      || !semesterFileCleanupVisible.value
    ) {
      return false;
    }
    semesterFileCleanupPreview.value = preview;
    return true;
  } catch (error) {
    if (requestId === semesterFileCleanupRequestId && semesterFileCleanupVisible.value) {
      ElMessage.error(messageOf(error));
    }
    return false;
  } finally {
    if (requestId === semesterFileCleanupRequestId) {
      semesterFileCleanupLoading.value = false;
    }
  }
}

async function executeSemesterFileCleanup() {
  const semesterId = semesterFileCleanupTarget.value?.id;
  if (
    semesterFileCleanupExecuting.value
    || semesterFileCleanupLoading.value
    || !semesterId
    || !semesterFileCleanupConfirmMatches.value
    || !semesterFileCleanupPreview.value?.candidateCount
  ) {
    return;
  }
  semesterFileCleanupExecuting.value = true;
  try {
    semesterFileCleanupResult.value = await props.api.post(`/api/v1/semesters/${semesterId}/files/cleanup`, {
      allowUnrecoverable: semesterFileCleanupUnrecoverableConfirmed.value,
      confirmedSemesterName: semesterFileCleanupConfirmation.value.trim(),
      previewToken: semesterFileCleanupPreview.value.previewToken
    });
    semesterFileCleanupConfirmation.value = "";
    semesterFileCleanupUnrecoverableConfirmed.value = false;
    await refreshSemesterFileCleanupPreview();
    const result = semesterFileCleanupResult.value;
    if (result.errors?.length) {
      ElMessage.warning(`已删除 ${result.deletedCount || 0} 个文件，另有 ${result.errors.length} 个文件处理失败`);
    } else {
      ElMessage.success(`已删除 ${result.deletedCount || 0} 个归档文件，释放 ${formatBytes(result.deletedBytes)}`);
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    semesterFileCleanupExecuting.value = false;
  }
}

function resetSemesterFileCleanup() {
  semesterFileCleanupRequestId++;
  semesterFileCleanupTarget.value = null;
  semesterFileCleanupPreview.value = null;
  semesterFileCleanupResult.value = null;
  semesterFileCleanupConfirmation.value = "";
  semesterFileCleanupUnrecoverableConfirmed.value = false;
  semesterFileCleanupLoading.value = false;
  semesterFileCleanupExecuting.value = false;
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
  if (editingAssignmentId.value) {
    ElMessage.warning("当前正在编辑作业，请先保存或取消编辑");
    return;
  }
  if (!validateAssignmentTemplateSelection(published)) return;
  if (!validateAssignmentClasses()) return;
  try {
    if (published) await confirmAssignmentPublish();
    const created = await props.api.post("/api/v1/assignments", { ...assignmentForm, published });
    assignments.value = [created, ...assignments.value];
    selectedAssignment.value = created.id;
    editingAssignmentId.value = created.id;
    refreshEditingAssignmentStats(created.id);
    ElMessage.success(published
      ? `作业已发布至：${assignmentClassNames(created).join("、")}`
      : "草稿已创建");
  } catch (error) {
    if (error !== "cancel") ElMessage.error(messageOf(error));
  }
}

function selectAssignment(row) {
  selectedAssignment.value = row?.id || null;
  assignmentStatsRequestId++;
  assignmentStats.value = null;
  if (row) {
    editingAssignmentId.value = row.id;
    refreshEditingAssignmentStats(row.id);
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
    ElMessage.warning("请使用“创建草稿”或“创建并发布”完成新建作业");
    return;
  }
  if (selectedAssignmentRecord.value?.status !== "published") {
    if (!validateAssignmentTemplateSelection(false)) return;
    if (!validateAssignmentClasses()) return;
  }
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
  if (!validateAssignmentTemplateSelection(true)) return;
  if (!validateAssignmentClasses()) return;
  try {
    await confirmAssignmentPublish();
    const updated = await props.api.put(`/api/v1/assignments/${editingAssignmentId.value}`, {
      ...assignmentForm,
      published: true
    });
    assignments.value = assignments.value.map((item) => (item.id === updated.id ? updated : item));
    selectedAssignment.value = updated.id;
    ElMessage.success(`作业已发布至：${assignmentClassNames(updated).join("、")}`);
  } catch (error) {
    if (error !== "cancel") ElMessage.error(messageOf(error));
  }
}

async function deleteAssignment(row) {
  if (!isAdmin.value || !row?.id) return;
  const isDraft = row.status === "draft";
  try {
    await ElMessageBox.confirm(
      isDraft
        ? `确认删除草稿“${row.title}”？删除后无法恢复。`
        : `确认彻底删除作业“${row.title}”？学生提交、评分任务、AI 报告、复核记录、发布成绩和上传文件都会一并删除，且无法恢复。`,
      isDraft ? "删除草稿" : "彻底删除作业",
      {
        type: isDraft ? "warning" : "error",
        confirmButtonText: isDraft ? "确认删除草稿" : "确认彻底删除",
        cancelButtonText: "取消"
      }
    );
    await props.api.delete(`/api/v1/assignments/${row.id}`);
    assignments.value = assignments.value.filter((item) => item.id !== row.id);
    if (editingAssignmentId.value === row.id) {
      resetAssignmentForm();
    }
    selectedAssignment.value = assignments.value[0]?.id || null;
    await refreshSubmissions();
    ElMessage.success(isDraft ? "草稿已删除" : "作业及关联数据已删除");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

function resetAssignmentForm() {
  assignmentStatsRequestId++;
  editingAssignmentId.value = null;
  assignmentStats.value = null;
  assignmentForm.title = "";
  assignmentForm.courseName = "";
  assignmentForm.description = "";
  assignmentForm.language = "java";
  assignmentForm.classNames = normalizedClassNames([props.user.className, props.user.teachingClass]);
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
  assignmentForm.classNames = normalizedClassNames(assignmentForm.classNames);
  if (!assignmentForm.classNames.length) {
    ElMessage.warning("请选择至少一个发布班级");
    return false;
  }
  return true;
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
  return [...new Set((values || [])
    .flatMap((value) => String(value || "").split(/[,，]/))
    .map((item) => item.trim())
    .filter(Boolean))];
}

async function confirmAssignmentPublish() {
  const classNames = normalizedClassNames(assignmentForm.classNames);
  const studentCount = students.value.filter((student) => classNames.includes(student.className)).length;
  const emptyClasses = classNames.filter((className) => !students.value.some((student) => student.className === className));
  const emptyClassTip = emptyClasses.length
    ? `\n注意：${emptyClasses.join("、")} 当前学期尚无学生。`
    : "";
  await ElMessageBox.confirm(
    `确认发布到 ${classNames.join("、")}，当前覆盖 ${studentCount} 名学生？${emptyClassTip}`,
    "确认发布班级",
    {
      type: emptyClasses.length ? "warning" : "info",
      confirmButtonText: "确认发布",
      cancelButtonText: "返回检查"
    }
  );
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
  if (syncingSubmissionSelection || activeTeacherModule.value !== "submissions") return;
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

async function deleteSelectedStudents() {
  const count = studentSelection.value.length;
  try {
    await ElMessageBox.confirm(
      `确认彻底删除所选 ${count} 名学生？其账号、提交、评分记录和上传文件都会永久删除，无法恢复。`,
      "彻底删除学生",
      { type: "error", confirmButtonText: "确认删除", cancelButtonText: "取消" }
    );
    const result = await props.api.delete("/api/v1/users/students", { studentIds: studentSelection.value.map((student) => student.id) });
    await refreshStudents();
    ElMessage.success(`已彻底删除 ${result.deletedCount || 0} 名学生`);
  } catch (error) {
    if (error !== "cancel") ElMessage.error(messageOf(error));
  }
}

async function previewFileCleanup() {
  if (cleanupPreviewLoading.value) return;
  const olderThanDays = Number(cleanupOlderThanDays.value);
  if (!Number.isInteger(olderThanDays) || olderThanDays < 1 || olderThanDays > 3650) {
    ElMessage.warning("请输入 1 至 3650 之间的清理天数");
    return;
  }
  const requestId = ++cleanupPreviewRequestId;
  cleanupPreviewLoading.value = true;
  cleanupPreview.value = null;
  cleanupPreviewedOlderThanDays.value = null;
  try {
    const preview = await props.api.get(`/api/v1/files/cleanup-preview?olderThanDays=${olderThanDays}`);
    if (requestId !== cleanupPreviewRequestId || Number(cleanupOlderThanDays.value) !== olderThanDays) {
      return;
    }
    cleanupPreview.value = preview;
    cleanupPreviewedOlderThanDays.value = olderThanDays;
    if (preview?.candidateCount) {
      ElMessage.success(`预览完成，找到 ${preview.candidateCount} 个候选文件`);
    } else {
      ElMessage.info(`预览完成，没有超过 ${olderThanDays} 天的可清理文件`);
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    if (requestId === cleanupPreviewRequestId) {
      cleanupPreviewLoading.value = false;
    }
  }
}

function resetCleanupPreview() {
  cleanupPreviewRequestId++;
  cleanupPreview.value = null;
  cleanupPreviewedOlderThanDays.value = null;
  cleanupPreviewLoading.value = false;
}

async function executeFileCleanup() {
  if (cleanupExecuting.value || cleanupPreviewLoading.value || !cleanupPreviewIsCurrent.value || !cleanupPreview.value?.candidateCount) {
    return;
  }
  const olderThanDays = cleanupPreviewedOlderThanDays.value;
  cleanupExecuting.value = true;
  try {
    await ElMessageBox.confirm(
      `确认清理 ${cleanupPreview.value.candidateCount} 个早于 ${olderThanDays} 天的上传文件？`,
      "文件清理",
      {
        type: "warning",
        confirmButtonText: "确认清理",
        cancelButtonText: "取消"
      }
    );
    cleanupPreview.value = await props.api.post(`/api/v1/files/cleanup?olderThanDays=${olderThanDays}`, {});
    ElMessage.success(`已清理 ${cleanupPreview.value.deletedCount || 0} 个文件`);
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  } finally {
    cleanupExecuting.value = false;
  }
}

async function startScoring() {
  if (!selectedAssignment.value) return;
  if (scoringSelection.value.length === 0) {
    ElMessage.warning("请先勾选待评分提交");
    return;
  }
  const publishedRows = scoringSelection.value.filter((row) => row.publishStatus === 1 || row.status === "published");
  if (publishedRows.length) {
    ElMessage.warning("已发布成绩不能重新评分，请先撤回成绩");
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
  checkScoringProgress(scoringPollGeneration);
}

function stopScoringPolling(resetWatch = true) {
  scoringPollGeneration++;
  if (scoringPollTimer) {
    window.clearTimeout(scoringPollTimer);
    scoringPollTimer = null;
  }
  scoringPolling.value = false;
  if (resetWatch) {
    scoringWatchIds.value = [];
  }
}

function scheduleNextScoringPoll(generation) {
  if (!scoringPolling.value || generation !== scoringPollGeneration) return;
  if (scoringPollTimer) {
    window.clearTimeout(scoringPollTimer);
  }
  scoringPollTimer = window.setTimeout(() => {
    scoringPollTimer = null;
    checkScoringProgress(generation);
  }, 3000);
}

async function checkScoringProgress(generation = scoringPollGeneration) {
  const assignmentId = selectedAssignment.value;
  const watchedIds = [...scoringWatchIds.value];
  const submissionVersion = submissionRequestId;
  if (generation !== scoringPollGeneration) return;
  if (!assignmentId || !watchedIds.length) {
    stopScoringPolling();
    return;
  }
  if (submissionsLoading.value) {
    scheduleNextScoringPoll(generation);
    return;
  }
  try {
    const [nextTasks, nextSubmissions, nextProgress] = await Promise.all([
      props.api.get(`/api/v1/ai-tasks?assignment_id=${assignmentId}`),
      props.api.get(submissionListUrl(assignmentId)),
      props.api.get(`/api/v1/ai-tasks/progress?assignment_id=${assignmentId}`)
    ]);
    const watchListUnchanged = watchedIds.length === scoringWatchIds.value.length
      && watchedIds.every((id) => scoringWatchIds.value.includes(id));
    if (
      generation !== scoringPollGeneration
      || !scoringPolling.value
      || !sameEntityId(selectedAssignment.value, assignmentId)
      || !watchListUnchanged
    ) return;
    if (submissionVersion !== submissionRequestId || submissionsLoading.value) {
      scheduleNextScoringPoll(generation);
      return;
    }
    tasks.value = nextTasks;
    await replaceSubmissionRows(nextSubmissions);
    if (
      generation !== scoringPollGeneration
      || !scoringPolling.value
      || !sameEntityId(selectedAssignment.value, assignmentId)
    ) return;
    await syncSubmissionTableSelections();
    taskProgress.value = nextProgress;
    refreshTokenQuota();
    refreshTokenStats();
    const watched = nextTasks.filter((task) => watchedIds.includes(task.id));
    if (!watched.length) {
      scheduleNextScoringPoll(generation);
      return;
    }
    const done = watched.filter((task) => ["success", "failed", "cancelled"].includes(task.status));
    if (done.length === watchedIds.length) {
      stopScoringPolling();
      if (sameEntityId(editingAssignmentId.value, assignmentId)) {
        refreshEditingAssignmentStats(assignmentId);
      }
      const failed = done.filter((task) => task.status === "failed").length;
      const cancelled = done.filter((task) => task.status === "cancelled").length;
      if (cancelled) {
        ElMessage.warning(`AI 评分已结束，${cancelled} 个未完成任务已停止`);
      } else if (failed) {
        ElMessage.warning(`AI 评分完成，${failed} 个任务失败`);
      } else {
        ElMessage.success("AI 评分已全部完成");
      }
      return;
    }
    scheduleNextScoringPoll(generation);
  } catch (error) {
    if (generation === scoringPollGeneration && sameEntityId(selectedAssignment.value, assignmentId)) {
      stopScoringPolling(false);
      ElMessage.error(messageOf(error));
    }
  }
}

async function cancelCurrentScoring() {
  if (!selectedAssignment.value || !canCancelCurrentScoring.value || cancellingScoring.value) return;
  try {
    await ElMessageBox.confirm(
      "确认结束当前批次的 AI 评分？等待中的任务将不再执行，执行中的结果会被忽略；已完成任务不受影响。",
      "结束当前 AI 评分",
      {
        type: "warning",
        confirmButtonText: "确认结束",
        cancelButtonText: "继续评分"
      }
    );
    cancellingScoring.value = true;
    const cancelledTasks = await props.api.post(
      `/api/v1/ai-tasks/cancel-current?assignment_id=${selectedAssignment.value}`,
      {}
    );
    stopScoringPolling();
    await refreshSubmissions();
    if (cancelledTasks?.length) {
      ElMessage.success(`已结束当前 AI 评分，停止 ${cancelledTasks.length} 个未完成任务`);
    } else {
      ElMessage.info("当前没有可结束的 AI 评分任务");
    }
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  } finally {
    cancellingScoring.value = false;
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
  if (status === "cancelled") return "已结束";
  return status || "未知";
}

function taskStudentText(task) {
  const identity = [task.studentRealName, task.studentUsername].filter(Boolean).join(" · ");
  return identity || `提交 #${task.submissionId || "--"}`;
}

function taskStudentDetail(task) {
  const identity = taskStudentText(task);
  return task.submissionFileName ? `${identity}：${task.submissionFileName}` : identity;
}

function taskStatusType(status) {
  if (status === "success") return "success";
  if (status === "running") return "warning";
  if (status === "failed") return "danger";
  if (status === "cancelled") return "info";
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

function cleanupFileType(type) {
  const labels = {
    submission_zip: "学生提交",
    rubric_word: "评分文档",
    rubric_excel: "评分表格"
  };
  return labels[type] || type || "未知";
}

function semesterCleanupFileType(type) {
  const labels = {
    submission_zip: "提交 ZIP",
    rubric_file: "评分附件",
    mixed: "共享类型"
  };
  return labels[type] || type || "未知";
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
