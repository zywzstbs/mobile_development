package cn.gulupai.server.team.persistence;

public record RoleRow(String id, String projectId, String roleName, String duty,
                      int total, int position) {
}
