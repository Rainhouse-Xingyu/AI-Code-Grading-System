package com.rainexis.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.service.business.ZipStructureService;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ZipStructureServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void analyzesZipWhoseChineseEntryNamesUseGbk() throws Exception {
        Path zip = tempDir.resolve("homework.zip");
        try (OutputStream output = Files.newOutputStream(zip);
             ZipOutputStream archive = new ZipOutputStream(output, Charset.forName("GBK"))) {
            archive.putNextEntry(new ZipEntry("中文作业/src/Main.java"));
            archive.write("public class Main { public static void main(String[] args) {} }"
                    .getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        ZipStructureService.StructureResult result = new ZipStructureService(new ObjectMapper())
                .analyze(zip, "java");
        JsonNode structure = new ObjectMapper().readTree(result.structureJson());

        assertThat(result.fileCount()).isEqualTo(1);
        assertThat(structure.path("file_tree").get(0).path("path").asText())
                .isEqualTo("中文作业/src/Main.java");
    }
}
