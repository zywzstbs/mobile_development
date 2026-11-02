package cn.gulupai.server.dice.api;

import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class DiceRequests {

    private DiceRequests() {
    }

    public record CreateSessionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedTaskRevision,
            @NotEmpty @Size(min = 2, max = 20) List<@NotBlank String> participantIds,
            @NotBlank @Pattern(regexp = "MAX|MIN|TARGET") String rule,
            @Min(1) @Max(6) Integer targetValue,
            @NotNull @Min(3) @Max(3) Integer maxRounds,
            Instant inviteEndsAt,
            Instant rollEndsAt
    ) {
    }

    public record ParticipationRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision
    ) {
    }

    public record RollRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotNull @Min(1) @Max(3) Integer roundNo
    ) {
    }

    public record TerminationRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Utf16Length(max = 500) String reason
    ) {
    }

    public record OutcomeDecisionRequest(
            @NotNull @PositiveOrZero @Max(999_999_999) Integer expectedRevision,
            @NotBlank @Pattern(regexp = "ACCEPT|DECLINE") String decision
    ) {
    }
}
