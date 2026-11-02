package cn.gulupai.server.course.api;

import java.util.List;

public record CourseNameView(String id, String fullName, List<String> aliases) {
    public CourseNameView {
        aliases = List.copyOf(aliases);
    }
}
