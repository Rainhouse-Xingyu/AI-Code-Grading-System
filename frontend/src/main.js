import { createApp } from "vue";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import App from "./App.vue";
import "./CSS/base.css";
import "./CSS/auth.css";
import "./CSS/teacher.css";
import "./CSS/student.css";

createApp(App).use(ElementPlus).mount("#root");
