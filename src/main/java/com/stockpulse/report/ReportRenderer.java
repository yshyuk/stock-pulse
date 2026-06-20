package com.stockpulse.report;

import com.stockpulse.domain.ReportFormat;
import com.stockpulse.domain.ReportModel;

/**
 * ★ Core abstraction for turning a {@link ReportModel} into a concrete output format.
 *
 * <p>{@link com.stockpulse.report.render.MarkdownRenderer} is the only implementation today.
 * A {@code JsonRenderer} can be added later (see {@link ReportFormat#JSON}) without changing
 * the pipeline — {@link ReportService} selects a renderer by {@link #format()}.
 */
public interface ReportRenderer {

    /** Which output format this renderer produces. */
    ReportFormat format();

    /** Render the model into the target format's text body. */
    String render(ReportModel model);
}
