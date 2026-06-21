package com.stockpulse.api;

import com.stockpulse.domain.Report;
import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportRepository reportRepository;

    private Report sample() {
        return Report.builder()
                .id(1L)
                .reportDate(LocalDate.of(2026, 6, 21))
                .format(ReportFormat.MARKDOWN)
                .content("# report")
                .generatedAt(Instant.parse("2026-06-21T06:00:00Z"))
                .build();
    }

    @Test
    void returnsReportByDate() throws Exception {
        when(reportRepository.findFirstByReportDateOrderByGeneratedAtDesc(LocalDate.of(2026, 6, 21)))
                .thenReturn(Optional.of(sample()));

        mockMvc.perform(get("/api/reports/2026-06-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportDate").value("2026-06-21"))
                .andExpect(jsonPath("$.format").value("MARKDOWN"))
                .andExpect(jsonPath("$.content").value("# report"));
    }

    @Test
    void returns404WhenMissing() throws Exception {
        when(reportRepository.findFirstByReportDateOrderByGeneratedAtDesc(any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/reports/2026-06-21"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsLatest() throws Exception {
        when(reportRepository.findFirstByOrderByReportDateDescGeneratedAtDesc())
                .thenReturn(Optional.of(sample()));

        mockMvc.perform(get("/api/reports/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }
}
