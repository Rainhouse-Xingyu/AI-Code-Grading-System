package com.rainexis.backend.service.business;

import com.rainexis.backend.common.BusinessException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RuntimeConfigService {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "DEEPSEEK_API_KEY",
            "DEEPSEEK_BASE_URL",
            "LOCAL_AI_BASE_URL",
            "LOCAL_AI_API_KEY",
            "LOCAL_AI_MODEL",
            "AI_MODEL",
            "AI_PROVIDER",
            "DEEPSEEK_TIMEOUT_SECONDS",
            "LOCAL_AI_TIMEOUT_SECONDS",
            "AI_MAX_COMPLETION_TOKENS",
            "DEEPSEEK_TOKEN_QUOTA",
            "AI_ENABLE_REMOTE",
            "AI_QUEUE_ENABLED",
            "AI_REDIS_QUEUE",
            "AI_DISPATCHER_ENABLED",
            "AI_MAX_CONCURRENT_TASKS",
            "AI_DISPATCH_INTERVAL_MS",
            "AI_RUNNING_TIMEOUT_MINUTES",
            "STORAGE_ROOT",
            "JWT_SECRET",
            "JWT_EXPIRATION_HOURS",
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "REDIS_HOST",
            "REDIS_PORT",
            "REDIS_PASSWORD"
    );
    private static final Set<String> RESTART_REQUIRED_KEYS = Set.of(
            "STORAGE_ROOT",
            "JWT_SECRET",
            "JWT_EXPIRATION_HOURS",
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "REDIS_HOST",
            "REDIS_PORT",
            "REDIS_PASSWORD"
    );
    private static final Set<String> SECRET_KEYS = Set.of(
            "DEEPSEEK_API_KEY",
            "LOCAL_AI_API_KEY",
            "JWT_SECRET",
            "DB_PASSWORD",
            "REDIS_PASSWORD"
    );

    private final Path envPath;
    private volatile Map<String, String> values = Map.of();

    public RuntimeConfigService(@Value("${APP_ENV_FILE:}") String configuredEnvPath) {
        this.envPath = resolveEnvPath(configuredEnvPath);
    }

    @PostConstruct
    public void load() {
        values = readEnvFile();
    }

    public List<Map<String, Object>> list() {
        Map<String, String> snapshot = values;
        return ALLOWED_KEYS.stream()
                .sorted()
                .map(key -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", key);
                    item.put("value", snapshot.getOrDefault(key, ""));
                    item.put("secret", SECRET_KEYS.contains(key));
                    item.put("restartRequired", RESTART_REQUIRED_KEYS.contains(key));
                    item.put("editable", true);
                    return item;
                })
                .toList();
    }

    public synchronized List<Map<String, Object>> update(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return list();
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            if (!ALLOWED_KEYS.contains(key)) {
                throw BusinessException.badRequest("不支持修改的配置项: " + key);
            }
            cleaned.put(key, value == null ? "" : value.trim());
        });
        Map<String, String> next = new LinkedHashMap<>(values);
        next.putAll(cleaned);
        writeEnvFile(next);
        values = next;
        return list();
    }

    public String get(String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        String value = get(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String value = get(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = get(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    public Path envPath() {
        return envPath;
    }

    private Map<String, String> readEnvFile() {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(envPath)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int index = trimmed.indexOf('=');
                String key = trimmed.substring(0, index).trim();
                if (ALLOWED_KEYS.contains(key)) {
                    result.put(key, unquote(trimmed.substring(index + 1).trim()));
                }
            }
            return result;
        } catch (IOException ex) {
            throw new BusinessException(500, "读取 .env 失败: " + ex.getMessage());
        }
    }

    private void writeEnvFile(Map<String, String> nextValues) {
        List<String> lines = new ArrayList<>();
        Set<String> written = new java.util.HashSet<>();
        if (Files.exists(envPath)) {
            try {
                for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("#") && trimmed.contains("=")) {
                        String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
                        if (ALLOWED_KEYS.contains(key) && nextValues.containsKey(key)) {
                            lines.add(key + "=" + quoteIfNeeded(nextValues.get(key)));
                            written.add(key);
                            continue;
                        }
                    }
                    lines.add(line);
                }
            } catch (IOException ex) {
                throw new BusinessException(500, "读取 .env 失败: " + ex.getMessage());
            }
        }
        for (String key : ALLOWED_KEYS.stream().sorted().toList()) {
            if (!written.contains(key) && nextValues.containsKey(key)) {
                lines.add(key + "=" + quoteIfNeeded(nextValues.get(key)));
            }
        }
        try {
            Files.createDirectories(envPath.getParent());
            Files.write(envPath, lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException(500, "写入 .env 失败: " + ex.getMessage());
        }
    }

    private Path resolveEnvPath(String configuredEnvPath) {
        if (configuredEnvPath != null && !configuredEnvPath.isBlank()) {
            return Paths.get(configuredEnvPath).toAbsolutePath().normalize();
        }
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (userDir.getFileName() != null && "backend".equals(userDir.getFileName().toString())) {
            Path projectEnv = userDir.resolve("../.env").normalize();
            if (Files.exists(projectEnv) || !Files.exists(userDir.resolve(".env"))) {
                return projectEnv;
            }
        }
        List<Path> candidates = List.of(userDir.resolve(".env"), userDir.resolve("../.env").normalize());
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private String quoteIfNeeded(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(" ") || safe.contains("#")) {
            return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return safe;
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }
}
