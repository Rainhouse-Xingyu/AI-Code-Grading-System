<template>
  <section class="desk admin-desk">
    <div class="page-heading">
      <div>
        <h2>系统管理</h2>
        <p>运行配置、教师账号、管理员账号</p>
      </div>
      <div class="toolbar">
        <el-button @click="loadConfig">刷新配置</el-button>
        <el-button @click="loadAccounts">刷新账号</el-button>
      </div>
    </div>

    <div class="admin-grid">
      <el-card shadow="never" class="admin-panel">
        <template #header>
          <div class="card-head">
            <span>运行配置</span>
            <el-tag size="small" type="info">{{ envPath || ".env" }}</el-tag>
          </div>
        </template>
        <el-table :data="configItems" height="430" size="small">
          <el-table-column label="配置项" min-width="230" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tooltip placement="top-start" :content="configDescription(row.key)">
                <div class="config-name">
                  <strong>{{ configLabel(row.key) }}</strong>
                  <span>{{ row.key }}</span>
                </div>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="值" min-width="260">
            <template #default="{ row }">
              <el-input v-model="row.value" :type="row.secret ? 'password' : 'text'" show-password />
            </template>
          </el-table-column>
          <el-table-column label="生效方式" width="110">
            <template #default="{ row }">
              <el-tag :type="row.restartRequired ? 'warning' : 'success'" size="small">
                {{ row.restartRequired ? "需重启" : "立即生效" }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div class="form-actions">
          <el-button type="primary" :loading="configSaving" @click="saveConfig">保存配置</el-button>
        </div>
      </el-card>

      <el-card shadow="never" class="admin-panel">
        <template #header>账号维护</template>
        <el-form label-position="top" class="admin-account-form" @submit.prevent="saveAccount">
          <el-form-item label="教职工号/账号">
            <el-input v-model="accountForm.username" />
          </el-form-item>
          <el-form-item label="姓名">
            <el-input v-model="accountForm.realName" />
          </el-form-item>
          <el-form-item label="账号类型">
            <el-select v-model="accountForm.role">
              <el-option label="教师" value="teacher" />
              <el-option label="管理员" value="admin" />
            </el-select>
          </el-form-item>
          <el-form-item label="学院">
            <el-input v-model="accountForm.college" />
          </el-form-item>
          <el-form-item label="教授课程">
            <el-input v-model="accountForm.teachingCourse" />
          </el-form-item>
          <el-form-item label="教授班级">
            <el-input v-model="accountForm.teachingClass" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="accountForm.email" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="accountForm.phone" />
          </el-form-item>
          <el-form-item label="初始密码">
            <el-input v-model="accountForm.initialPassword" show-password />
          </el-form-item>
          <div class="form-actions">
            <el-button @click="resetForm">清空</el-button>
            <el-button type="primary" :loading="accountSaving" native-type="submit">
              {{ accountForm.id ? "保存账号" : "新增账号" }}
            </el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="admin-panel">
      <template #header>
        <div class="card-head">
          <span>教师与管理员账号</span>
          <div class="toolbar">
            <el-input v-model="accountSearch" size="small" class="admin-search" clearable placeholder="搜索账号/姓名/学院/班级" />
            <el-button size="small" @click="downloadTemplate">导入模板</el-button>
            <el-input v-model="importPassword" size="small" class="admin-password" show-password placeholder="导入默认密码" />
            <el-upload :show-file-list="false" :auto-upload="false" :on-change="importAccounts" accept=".xlsx">
              <el-button size="small" type="success">Excel 导入</el-button>
            </el-upload>
          </div>
        </div>
      </template>
      <el-progress v-if="importProgress > 0 && importProgress < 100" :percentage="importProgress" />
      <el-table :data="filteredAccounts" height="300">
        <el-table-column prop="username" label="账号" width="130" />
        <el-table-column prop="realName" label="姓名" width="110" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'admin' ? 'warning' : 'info'">
              {{ roleText(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="college" label="学院" min-width="130" show-overflow-tooltip />
        <el-table-column prop="teachingCourse" label="课程" min-width="150" show-overflow-tooltip />
        <el-table-column prop="teachingClass" label="教授班级" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" @click="editAccount(row)">编辑</el-button>
            <el-button link type="warning" @click="resetPassword(row)">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { messageOf } from "../JS/api.js";

const props = defineProps({
  api: { type: Object, required: true }
});

const envPath = ref("");
const configItems = ref([]);
const accounts = ref([]);
const accountSearch = ref("");
const importPassword = ref("Teacher123456");
const importProgress = ref(0);
const configSaving = ref(false);
const accountSaving = ref(false);
const accountForm = reactive(emptyAccount());
const configLabels = {
  AI_DISPATCHER_ENABLED: ["AI 调度器开关", "控制后端是否自动领取并执行 AI 评分任务。对应 AI_DISPATCHER_ENABLED。"],
  AI_ENABLE_REMOTE: ["远程 AI 开关", "控制是否允许调用远程模型服务。对应 AI_ENABLE_REMOTE。"],
  AI_MAX_COMPLETION_TOKENS: ["AI 最大输出 Token", "限制模型单次评分返回内容的最大 Token 数。对应 AI_MAX_COMPLETION_TOKENS。"],
  AI_MAX_CONCURRENT_TASKS: ["并发评分任务数", "控制后台最多同时执行多少个 AI 评分任务。对应 AI_MAX_CONCURRENT_TASKS。"],
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
    configItems.value = result.items || [];
  } catch (error) {
    ElMessage.error(messageOf(error));
  }
}

async function saveConfig() {
  configSaving.value = true;
  try {
    const values = Object.fromEntries(configItems.value.map((item) => [item.key, item.value ?? ""]));
    const result = await props.api.put("/api/v1/admin/config", { values });
    envPath.value = result.envPath || envPath.value;
    configItems.value = result.items || configItems.value;
    ElMessage.success("配置已保存");
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
    const payload = { ...accountForm, employeeNo: accountForm.username };
    if (accountForm.id) {
      await props.api.put(`/api/v1/admin/accounts/${accountForm.id}`, payload);
    } else {
      await props.api.post("/api/v1/admin/accounts", payload);
    }
    await loadAccounts();
    resetForm();
    ElMessage.success("账号已保存");
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

function downloadTemplate() {
  props.api.downloadGet("/api/v1/admin/accounts/import-template", "教师管理员导入模板.xlsx");
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
    ElMessage.success(`导入完成：新增 ${result.imported || 0} 个账号`);
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
