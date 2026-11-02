package cn.gulupai.server.community.application;

import cn.gulupai.server.community.persistence.ChatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 批量清理过期聊天同步 token。只在独立调度中按到期时间取有限批次，
 * 不在用户请求内做全表删除，也不影响在线用户正在重试的未过期 token。
 */
@Component
public class ChatSyncCursorCleanup {

    private static final int BATCH = 500;

    private final ChatRepository repo;
    private final Clock clock;

    public ChatSyncCursorCleanup(ChatRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${app.chat.sync-cursor-cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${app.chat.sync-cursor-cleanup-delay-ms:3600000}")
    public void cleanup() {
        while (repo.deleteExpiredSyncCursorsBatch(clock.instant(), BATCH) == BATCH) {
            // 继续下一批，直到本次没有更多过期记录。
        }
    }
}
