package cn.gulupai.server.identity.api;

import java.util.List;

public record RegistrationOptions(boolean enabled, boolean teacherApplicationEnabled, List<SchoolOption> schools) {
    public RegistrationOptions {
        schools = List.copyOf(schools);
    }

    public record SchoolOption(String id, String name) {
    }
}
