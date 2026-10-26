package com.rainexis.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainexis.backend.service.business.RubricParserService;
import com.rainexis.backend.service.business.TeacherClassService;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

class RubricStructureTests {
    @Test void originalExcelColumnsSupportNumberedBandsAndKeepAdditiveKnowledgePoints() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var buffer = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            String[][] rows = {
                    {"评分维度", "权重", "子项", "子项分值", "评分标准"},
                    {"知识点运用", "60", "循环结构1", "2", "循环"},
                    {"知识点运用", "60", "循环结构2", "2", "break"},
                    {"AI过程性应用", "20", "AI过程性应用1", "18~20", "完整"},
                    {"AI过程性应用", "20", "AI过程性应用2", "15~17", "基本完整"},
                    {"AI过程性应用", "20", "AI过程性应用3", "0~14", "不足"},
                    {"新技术应用", "10", "新技术应用1", "9~10", "很好"},
                    {"新技术应用", "10", "新技术应用2", "1~8", "一般"},
                    {"新技术应用", "10", "新技术应用3", "0", "未使用"}
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]);
            }
            workbook.write(buffer); bytes = buffer.toByteArray();
        }
        var mapper = new ObjectMapper();
        var parsed = new RubricParserService(mapper).parse(new MockMultipartFile("file", "原格式.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        var dimensions = mapper.readTree(parsed.rubricJson()).path("dimensions");
        assertThat(dimensions.size()).isEqualTo(4);
        assertThat(dimensions.get(2).path("scoring_bands").size()).isEqualTo(3);
        assertThat(dimensions.get(2).path("max_score").asInt()).isEqualTo(20);
        assertThat(dimensions.get(3).path("scoring_bands").size()).isEqualTo(3);
        assertThat(dimensions.get(3).path("max_score").asInt()).isEqualTo(10);
    }

    @Test void teacherClassRangesAndListsAreExactAndDoNotGrantSimilarPrefixes() {
        var service = new TeacherClassService(null);
        assertThat(service.parse("软件26201-26203、软件26206，软件26208"))
                .containsExactly("软件26201", "软件26202", "软件26203", "软件26206", "软件26208");
        assertThat(service.parse("软件技术26201-26202")).doesNotContain("软件26201");
        assertThat(service.parse("A001-A003\nB02")).containsExactly("A001", "A002", "A003", "B02");
        assertThat(service.parse(" ")).isEmpty();
    }
}
