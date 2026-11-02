package cn.gulupai.server.team.persistence;

public record ProjectCodeRow(
        String id,
        String projectId,
        String status,
        String codeCiphertext,
        int revision
) {
}
