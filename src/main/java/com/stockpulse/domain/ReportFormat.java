package com.stockpulse.domain;

/**
 * Output format of a rendered report. Used to pick a {@link com.stockpulse.report.ReportRenderer}.
 */
public enum ReportFormat {
    MARKDOWN(".md"),
    /** Reserved: a JsonRenderer can be added later without touching the pipeline. */
    JSON(".json");

    private final String fileExtension;

    ReportFormat(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public String fileExtension() {
        return fileExtension;
    }
}
