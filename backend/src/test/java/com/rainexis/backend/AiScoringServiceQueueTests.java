package com.rainexis.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.entity.TAiReport;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TProjectStructure;
import com.rainexis.backend.entity.TRubric;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAiLogMapper;
import com.rainexis.backend.mapper.TAiReportMapper;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TProjectStructureMapper;
import com.rainexis.backend.mapper.TRubricMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.service.business.AiScoringService;
import com.rainexis.backend.service.business.RuntimeConfigService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiScoringServiceQueueTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void queueModePushesTaskToRedisAndWaitsForCallback() throws Exception {
        TAiTaskMapper taskMapper = mock(TAiTaskMapper.class);
        TAiLogMapper logMapper = mock(TAiLogMapper.class);
        TAiReportMapper reportMapper = mock(TAiReportMapper.class);
        TSubmissionMapper submissionMapper = mock(TSubmissionMapper.class);
        TAssignmentMapper assignmentMapper = mock(TAssignmentMapper.class);
        TProjectStructureMapper structureMapper = mock(TProjectStructureMapper.class);
        TRubricMapper rubricMapper = mock(TRubricMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ListOperations<String, String> listOperations = mock(ListOperations.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);

        TAiTask task = new TAiTask();
        task.setId(42L);
        task.setAssignmentId(7L);
        task.setSubmissionId(99L);
        task.setModelName("DeepSeek/DeepSeek-R1");
        task.setStatus("pending");

        TSubmission submission = new TSubmission();
        submission.setId(99L);
        submission.setAssignmentId(7L);
        submission.setProjectStructureId(123L);
        submission.setStatus("scoring");

        TProjectStructure structure = new TProjectStructure();
        structure.setId(123L);
        structure.setSubmissionId(99L);
        structure.setStructureJson("""
                {"total_files":1,"file_tree":[{"path":"src/Main.java","content":"class Main {}"}],"dependency_graph":{}}
                """);

        TRubric rubric = new TRubric();
        rubric.setAssignmentId(7L);
        rubric.setIsActive((byte) 1);
        rubric.setRubricJson("""
                {"dimensions":[{"name":"功能完整性","max_score":100,"criteria":"代码可运行"}]}
                """);

        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(runtimeConfigService.getBoolean(eq("AI_QUEUE_ENABLED"), eq(true))).thenReturn(true);
        when(runtimeConfigService.get("AI_REDIS_QUEUE", "ai:grading:tasks")).thenReturn("ai:grading:tasks");
        when(taskMapper.selectById(42L)).thenReturn(task);
        when(submissionMapper.selectById(99L)).thenReturn(submission);
        when(structureMapper.selectById(123L)).thenReturn(structure);
        when(rubricMapper.selectOne(any())).thenReturn(rubric);

        AiScoringService service = new AiScoringService(
                taskMapper,
                logMapper,
                reportMapper,
                submissionMapper,
                assignmentMapper,
                structureMapper,
                rubricMapper,
                objectMapper,
                redisProvider,
                "ai:grading:tasks",
                "",
                "https://example.invalid/v1/models",
                "",
                "",
                "Qwen2.5-Coder-7B-Instruct",
                "DeepSeek/DeepSeek-R1",
                "deepseek",
                600,
                600,
                8192,
                false,
                true,
                false,
                runtimeConfigService
        );

        TAiTask queued = service.processTask(42L);

        assertThat(queued.getStatus()).isEqualTo("running");
        assertThat(queued.getStartTime()).isBeforeOrEqualTo(LocalDateTime.now());
        verify(listOperations).leftPush(eq("ai:grading:tasks"), any(String.class));
        verify(reportMapper, never()).insert(any(TAiReport.class));

        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listOperations).leftPush(eq("ai:grading:tasks"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(payload.path("task_id").asLong()).isEqualTo(42L);
        assertThat(payload.path("submission_id").asLong()).isEqualTo(99L);
        assertThat(payload.path("assignment_id").asLong()).isEqualTo(7L);
        assertThat(payload.path("code_json").path("total_files").asInt()).isEqualTo(1);
        assertThat(payload.path("rubric_json").path("dimensions").get(0).path("name").asText()).isEqualTo("功能完整性");
    }
}
