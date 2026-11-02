package cn.gulupai.server.identity.domain;

public record ProfileData(
        int weeklyHours,
        String availability,
        String collaborationGoal
) {
    public static ProfileData defaults() {
        return new ProfileData(0, "READY", "");
    }
}
