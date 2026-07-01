<template>
  <section class="auth-screen">
    <el-card class="auth-panel" shadow="always">
      <div class="brand-block">
        <el-icon size="34"><Medal /></el-icon>
        <div>
          <h1>AI Code Grading System</h1>
          <p>课程代码提交、AI 初评、教师复核、成绩发布工作台</p>
        </div>
      </div>
      <el-segmented v-model="mode" :options="modeOptions" class="auth-mode" />
      <el-form label-position="top" autocomplete="off" @submit.prevent="submit">
        <el-form-item label="账号（学生请使用学号）">
          <el-input
            v-model="form.username"
            :name="usernameFieldName"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            :name="passwordFieldName"
            type="password"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <template v-if="mode === 'register'">
          <el-form-item label="姓名">
            <el-input v-model="form.realName" />
          </el-form-item>
          <el-form-item label="班级">
            <el-input v-model="form.className" />
          </el-form-item>
        </template>
        <el-button type="primary" native-type="submit" :loading="loading" class="wide-button">
          {{ mode === "login" ? "登录" : "创建教师账号" }}
        </el-button>
      </el-form>
    </el-card>
  </section>
</template>

<script setup>
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Medal } from "@element-plus/icons-vue";
import { request, messageOf } from "../JS/api.js";

const emit = defineEmits(["sign-in"]);
const mode = ref("login");
const modeOptions = [
  { label: "登录", value: "login" },
  { label: "教师注册", value: "register" }
];
const loading = ref(false);
const fieldSalt = Math.random().toString(36).slice(2);
const usernameFieldName = computed(() => `${mode.value}-account-${fieldSalt}`);
const passwordFieldName = computed(() => `${mode.value}-secret-${fieldSalt}`);
const form = reactive({
  username: "",
  password: "",
  realName: "",
  className: ""
});

async function submit() {
  loading.value = true;
  try {
    const endpoint = mode.value === "login" ? "/api/v1/auth/login" : "/api/v1/auth/register";
    const payload =
      mode.value === "login"
        ? { username: form.username, password: form.password }
        : {
            username: form.username,
            password: form.password,
            realName: form.realName,
            className: form.className
          };
    const response = await request(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    emit("sign-in", { token: response.token, csrfToken: response.csrfToken, user: response.user });
    ElMessage.success("登录成功");
  } catch (error) {
    ElMessage.error(messageOf(error));
  } finally {
    loading.value = false;
  }
}
</script>
