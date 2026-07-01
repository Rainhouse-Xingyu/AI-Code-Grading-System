package com.rainexis.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 应用程序入口
 * 扫描 com.rainexis.backend.mapper 包下的 MyBatis Mapper 接口
 */
@SpringBootApplication
@MapperScan("com.rainexis.backend.mapper")
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
