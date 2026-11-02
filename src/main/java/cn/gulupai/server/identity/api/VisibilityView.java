package cn.gulupai.server.identity.api;

public record VisibilityView(
        String userId,
        String worksAudience,
        boolean teacherSummaryVisible,
        boolean cooperationSummaryVisible,
        int revision
) {
}
