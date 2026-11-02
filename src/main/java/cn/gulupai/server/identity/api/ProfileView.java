package cn.gulupai.server.identity.api;

import java.util.List;

public record ProfileView(
        String userId,
        String displayName,
        List<String> skills,
        int weeklyHours,
        String availability,
        String collaborationGoal,
        String intro,
        int revision
) {
    public ProfileView {
        skills = List.copyOf(skills);
    }
}
