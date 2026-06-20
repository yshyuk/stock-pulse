package com.stockpulse.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * A single raw item collected from a {@link com.stockpulse.collector.DataSource}.
 *
 * <p>This is an immutable value object (not yet persisted). The {@code payload} is kept
 * as a loose key/value map on purpose so heterogeneous sources (DART disclosures,
 * Naver finance quotes, news articles, ...) can all flow through the same pipeline
 * without a rigid schema. The {@code processor} stage is responsible for turning this
 * into structured, comparable metrics.
 */
@Value
@Builder
public class RawData {

    /** Originating source, e.g. "dummy", "dart", "naver-finance". */
    String sourceName;

    /** Stock ticker/code this item refers to (nullable for source-level items like news). */
    String symbol;

    /** Human-readable stock name, when known. */
    String name;

    /** Loose payload as collected. Interpreted by the processor stage. */
    Map<String, Object> payload;

    /** When this item was fetched. */
    Instant fetchedAt;
}
