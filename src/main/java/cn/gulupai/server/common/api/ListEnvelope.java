package cn.gulupai.server.common.api;

import java.util.List;
import java.util.Objects;

/**
 * 列表统一投影：{@code items, nextCursor, hasMore, total}。
 *
 * <p>完整列表 {@code hasMore=false,nextCursor=null}，total 必须是当前授权范围内
 * 的完整总数；分页列表由后续阶段按游标快照构造。
 */
public record ListEnvelope<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        int total
) {

    public ListEnvelope {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (total < items.size()) {
            throw new IllegalArgumentException("total 不能小于当前页条数");
        }
    }

    public static <T> ListEnvelope<T> full(List<T> items) {
        return new ListEnvelope<>(items, null, false, items.size());
    }

    public static <T> ListEnvelope<T> page(List<T> items, String nextCursor, boolean hasMore, int total) {
        return new ListEnvelope<>(items, nextCursor, hasMore, total);
    }
}
