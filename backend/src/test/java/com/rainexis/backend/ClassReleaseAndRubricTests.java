package com.rainexis.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.entity.*;
import com.rainexis.backend.mapper.*;
import com.rainexis.backend.security.JwtService;
import com.rainexis.backend.service.business.AiScoringService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:class_release;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "app.ai.dispatcher-enabled=false", "APP_ENV_FILE=${java.io.tmpdir}/class-release-empty.env"
})
class ClassReleaseAndRubricTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired TUserMapper users;
    @Autowired TAssignmentMapper assignments;
    @Autowired TRubricTemplateItemMapper items;
    @Autowired TSubmissionMapper submissions;
    @Autowired TProjectStructureMapper structures;
    @Autowired TAiTaskMapper tasks;
    @Autowired TAiReportMapper reports;
    @Autowired AiScoringService scoring;
    @Autowired JwtService jwt;
    MockMvc mvc;
    String admin, teacherA, teacherB, studentA, studentB;
    String classA, classB;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String suffix = UUID.randomUUID().toString().substring(0, 7);
        classA = "班A-" + suffix;
        classB = "班B-" + suffix;
        admin = token("admin", "", "");
        teacherA = token("teacher", "", classA);
        teacherB = token("teacher", "", classB);
        studentA = token("student", classA, "");
        studentB = token("student", classB, "");
    }

    String token(String role, String className, String teachingClass) {
        TUser user = new TUser();
        user.setUsername(UUID.randomUUID().toString()); user.setPassword("unused"); user.setRole(role);
        user.setClassName(className); user.setTeachingClass(teachingClass); user.setTokenVersion(0);
        users.insert(user);
        return "Bearer " + jwt.createToken(user);
    }

    JsonNode postJson(String path, String token, Object payload) throws Exception {
        var response = mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(payload))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("data");
    }

    @Test void totalTaskNeedsIndependentTeacherReleasesAndEnforcesOwnershipAndSchedule() throws Exception {
        JsonNode template = postJson("/api/v1/rubric-templates", admin, Map.of("templateName", "统一标准", "items", List.of(
                Map.of("dimensionName", "知识点", "pointName", "变量", "pointScore", 60),
                Map.of("dimensionName", "AI过程性应用", "pointName", "AI过程性应用", "pointScore", 20),
                Map.of("dimensionName", "新技术应用", "pointName", "新技术应用", "pointScore", 10),
                Map.of("dimensionName", "系统交互性", "pointName", "系统交互性", "pointScore", 10))));
        List<Long> ids = new ArrayList<>(); template.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        JsonNode master = postJson("/api/v1/assignments", admin, Map.of("title", "统一任务", "classNames", List.of(classA, classB),
                "published", true, "teacherReleaseRequired", true, "rubricTemplateId", template.path("id").asLong(), "selectedRubricItemIds", ids));
        long masterId = master.path("id").asLong();
        mvc.perform(get("/api/v1/assignments").header("Authorization", studentA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + masterId + ")]").isEmpty());
        mvc.perform(get("/api/v1/assignments").header("Authorization", teacherA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + masterId + ")].releasableClassNames[0]").value(classA));

        Map<String, Object> schedule = Map.of("className", classA, "startTime", LocalDateTime.now().plusDays(1).toString(), "endTime", LocalDateTime.now().plusDays(3).toString());
        mvc.perform(post("/api/v1/assignments/" + masterId + "/class-release").header("Authorization", teacherB)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(schedule))).andExpect(status().isForbidden());
        JsonNode child = postJson("/api/v1/assignments/" + masterId + "/class-release", teacherA, schedule);
        long childId = child.path("id").asLong();
        assertThat(child.path("normalizedRubricJson").asText()).isEqualTo(master.path("normalizedRubricJson").asText());
        mvc.perform(post("/api/v1/assignments/" + masterId + "/class-release").header("Authorization", teacherA)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(schedule))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/assignments/" + childId + "/stats").header("Authorization", teacherB)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assignments").header("Authorization", studentA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + childId + ")]").isEmpty());
        mvc.perform(multipart("/api/v1/submissions").file(new org.springframework.mock.web.MockMultipartFile("file", "fixture.zip", "application/zip", new byte[]{1}))
                .param("assignmentId", String.valueOf(childId)).header("Authorization", studentA)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/assignments/" + childId + "/class-schedule").header("Authorization", teacherB)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(schedule))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/assignments/" + childId + "/class-schedule").header("Authorization", teacherA)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("className", classA, "startTime", LocalDateTime.now().minusMinutes(1).toString(), "endTime", LocalDateTime.now().plusDays(2).toString())))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assignments").header("Authorization", studentA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + childId + ")].parentAssignmentId").value((int) masterId));
        mvc.perform(get("/api/v1/assignments").header("Authorization", studentB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + childId + ")]").isEmpty());
        postJson("/api/v1/assignments/" + masterId + "/class-release", teacherB, Map.of("className", classB, "endTime", LocalDateTime.now().plusDays(6).toString()));
        mvc.perform(get("/api/v1/assignments/" + masterId + "/class-releases").header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(delete("/api/v1/assignments/" + masterId).header("Authorization", admin)).andExpect(status().isConflict());
        String noClasses = token("teacher", "", "");
        mvc.perform(get("/api/v1/users/students").header("Authorization", noClasses)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test void legacyBandsAreGroupedWithoutRewritingStoredRowsOrAddingScores() throws Exception {
        JsonNode template = postJson("/api/v1/rubric-templates", admin, Map.of("templateName", "旧模板", "items", List.of(
                Map.of("dimensionName", "知识点运用", "pointName", "变量", "pointScore", 60))));
        long templateId = template.path("id").asLong();
        for (int i = 0; i < 5; i++) {
            TRubricTemplateItem item = new TRubricTemplateItem();
            item.setTemplateId(templateId); item.setDimensionName("AI过程性应用"); item.setDimensionOrder(2 + i);
            item.setPointName("AI过程性应用" + (i + 1)); item.setPointOrder(1); item.setPointScore(BigDecimal.valueOf(20));
            item.setEnabled((byte) 1); item.setCriteria("评分档位：\n- " + (i * 4) + "~" + (i * 4 + 4) + "分：阶段" + i);
            items.insert(item);
        }
        JsonNode response = json.readTree(mvc.perform(get("/api/v1/rubric-templates/" + templateId + "/items").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.get(1).path("pointScore").decimalValue()).isEqualByComparingTo("20");
        assertThat(response.get(1).path("scoringMethod").asText()).isEqualTo("ai_only");
        assertThat(json.readTree(response.get(1).path("scoreBandsJson").asText()).size()).isEqualTo(5);
        assertThat(items.selectCount(new LambdaQueryWrapper<TRubricTemplateItem>().eq(TRubricTemplateItem::getTemplateId, templateId))).isEqualTo(6);
    }

    @Test void mixedScoringAveragesOnlyDualItemsAndKeepsAiOnlyScore() throws Exception {
        List<Map<String, Object>> dimensions = List.of(
                Map.of("name", "知识点", "max_score", 60, "scoring_method", "ai_rule_average"),
                Map.of("name", "新技术", "max_score", 10, "scoring_method", "ai_only"),
                Map.of("name", "AI过程性应用", "max_score", 20, "scoring_method", "ai_only"),
                Map.of("name", "系统交互性", "max_score", 10, "scoring_method", "manual"));
        TAssignment assignment = new TAssignment(); assignment.setTitle("混合评分"); assignment.setTeacherId(1L);
        assignment.setNormalizedRubricJson(json.writeValueAsString(Map.of("dimensions", dimensions))); assignments.insert(assignment);
        TSubmission submission = new TSubmission(); submission.setAssignmentId(assignment.getId()); submission.setStudentId(1L); submission.setFileUrl("fixture.zip"); submissions.insert(submission);
        TProjectStructure structure = new TProjectStructure(); structure.setSubmissionId(submission.getId()); structure.setStructureJson("{\"ai_process\":{\"submitted\":true,\"valid_json_files\":1}}"); structures.insert(structure);
        submission.setProjectStructureId(structure.getId()); submissions.updateById(submission);
        TAiTask task = new TAiTask(); task.setAssignmentId(assignment.getId()); task.setSubmissionId(submission.getId()); task.setStatus("running"); tasks.insert(task);
        TAiReport pending = new TAiReport(); pending.setTaskId(task.getId()); pending.setSubmissionId(submission.getId());
        pending.setLlmStatus("pending"); pending.setKeywordStatus("success"); pending.setAiProcessStatus("pending");
        pending.setKeywordResultJson(json.writeValueAsString(result(List.of(Map.of("name", "知识点", "score", 40, "max_score", 60))))); reports.insert(pending);
        Map<String, Object> callback = new LinkedHashMap<>(result(List.of()));
        callback.put("source_llm_result", result(List.of(Map.of("name", "知识点", "score", 50, "max_score", 60), Map.of("name", "新技术", "score", 8, "max_score", 10))));
        callback.put("ai_process_result", result(List.of(Map.of("name", "AI过程性应用", "score", 16, "max_score", 20))));
        TAiTask done = scoring.handleCallback(task.getId(), "success", callback, null);
        assertThat(done.getStatus()).isEqualTo("success");
        TAiReport saved = reports.selectOne(new LambdaQueryWrapper<TAiReport>().eq(TAiReport::getTaskId, task.getId()));
        assertThat(saved.getTotalScore()).isEqualByComparingTo("69"); // (50+40)/2 + 8 + 16
        JsonNode rows = json.readTree(saved.getScoreDetailJson());
        assertThat(rows.get(1).path("score").decimalValue()).isEqualByComparingTo("8");
        assertThat(rows.get(1).path("rule_score").isNull()).isTrue();
        assertThat(rows.get(3).path("manual_review_required").asBoolean()).isTrue();
    }

    @Test void keywordScoringSkipsAiOnlyItemsAndDoesNotInventFallbackPoints() throws Exception {
        String source = "{\"file_tree\":[{\"path\":\"Main.java\",\"content\":\"class Main { int count = 1; }\"}]}";
        String rubric = json.writeValueAsString(Map.of("dimensions", List.of(
                Map.of("name", "变量", "max_score", 60, "scoring_method", "ai_rule_average"),
                Map.of("name", "新技术", "max_score", 10, "scoring_method", "ai_only"))));
        Map<String, Object> result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(scoring, "keywordScore", source, rubric);
        assertThat((List<?>) result.get("dimension_scores")).hasSize(1);
        String aiOnly = json.writeValueAsString(Map.of("dimensions", List.of(Map.of("name", "新技术", "max_score", 10, "scoring_method", "ai_only"))));
        Map<String, Object> skipped = org.springframework.test.util.ReflectionTestUtils.invokeMethod(scoring, "keywordScore", source, aiOnly);
        assertThat((List<?>) skipped.get("dimension_scores")).isEmpty();
        assertThat((BigDecimal) skipped.get("total_score")).isEqualByComparingTo("0");
    }

    @Test void selectedSubsetScalesBandDescriptionsWithNormalizedMaximum() throws Exception {
        JsonNode template = postJson("/api/v1/rubric-templates", admin, Map.of("templateName", "档位归一化", "items", List.of(
                Map.of("dimensionName", "知识点", "pointName", "变量", "pointScore", 60),
                Map.of("dimensionName", "AI过程性应用", "pointName", "AI过程性应用", "pointScore", 20,
                        "scoreBandsJson", "[{\"score_min\":15,\"score_max\":17,\"criteria\":\"基本完整\"}]"))));
        List<Long> ids = new ArrayList<>(); template.path("items").forEach(value -> ids.add(value.path("id").asLong()));
        JsonNode assignment = postJson("/api/v1/assignments", admin, Map.of("title", "子集", "published", false, "classNames", List.of(classA), "rubricTemplateId", template.path("id").asLong(), "selectedRubricItemIds", ids));
        JsonNode ai = json.readTree(assignment.path("normalizedRubricJson").asText()).path("dimensions").get(1);
        assertThat(ai.path("max_score").decimalValue()).isEqualByComparingTo("25");
        assertThat(ai.path("scoring_bands").get(0).path("score_min").decimalValue()).isEqualByComparingTo("18.75");
        assertThat(ai.path("criteria").asText()).contains("18.75~21.25");
    }

    Map<String, Object> result(List<Map<String, Object>> scores) {
        return new LinkedHashMap<>(Map.of("model_name", "test-model", "model_source", "deepseek", "dimension_scores", scores,
                "total_score", scores.stream().mapToDouble(row -> ((Number) row.get("score")).doubleValue()).sum(),
                "issues", List.of(), "report_markdown", "测试报告", "token_usage", 10));
    }
}
