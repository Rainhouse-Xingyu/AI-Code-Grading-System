<template>
  <section class="auth-screen">
    <el-card class="auth-panel" shadow="always">
      <div class="brand-block">
        <el-icon size="34"><Medal /></el-icon>
        <div>
          <h1>程序设计作业智能评分系统</h1>
          <p>课程代码提交、AI 初评、教师复核、成绩发布工作台</p>
        </div>
      </div>
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
        <el-button type="primary" native-type="submit" :loading="loading" class="wide-button">
          登录
        </el-button>
      </el-form>
    </el-card>
  </section>
</template>

<script setup>
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { Medal } from "@element-plus/icons-vue";
import { request, messageOf } from "../JS/api.js";

const emit = defineEmits(["sign-in"]);
const loading = ref(false);
const fieldSalt = Math.random().toString(36).slice(2);
const usernameFieldName = `login-account-${fieldSalt}`;
const passwordFieldName = `login-secret-${fieldSalt}`;
const form = reactive({
  username: "",
  password: ""
});

async function submit() {
  loading.value = true;
  try {
    const response = await request("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: form.username, password: form.password })
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
