package com.stockpulse.analysis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.stockpulse.config.StockPulseProperties;
import com.stockpulse.domain.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Second-stage analyzer backed by the Claude API (api.anthropic.com).
 *
 * <p>Active only when {@code stockpulse.analysis.enabled=true}; it is {@code @Primary} so it
 * replaces {@link NoOpReportAnalyzer} in the pipeline when enabled. It takes the rendered
 * report (objective metrics only) and asks Claude to interpret it — the judgement step that
 * is otherwise done by a human pasting the report into Claude.
 *
 * <p>Uses the official Anthropic Java SDK with {@code claude-opus-4-8} and adaptive thinking.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(prefix = "stockpulse.analysis", name = "enabled", havingValue = "true")
public class AnthropicReportAnalyzer implements ReportAnalyzer {

    private static final long MAX_TOKENS = 8000L;
    private static final String SYSTEM_PROMPT = """
            당신은 한국 주식시장 분석가입니다. 입력으로 객관적 지표만 담긴 일일 리포트를 받습니다.
            등락률·거래량 변화율 등 수치를 바탕으로 종목별 특이사항과 해석을 제시하세요.
            확정적 매수/매도 단정은 피하고, 근거와 함께 관찰 포인트를 정리하세요. 한국어로 답하세요.""";

    private final StockPulseProperties properties;
    /** Built lazily so a missing API key doesn't fail context startup. */
    private volatile AnthropicClient client;

    public AnthropicReportAnalyzer(StockPulseProperties properties) {
        this.properties = properties;
    }

    @Override
    public AnalysisResult analyze(Report report) {
        StockPulseProperties.Analysis cfg = properties.getAnalysis();
        if (!StringUtils.hasText(cfg.getAnthropicApiKey())) {
            log.warn("[analysis] enabled but ANTHROPIC_API_KEY missing — skipping Claude analysis");
            return AnalysisResult.none();
        }
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(cfg.getModel())
                    .maxTokens(MAX_TOKENS)
                    .system(SYSTEM_PROMPT)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .addUserMessage(report.getContent())
                    .build();

            Message response = client().messages().create(params);
            String text = extractText(response);
            log.info("[analysis] Claude analysis produced {} chars", text.length());
            return AnalysisResult.builder()
                    .performed(true)
                    .analysis(text)
                    .build();
        } catch (Exception e) {
            // Analysis is an enrichment step — don't fail the whole batch on its account.
            log.error("[analysis] Claude API call failed: {}", e.getMessage(), e);
            return AnalysisResult.none();
        }
    }

    private String extractText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString();
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = AnthropicOkHttpClient.builder()
                            .apiKey(properties.getAnalysis().getAnthropicApiKey())
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }
}
