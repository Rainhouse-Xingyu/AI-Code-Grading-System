package com.rainexis.backend;

import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.service.business.RuntimeConfigService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void workerConcurrencyIsEditableAndRequiresModelServiceRestart() throws Exception {
        Path envFile = tempDir.resolve(".env");
        RuntimeConfigService service = new RuntimeConfigService(envFile.toString());
        service.load();

        Map<String, Object> item = service.list().stream()
                .filter(value -> "AI_WORKER_CONCURRENCY".equals(value.get("key")))
                .findFirst()
                .orElseThrow();

        assertThat(item.get("restartRequired")).isEqualTo(true);
        assertThat(item.get("inputType")).isEqualTo("number");
        assertThat(item.get("min")).isEqualTo(1);
        assertThat(item.get("max")).isEqualTo(10);
        assertThat(item.get("defaultValue")).isEqualTo("5");

        service.update(Map.of("AI_WORKER_CONCURRENCY", "8"));

        assertThat(service.getInt("AI_WORKER_CONCURRENCY", 5)).isEqualTo(8);
        assertThat(Files.readString(envFile)).contains("AI_WORKER_CONCURRENCY=8");
    }

    @Test
    void workerConcurrencyRejectsValuesOutsideOneToTen() {
        RuntimeConfigService service = new RuntimeConfigService(tempDir.resolve(".env").toString());
        service.load();

        for (String invalid : new String[]{"0", "11", "2.5", "many"}) {
            assertThatThrownBy(() -> service.update(Map.of("AI_WORKER_CONCURRENCY", invalid)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1 到 10");
        }
    }
}
