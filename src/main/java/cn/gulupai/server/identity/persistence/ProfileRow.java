package cn.gulupai.server.identity.persistence;

public record ProfileRow(
        String userId,
        String intro,
        String profileJson,
        String visibilityJson,
        int revision
) {
}
