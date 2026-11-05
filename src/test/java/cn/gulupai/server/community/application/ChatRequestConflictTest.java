package cn.gulupai.server.community.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestConflictTest {

    @Test
    void onlyClientMessageUniqueKeyIsReuse() {
        assertThat(ChatService.isClientMessageUniqueViolation(new DuplicateKeyException(
                "duplicate", new RuntimeException(
                "duplicate key value violates unique constraint \"uq_chat_client_message\""))))
                .isTrue();
        assertThat(ChatService.isClientMessageUniqueViolation(new DuplicateKeyException(
                "duplicate", new RuntimeException(
                "duplicate key value violates unique constraint \"uq_chat_sequence\""))))
                .isFalse();
        assertThat(ChatService.isClientMessageUniqueViolation(new DataIntegrityViolationException(
                "check", new RuntimeException(
                "new row violates check constraint \"ck_chat_request_hash\""))))
                .isFalse();
    }
}
