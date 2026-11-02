package cn.gulupai.server.identity.domain;

public record VisibilityData(
        String worksAudience,
        boolean teacherSummaryVisible,
        boolean cooperationSummaryVisible
) {
    public static VisibilityData defaults() {
        return new VisibilityData("PRIVATE", false, false);
    }
}
