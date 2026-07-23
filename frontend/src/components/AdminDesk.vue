<template>
  <section class="desk admin-desk">
    <header class="module-intro admin-intro">
      <div class="module-intro-copy">
        <span class="module-kicker">系统控制台</span>
        <h2>系统管理</h2>
        <p>集中维护运行参数、服务生效方式，以及教师和管理员账号。</p>
      </div>
      <div class="module-intro-actions">
        <el-button @click="loadConfig">刷新配置</el-button>
        <el-button @click="loadAccounts">刷新账号</el-button>
      </div>
    </header>

    <div class="module-summary-grid admin-summary-grid" aria-label="系统管理概览">
      <MetricCard label="配置项" :value="configItems.length" />
      <MetricCard label="需重启生效" :value="restartConfigCount" />
      <MetricCard label="教师账号" :value="teacherAccountCount" />
      <MetricCard label="管理员账号" :value="adminAccountCount" />
    </div>

    <div class="admin-grid">
      <el-card shadow="never" class="admin-panel data-panel admin-config-panel">
        <template #header>
          <div class="panel-toolbar">
            <div class="panel-toolbar-main">
              <strong>运行配置</strong>
              <span>修改模型、队列、数据库和安全参数</span>
            </div>
            <div class="panel-toolbar-actions">
              <el-tag size="small" type="info" class="admin-env-tag">{{ envPath || ".env" }}</el-tag>
            </div>
          </div>
        </template>

        <div class="admin-config-categories" aria-label="配置分类">
          <div v-for="category in configCategorySummaries" :key="category.key" class="admin-config-category">
            <i :class="`is-${category.key}`" />
            <span>{{ category.label }}</span>
            <strong>{{ category.count }}</strong>
          </div>
        </div>

        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="保存前请确认生效方式；标记为“需重启”的配置不会立刻作用于正在运行的服务。"
          class="admin-restart-alert"
        />

        <div class="admin-table-scroll config-table-scroll">
          <el-table :data="configItems" height="460" size="small" class="admin-config-table">
            <el-table-column label="分类" width="92">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" type="info">{{ configCategoryName(row.key) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="配置项" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">
                <el-tooltip placement="top-start" :content="configDescription(row.key)">
                  <div class="config-name">
                    <div class="config-name-title">
                      <strong>{{ configLabel(row.key) }}</strong>
                      <span v-if="row.secret" class="admin-sensitive-indicator">敏感</span>
                    </div>
                    <span>{{ row.key }}</span>
                  </div>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="配置值" min-width="270">
              <template #default="{ row }">
                <div class="admin-config-value">
                  <el-input-number
                    v-if="row.inputType === 'number'"
                    v-model="row.value"
                    :min="row.min"
                    :max="row.max"
                    :step="1"
                    :precision="0"
                    step-strictly
                    controls-position="right"
                    class="config-number-input"
                  />
                  <el-input v-else v-model="row.value" :type="row.secret ? 'password' : 'text'" show-password />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="生效方式" width="138">
              <template #default="{ row }">
                <el-tag :type="row.restartRequired ? 'warning' : 'success'" size="small">
                  {{ restartLabel(row) }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂未读取到运行配置" :image-size="76" />
            </template>
          </el-table>
        </div>

        <div class="admin-config-footer">
          <div>
            <strong>{{ immediateConfigCount }} 项可立即生效</strong>
            <span>{{ restartConfigCount }} 项保存后需要重启或重建对应服务</span>
          </div>
          <el-button type="primary" :loading="configSaving" @click="saveConfig">保存配置</el-button>
        </div>
      </el-card>

      <el-card ref="accountEditor" shadow="never" class="admin-panel data-panel admin-account-panel">
        <template #header>
          <div class="panel-toolbar">
            <div class="panel-toolbar-main">
              <strong>账号维护</strong>
              <span>{{ accountForm.id ? "更新已选账号资料" : "创建教师或管理员账号" }}</span>
            </div>
            <div class="panel-toolbar-actions">
              <el-tag :type="accountForm.id ? 'warning' : 'primary'" size="small">
                {{ accountForm.id ? "编辑中" : "新增" }}
              </el-tag>
            </div>
          </div>
        </template>

        <el-form label-position="top" class="admin-account-form" @submit.prevent="saveAccount">
          <section class="admin-form-section">
            <div class="admin-form-section-head">
              <strong>基本身份</strong>
              <span>用于登录和权限识别</span>
            </div>
            <div class="admin-form-grid">
              <el-form-item label="教职工号 / 账号">
                <el-input v-model="accountForm.username" placeholder="请输入唯一账号" />
              </el-form-item>
              <el-form-item label="姓名">
                <el-input v-model="accountForm.realName" placeholder="请输入姓名" />
              </el-form-item>
              <el-form-item label="账号类型">
                <el-select v-model="accountForm.role" placeholder="选择账号类型">
                  <el-option label="教师" value="teacher" />
                  <el-option label="管理员" value="admin" />
                </el-select>
              </el-form-item>
              <el-form-item :label="accountForm.id ? '新密码（留空不修改）' : '初始密码'" class="admin-password-field">
                <el-input v-model="accountForm.initialPassword" type="password" show-password autocomplete="new-password" />
                <span class="admin-field-help">
                  {{ accountForm.id ? "填写后将使旧密码立即失效" : "默认 Teacher123456，首次登录后建议修改" }}
                </span>
              </el-form-item>
            </div>
          </section>

          <section class="admin-form-section">
            <div class="admin-form-section-head">
              <strong>教学信息</strong>
              <span>用于教师账号归属和筛选</span>
            </div>
            <div class="admin-form-grid">
              <el-form-item label="学院">
                <el-input v-model="accountForm.college" placeholder="例如：计算机学院" />
              </el-form-item>
              <el-form-item label="教授课程">
                <el-input v-model="accountForm.teachingCourse" placeholder="请输入课程名称" />
              </el-form-item>
              <el-form-item label="教授班级" class="admin-form-span-two">
                <el-input v-model="accountForm.teachingClass" placeholder="请输入班级名称" />
              </el-form-item>
            </div>
          </section>

          <section class="admin-form-section">
            <div class="admin-form-section-head">
              <strong>联系信息</strong>
              <span>用于完善账号资料</span>
            </div>
            <div class="admin-form-grid">
              <el-form-item label="邮箱">
                <el-input v-model="accountForm.email" placeholder="name@example.com" />
              </el-form-item>
              <el-form-item label="手机号">
                <el-input v-model="accountForm.phone" placeholder="请输入手机号" />
              </el-form-item>
            </div>
          </section>

          <div class="form-actions admin-form-actions">
            <el-button @click="resetForm">清空</el-button>
            <el-button type="primary" :loading="accountSaving" native-type="submit">
              {{ accountForm.id ? "保存账号" : "新增账号" }}
            </el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="admin-panel data-panel admin-account-list-panel">
      <template #header>
        <div class="panel-toolbar">
          <div class="panel-toolbar-main">
            <strong>教师与管理员账号</strong>
            <span>查询账号资料，编辑信息或重置登录密码</span>
          </div>
          <div class="panel-toolbar-actions">
            <el-tag size="small" type="info">显示 {{ filteredAccounts.length }} / {{ accounts.length }}</el-tag>
          </div>
        </div>
      </template>

      <div class="account-list-toolbar">
        <div class="account-search-zone">
          <span class="admin-field-label">搜索账号</span>
          <el-input
            v-model="accountSearch"
            class="admin-search"
            clearable
            placeholder="搜索账号、姓名、学院或班级"
          />
        </div>
        <div class="account-import-zone">
          <div class="admin-import-password">
            <span class="admin-field-label">导入默认密码 <em>敏感</em></span>
            <el-input
              v-model="importPassword"
              class="admin-password"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="导入默认密码"
            />
          </div>
          <div class="account-import-actions">
            <el-button
              type="primary"
              plain
              :icon="Download"
              :loading="templateDownloading"
              @click="downloadTemplate"
            >
              下载教师导入模板
            </el-button>
            <el-upload :show-file-list="false" :auto-upload="false" :on-change="importAccounts" accept=".xlsx">
              <el-button type="success">Excel 导入</el-button>
            </el-upload>
          </div>
          <span class="account-import-help">请先下载模板；教职工号和姓名必填，空白密码使用左侧默认密码。</span>
        </div>
      </div>

      <el-progress
        v-if="importProgress > 0 && importProgress < 100"
        :percentage="importProgress"
        class="admin-import-progress"
      />

      <div class="admin-table-scroll account-table-scroll">
        <el-table :data="filteredAccounts" height="330" class="admin-account-table">
          <el-table-column prop="username" label="账号" width="140" />
          <el-table-column prop="realName" label="姓名" width="110" />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 'admin' ? 'warning' : 'info'">
                {{ roleText(row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="college" label="学院" min-width="140" show-overflow-tooltip />
          <el-table-column prop="teachingCourse" label="课程" min-width="160" show-overflow-tooltip />
          <el-table-column prop="teachingClass" label="教授班级" min-width="160" show-overflow-tooltip />
          <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" fixed="right" width="170">
            <template #default="{ row }">
              <div class="admin-row-actions">
                <el-button link type="primary" @click="editAccount(row)">编辑</el-button>
                <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="accountSearch ? '没有匹配的账号' : '暂时没有教师或管理员账号'" :image-size="82" />
          </template>
        </el-table>
      </div>
    </el-card>
  </section>
</template>

<script setup>
import { computed, h, nextTick, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { Download } from "@element-plus/icons-vue";
import { messageOf } from "../JS/api.js";
import MetricCard from "./MetricCard.vue";

const props = defineProps({
  api: { type: Object, required: true }
});
const accountEditor = ref(null);

const envPath = ref("");
const configItems = ref([]);
const accounts = ref([]);
const accountSearch = ref("");
const importPassword = ref("Teacher123456");
const importProgress = ref(0);
const templateDownloading = ref(false);
const configSaving = ref(false);
const accountSaving = ref(false);
const accountForm = reactive(emptyAccount());
const configLabels = {
  AI_DISPATCHER_ENABLED: ["AI 调度器开关", "控制后端是否自动领取并执行 AI 评分任务。对应 AI_DISPATCHER_ENABLED。"],
  AI_ENABLE_REMOTE: ["远程 AI 开关", "控制是否允许调用远程模型服务。对应 AI_ENABLE_REMOTE。"],
  AI_MAX_COMPLETION_TOKENS: ["AI 最大输出 Token", "限制模型单次评分返回内容的最大 Token 数。对应 AI_MAX_COMPLETION_TOKENS。"],
  AI_MAX_CONCURRENT_TASKS: ["后端入队调度并发", "控制后端同时领取并推入 Redis 的任务数，不等同于模型请求并发。对应 AI_MAX_CONCURRENT_TASKS。"],
  AI_WORKER_CONCURRENCY: ["模型评分并发数", "模型服务同时从 Redis 领取并调用模型的作业数。范围 1～10，推荐 5；保存后需重建 model-service。"],
  AI_MODEL: ["远程模型名称", "远程 AI 服务使用的模型名称。对应 AI_MODEL。"],
  AI_PROVIDER: ["AI 服务提供方", "选择评分优先使用的模型提供方，例如 deepseek 或 local。对应 AI_PROVIDER。"],
  AI_QUEUE_ENABLED: ["AI 队列开关", "控制是否通过 Redis 队列提交评分任务。对应 AI_QUEUE_ENABLED。"],
  AI_REDIS_QUEUE: ["AI 队列名称", "Redis 中保存评分任务的队列名。对应 AI_REDIS_QUEUE。"],
  AI_RUNNING_TIMEOUT_MINUTES: ["任务超时分钟数", "运行中的评分任务超过该时间会被重新排队。对应 AI_RUNNING_TIMEOUT_MINUTES。"],
  AI_DISPATCH_INTERVAL_MS: ["调度间隔毫秒", "后台调度器扫描待评分任务的时间间隔。对应 AI_DISPATCH_INTERVAL_MS。"],
  DB_PASSWORD: ["数据库密码", "后端连接 MySQL 使用的密码，修改通常需要重启服务。对应 DB_PASSWORD。"],
  DB_URL: ["数据库地址", "后端连接 MySQL 的 JDBC 地址，修改需要重启服务。对应 DB_URL。"],
  DB_USERNAME: ["数据库用户名", "后端连接 MySQL 使用的用户名，修改通常需要重启服务。对应 DB_USERNAME。"],
  DEEPSEEK_API_KEY: ["DeepSeek API Key", "调用 DeepSeek 或远程兼容模型服务使用的密钥。对应 DEEPSEEK_API_KEY。"],
  DEEPSEEK_BASE_URL: ["DeepSeek 接口地址", "DeepSeek 或远程兼容模型服务的基础地址。对应 DEEPSEEK_BASE_URL。"],
  DEEPSEEK_TIMEOUT_SECONDS: ["DeepSeek 超时时间", "调用远程模型等待响应的最长秒数。对应 DEEPSEEK_TIMEOUT_SECONDS。"],
  DEEPSEEK_TOKEN_QUOTA: ["DeepSeek Token 配额", "用于统计和提示远程模型 Token 使用量上限。对应 DEEPSEEK_TOKEN_QUOTA。"],
  JWT_EXPIRATION_HOURS: ["登录有效小时数", "JWT 登录令牌有效时长，修改需要重启服务。对应 JWT_EXPIRATION_HOURS。"],
  JWT_SECRET: ["登录签名密钥", "JWT 签名密钥，修改会影响已有登录状态，需要重启服务。对应 JWT_SECRET。"],
  LOCAL_AI_API_KEY: ["本地 AI API Key", "本地 OpenAI 兼容服务如需鉴权时使用的密钥。对应 LOCAL_AI_API_KEY。"],
  LOCAL_AI_BASE_URL: ["本地 AI 接口地址", "本地 OpenAI 兼容模型服务地址。对应 LOCAL_AI_BASE_URL。"],
  LOCAL_AI_MODEL: ["本地模型名称", "本地 AI 服务使用的模型名称。对应 LOCAL_AI_MODEL。"],
  LOCAL_AI_TIMEOUT_SECONDS: ["本地 AI 超时时间", "调用本地模型等待响应的最长秒数。对应 LOCAL_AI_TIMEOUT_SECONDS。"],
  REDIS_HOST: ["Redis 主机", "后端连接 Redis 的主机地址，修改需要重启服务。对应 REDIS_HOST。"],
  REDIS_PASSWORD: ["Redis 密码", "后端连接 Redis 使用的密码，修改需要重启服务。对应 REDIS_PASSWORD。"],
  REDIS_PORT: ["Redis 端口", "后端连接 Redis 的端口，修改需要重启服务。对应 REDIS_PORT。"],
  STORAGE_ROOT: ["文件存储目录", "作业上传、报告等文件的后端存储根目录，修改通常需要重启服务。对应 STORAGE_ROOT。"]
};

const configCategoryDefinitions = [
  { key: "ai", label: "AI 模型" },
  { key: "queue", label: "任务队列" },
  { key: "database", label: "数据库" },
  { key: "security", label: "安全认证" },
  { key: "storage", label: "文件存储" },
  { key: "system", label: "其他" }
];

const restartConfigCount = computed(() => configItems.value.filter((item) => item.restartRequired).length);
const immediateConfigCount = computed(() => configItems.value.length - restartConfigCount.value);
const teacherAccountCount = computed(() => accounts.value.filter((item) => item.role !== "admin").length);
const adminAccountCount = computed(() => accounts.value.filter((item) => item.role === "admin").length);
const configCategorySummaries = computed(() =>
  configCategoryDefinitions
    .map((category) => ({
      ...category,
      count: configItems.value.filter((item) => configCategoryKey(item.key) === category.key).length
    }))
    .filter((category) => category.count > 0)
);

const filteredAccounts = computed(() => {
  const keyword = accountSearch.value.trim().toLowerCase();
  if (!keyword) return accounts.value;
  return accounts.value.filter((item) =>
    [item.username, item.realName, item.college, item.teachingCourse, item.teachingClass]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword))
  );
});

onMounted(() => {
  loadConfig();
  loadAccounts();
});

async function loadConfig() {
  try {
    const result = await props.api.get("/api/v1/admin/config");
    envPath.value = result.envPath || "";
    configItems.value = normalizeConfigItems(result.items || []);
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function saveConfig() {
  const invalidNumber = configItems.value.find((item) => {
    if (item.inputType !== "number") return false;
    const value = Number(item.value);
    return !Number.isInteger(value) || value < item.min || value > item.max;
  });
  if (invalidNumber) {
    ElMessage.error(`${configLabel(invalidNumber.key)}必须是 ${invalidNumber.min} 到 ${invalidNumber.max} 之间的整数`);
    return;
  }
  const workerConcurrencyChanged = configItems.value.some(
    (item) => item.key === "AI_WORKER_CONCURRENCY" && String(item.value) !== String(item.originalValue)
  );
  configSaving.value = true;
  try {
    const values = Object.fromEntries(
      configItems.value.map((item) => [item.key, item.value == null ? "" : String(item.value)])
    );
    const result = await props.api.put("/api/v1/admin/config", { values });
    envPath.value = result.envPath || envPath.value;
    configItems.value = normalizeConfigItems(result.items || configItems.value);
    ElMessage.success(
      workerConcurrencyChanged
        ? "并发数已保存；请在没有执行中评分任务时重建 model-service 后生效"
        : "配置已保存"
    );
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    configSaving.value = false;
  }
}

async function loadAccounts() {
  try {
    accounts.value = await props.api.get("/api/v1/admin/accounts");
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function saveAccount() {
  accountSaving.value = true;
  try {
    const editing = Boolean(accountForm.id);
    const initialPassword = accountForm.initialPassword || "Teacher123456";
    const payload = { ...accountForm, employeeNo: accountForm.username };
    let savedAccount;
    if (accountForm.id) {
      savedAccount = await props.api.put(`/api/v1/admin/accounts/${accountForm.id}`, payload);
    } else {
      savedAccount = await props.api.post("/api/v1/admin/accounts", payload);
    }
    await loadAccounts();
    resetForm();
    if (editing) {
      ElMessage.success("账号已保存；如填写了新密码，旧密码已失效");
    } else {
      const accountRoleLabel = payload.role === "admin" ? "管理员" : "教师";
      const credentialMessage = h("div", { class: "account-credential-message" }, [
        h("p", `账号已创建，请将以下登录信息交给${accountRoleLabel}：`),
        h("p", [h("strong", "登录账号："), savedAccount?.username || payload.username.trim()]),
        h("p", [h("strong", "初始密码："), initialPassword]),
        h("small", "首次登录后建议立即修改密码。")
      ]);
      ElMessageBox.alert(credentialMessage, `${accountRoleLabel}账号创建成功`, {
        confirmButtonText: "我已记录",
        closeOnClickModal: false,
        closeOnPressEscape: false
      }).catch(() => {});
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    accountSaving.value = false;
  }
}

function editAccount(row) {
  Object.assign(accountForm, emptyAccount(), row, {
    initialPassword: ""
  });
  nextTick(() => {
    const editorElement = accountEditor.value?.$el || accountEditor.value;
    editorElement?.scrollIntoView({ behavior: "smooth", block: "start" });
    editorElement?.querySelector("input")?.focus({ preventScroll: true });
  });
  ElMessage.info(`已载入 ${row.realName || row.username} 的账号资料`);
}

function resetForm() {
  Object.assign(accountForm, emptyAccount());
}

async function resetPassword(row) {
  try {
    const { value } = await ElMessageBox.prompt(`请输入 ${row.realName || row.username} 的新密码`, "重置密码", {
      inputValue: "Teacher123456",
      inputType: "password",
      confirmButtonText: "确认重置",
      cancelButtonText: "取消"
    });
    const params = new URLSearchParams();
    params.set("newPassword", value);
    await props.api.post(`/api/v1/admin/accounts/${row.id}/reset-password?${params.toString()}`, {});
    ElMessage.success("密码已重置");
  } catch (error) {
    if (error !== "cancel") {
      ElMessage.error(messageOf(error));
    }
  }
}

async function downloadTemplate() {
  templateDownloading.value = true;
  try {
    await props.api.downloadGet("/api/v1/admin/accounts/import-template", "教师账号导入模板.xlsx");
    ElMessage.success("教师导入模板已下载");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    templateDownloading.value = false;
  }
}

async function importAccounts(uploadFile) {
  if (!uploadFile.raw) return;
  const form = new FormData();
  form.append("file", uploadFile.raw);
  form.append("initialPassword", importPassword.value);
  importProgress.value = 1;
  try {
    const result = await props.api.upload("/api/v1/admin/accounts/import", form, {
      onProgress: (percent) => {
        importProgress.value = percent;
      }
    });
    await loadAccounts();
    const skipped = result.skipped?.length || 0;
    const failed = result.failed?.length || 0;
    const summary = `导入完成：新增 ${result.imported || 0}，跳过 ${skipped}，失败 ${failed}`;
    if (failed > 0) {
      ElMessage.warning(summary);
    } else {
      ElMessage.success(summary);
    }
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    window.setTimeout(() => {
      importProgress.value = 0;
    }, 600);
  }
}

function roleText(role) {
  if (role === "admin") return "管理员";
  return "教师";
}

function configLabel(key) {
  return configLabels[key]?.[0] || key;
}

function configDescription(key) {
  return configLabels[key]?.[1] || `系统配置项，对应 ${key}。`;
}

function configCategoryKey(key) {
  if (key.startsWith("DB_")) return "database";
  if (key.startsWith("REDIS_") || key.includes("QUEUE") || key.includes("DISPATCH")) return "queue";
  if (key.startsWith("JWT_")) return "security";
  if (key.startsWith("STORAGE_")) return "storage";
  if (key.startsWith("AI_") || key.startsWith("DEEPSEEK_") || key.startsWith("LOCAL_AI_")) return "ai";
  return "system";
}

function configCategoryName(key) {
  const categoryKey = configCategoryKey(key);
  return configCategoryDefinitions.find((category) => category.key === categoryKey)?.label || "其他";
}

function normalizeConfigItems(items) {
  return items.map((item) => {
    if (item.inputType !== "number") {
      return { ...item, originalValue: item.value ?? "" };
    }
    const rawValue = item.value === "" || item.value == null ? item.defaultValue : item.value;
    const numberValue = Number(rawValue);
    return {
      ...item,
      value: Number.isFinite(numberValue) ? numberValue : Number(item.defaultValue || item.min || 1),
      originalValue: Number.isFinite(numberValue) ? numberValue : Number(item.defaultValue || item.min || 1)
    };
  });
}

function restartLabel(row) {
  if (!row.restartRequired) return "立即生效";
  return row.key === "AI_WORKER_CONCURRENCY" ? "需重建模型服务" : "需重启";
}

function emptyAccount() {
  return {
    id: null,
    username: "",
    role: "teacher",
    realName: "",
    email: "",
    phone: "",
    college: "",
    teachingCourse: "",
    teachingClass: "",
    initialPassword: "Teacher123456"
  };
}
</script>

<style src="../CSS/admin-pages.css"></style>
