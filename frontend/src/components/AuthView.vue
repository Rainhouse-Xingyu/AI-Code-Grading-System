<template>
  <section class="auth-screen">
    <aside class="auth-story">
      <div class="auth-wordmark">CodeGrader</div>
      <div class="auth-story-content">
        <span class="auth-eyebrow">AI-POWERED ASSESSMENT</span>
        <h1>程序设计作业<br /><em>智能评分工作台</em></h1>
        <p>从代码提交到 AI 初评、教师复核，再到成绩发布，让每一次评分清晰、高效且可追溯。</p>
        <ol class="auth-flow" aria-label="评分流程">
          <li>
            <span>01</span>
            <div><strong>提交作业</strong><small>按课程要求上传代码</small></div>
          </li>
          <li>
            <span>02</span>
            <div><strong>AI 初评</strong><small>批量评分并生成报告</small></div>
          </li>
          <li>
            <span>03</span>
            <div><strong>教师复核</strong><small>依据 Rubric 调整评分</small></div>
          </li>
          <li>
            <span>04</span>
            <div><strong>成绩发布</strong><small>统一推送最终结果</small></div>
          </li>
        </ol>
      </div>
      <div class="auth-story-footer"><span /> 服务运行正常</div>
    </aside>
    <main class="auth-form-zone">
      <div class="auth-mobile-wordmark">CodeGrader</div>
      <el-card class="auth-panel" shadow="never">
        <div class="brand-block">
          <h2>账号登录</h2>
          <p>学生请使用学号登录，教师请使用教职工号</p>
        </div>
        <el-form label-position="top" autocomplete="off" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input
            v-model="form.username"
            :name="usernameFieldName"
            placeholder="请输入学号 / 教职工号"
            size="large"
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
            placeholder="请输入密码"
            size="large"
            autocomplete="new-password"
            show-password
          />
        </el-form-item>
        <el-button type="primary" size="large" native-type="submit" :loading="loading" class="wide-button">
          登录
        </el-button>
        </el-form>
        <div class="auth-help">登录即表示你同意遵守学校信息系统使用规范</div>
      </el-card>
    </main>
  </section>
</template>

<script setup>
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
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
