package com.rainexis.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 兼容性配置
 * 注册 ObjectMapper，自动发现并注册所有 Jackson 模块（如 JavaTimeModule）
 */
@Configuration
public class JacksonCompatConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
