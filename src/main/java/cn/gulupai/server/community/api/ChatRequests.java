package cn.gulupai.server.community.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ChatRequests {
    private ChatRequests() {
    }

    public record RefInput(@NotBlank String type, @NotBlank String id,
                           @NotNull @PositiveOrZero Integer version) {
    }

    public record Send(
            @NotBlank @Size(max = 128) String clientMessageId,
            @Utf16Length(max = 1000) String content,
            @Size(max = 5) List<@Valid RefInput> refs,
            @Size(max = 20) List<@NotBlank String> mentions
    ) {
    }

    public record Withdraw(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision
    ) {
    }

    public record CursorPut(
            @NotNull @PositiveOrZero Long sequence
    ) {
    }
}
