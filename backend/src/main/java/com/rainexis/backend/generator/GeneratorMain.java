package com.rainexis.backend.generator;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;

import java.util.Collections;

/**
 * MyBatis-Plus 代码生成器
 * 根据数据库表自动生成 Entity、Mapper、Service、Controller 等骨架代码
 * 仅在项目初始化阶段使用，不应在生产中运行
 */
public class GeneratorMain {

    public static void main(String[] args) {

        FastAutoGenerator.create(
                        "jdbc:mysql://localhost:3306/neusoft_ai_code_grading_ststem?useSSL=false&serverTimezone=UTC",
                        "root",
                        "MYxingyu20022453.."
                )
                .globalConfig(builder -> {
                    builder.author("xingyu")
                            .outputDir(System.getProperty("user.dir") + "/backend/src/main/java")
                            .enableSwagger() // 可选
                            .commentDate("yyyy-MM-dd");
                })
                .packageConfig(builder -> {
                    builder.parent("com.rainexis.backend")
                            .entity("entity")
                            .mapper("mapper")
                            .service("service")
                            .serviceImpl("service.impl")
                            .controller("controller")
                            .pathInfo(Collections.singletonMap(
                                    OutputFile.xml,
                                    System.getProperty("user.dir") + "/src/main/resources/mapper"
                            ));
                })
                .strategyConfig(builder -> {

                    builder.addInclude(
                                    "t_user",
                                    "t_assignment",
                                    "t_submission",
                                    "t_ai_task",
                                    "t_ai_report",
                                    "t_rubric",
                                    "t_teacher_review",
                                    "t_grade_publish",
                                    "t_file"
                            )
                            .entityBuilder()
                            .enableLombok()
                            .enableTableFieldAnnotation();

                })
                .execute();
    }
}