<template>
  <main v-if="!user" class="app-shell">
    <AuthView @sign-in="signIn" />
  </main>
  <div v-else class="workspace">
    <aside class="sidebar">
      <div class="sidebar-title">
        <el-icon><School /></el-icon>
        <span>评分系统</span>
      </div>
      <el-button v-if="user.role !== 'student'" :type="view === 'teacher' ? 'primary' : 'default'" plain @click="view = 'teacher'">
        教师工作台
      </el-button>
      <el-button v-if="user.role === 'student'" :type="view === 'student' ? 'primary' : 'default'" plain @click="view = 'student'">
        学生端
      </el-button>
      <nav v-if="view === 'teacher'" class="sidebar-nav">
        <button
          v-for="item in teacherNavItems"
          :key="item.key"
          type="button"
          :class="{ active: teacherModule === item.key }"
          @click="teacherModule = item.key"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </nav>
    </aside>
    <section class="main">
      <header class="topbar">
        <div>
          <strong>{{ user.realName || user.username }}</strong>
          <span>{{ userSubtitle }}</span>
        </div>
        <div class="topbar-actions">
          <el-alert
            v-if="user.needPasswordChange"
            title="建议修改初始密码"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-button @click="passwordDialogVisible = true">个人设置</el-button>
          <el-button @click="signOut">退出</el-button>
        </div>
      </header>
      <TeacherDesk v-if="view === 'teacher'" :api="api" :user="user" :active-module="teacherModule" />
      <StudentDesk v-else :api="api" />
    </section>
    <el-dialog v-model="passwordDialogVisible" title="个人设置" width="460px">
      <el-form label-position="top" @submit.prevent="saveProfile">
        <el-form-item label="姓名">
          <el-input v-model="profileForm.realName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="profileForm.email" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="profileForm.phone" />
        </el-form-item>
      </el-form>
      <el-divider />
      <el-form label-position="top" @submit.prevent="changePassword">
        <el-form-item label="旧密码">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button :loading="profileSaving" @click="saveProfile">保存资料</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="changePassword">修改密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { Collection, Cpu, Download, EditPen, Files, School, UploadFilled, User } from "@element-plus/icons-vue";
import { createApi, messageOf } from "./JS/api.js";
import AuthView from "./components/AuthView.vue";
import TeacherDesk from "./components/TeacherDesk.vue";
import StudentDesk from "./components/StudentDesk.vue";

const token = ref(localStorage.getItem("token") || "");
const csrfToken = ref(localStorage.getItem("csrfToken") || "");
const storedUser = localStorage.getItem("user");
const user = ref(storedUser ? JSON.parse(storedUser) : null);
const idleTimeoutMs = 15 * 60 * 1000;
const lastActivityKey = "lastActivityAt";
let idleTimer = null;
const view = ref(user.value?.role === "student" ? "student" : "teacher");
const teacherModule = ref("assignments");
const api = computed(() => createApi(token.value, csrfToken.value, {
  onAuthUpdate: signIn,
  onAuthExpired: () => signOut("登录状态已失效，请重新登录"),
  onActivity: markActivity,
  isSessionActive
}));
const userSubtitle = computed(() => {
  if (!user.value) return "";
  const parts = [user.value.role, user.value.className || "未设置班级"];
  if (user.value.role === "student" && user.value.teacherRealName) {
    parts.push(`任课教师 ${user.value.teacherRealName}`);
  }
  return parts.join(" · ");
});
const teacherNavItems = computed(() => [
  { key: "assignments", label: "作业", icon: Files },
  { key: "students", label: "学生", icon: User },
  ...(user.value?.role === "admin" ? [{ key: "templates", label: "评分模板", icon: Collection }] : []),
  { key: "submissions", label: "提交评分", icon: UploadFilled },
  { key: "tasks", label: "AI 任务", icon: Cpu },
  { key: "downloads", label: "文件下载", icon: Download },
  { key: "review", label: "复核发布", icon: EditPen }
]);
const passwordDialogVisible = ref(false);
const profileSaving = ref(false);
const passwordSaving = ref(false);
const profileForm = reactive({
  realName: "",
  email: "",
  phone: ""
});
const passwordForm = reactive({
  oldPassword: "",
  newPassword: ""
});

watch(passwordDialogVisible, (visible) => {
  if (!visible || !user.value) return;
  profileForm.realName = user.value.realName || "";
  profileForm.email = user.value.email || "";
  profileForm.phone = user.value.phone || "";
});

watch(user, (nextUser) => {
  if (nextUser?.role !== "admin" && teacherModule.value === "templates") {
    teacherModule.value = "assignments";
  }
});

onMounted(() => {
  if (user.value && !isSessionActive()) {
    signOut("登录状态已超过 15 分钟未操作，请重新登录");
    return;
  }
  installActivityWatchers();
  startIdleTimer();
});

onUnmounted(() => {
  removeActivityWatchers();
  if (idleTimer) {
    window.clearInterval(idleTimer);
  }
});

function signIn(payload) {
  token.value = payload.token;
  csrfToken.value = payload.csrfToken || "";
  user.value = payload.user;
  view.value = payload.user.role === "student" ? "student" : "teacher";
  localStorage.setItem("token", payload.token);
  localStorage.setItem("csrfToken", csrfToken.value);
  localStorage.setItem("user", JSON.stringify(payload.user));
  markActivity();
}

function signOut(message = "") {
  token.value = "";
  csrfToken.value = "";
  user.value = null;
  localStorage.removeItem("token");
  localStorage.removeItem("csrfToken");
  localStorage.removeItem("user");
  localStorage.removeItem(lastActivityKey);
  if (message) {
    ElMessage.warning(message);
  }
}

function markActivity() {
  if (!user.value) return;
  localStorage.setItem(lastActivityKey, String(Date.now()));
}

function isSessionActive() {
  if (!user.value) return false;
  const lastActivityAt = Number(localStorage.getItem(lastActivityKey) || 0);
  return lastActivityAt > 0 && Date.now() - lastActivityAt <= idleTimeoutMs;
}

function checkIdleTimeout() {
  if (user.value && !isSessionActive()) {
    signOut("登录状态已超过 15 分钟未操作，请重新登录");
  }
}

function installActivityWatchers() {
  ["click", "keydown", "mousemove", "scroll", "touchstart"].forEach((eventName) => {
    window.addEventListener(eventName, markActivity, { passive: true });
  });
}

function removeActivityWatchers() {
  ["click", "keydown", "mousemove", "scroll", "touchstart"].forEach((eventName) => {
    window.removeEventListener(eventName, markActivity);
  });
}

function startIdleTimer() {
  if (!localStorage.getItem(lastActivityKey) && user.value) {
    markActivity();
  }
  idleTimer = window.setInterval(checkIdleTimeout, 30 * 1000);
}

async function changePassword() {
  passwordSaving.value = true;
  try {
    const params = new URLSearchParams();
    params.set("oldPassword", passwordForm.oldPassword);
    params.set("newPassword", passwordForm.newPassword);
    await api.value.put(`/api/v1/users/me/password?${params.toString()}`, {});
    passwordForm.oldPassword = "";
    passwordForm.newPassword = "";
    passwordDialogVisible.value = false;
    ElMessage.success("密码已修改，请重新登录");
    signOut();
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    passwordSaving.value = false;
  }
}

async function saveProfile() {
  profileSaving.value = true;
  try {
    const nextUser = await api.value.put("/api/v1/users/me", { ...profileForm });
    user.value = nextUser;
    localStorage.setItem("user", JSON.stringify(nextUser));
    ElMessage.success("资料已保存");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    profileSaving.value = false;
  }
}
</script>
