package cn.gulupai.server.dice.api;

import java.time.Instant;
import java.util.List;

public final class DiceViews {

    private DiceViews() {
    }

    public record Participant(String userId, String decision, boolean activeInRound,
                              boolean hasRolled, Integer myRollValue) {
    }

    public record Roll(String id, String userId, int value, Instant createdAt) {
    }

    public record Round(int roundNo, List<String> candidateIds, List<Roll> rolls,
                        String winnerId) {
        public Round {
            candidateIds = List.copyOf(candidateIds);
            rolls = List.copyOf(rolls);
        }
    }

    public record Session(String id, String projectId, String taskId, int taskRevision,
                          int revision, String rule, Integer targetValue, int maxRounds,
                          int roundNo, String status, Instant inviteEndsAt, Instant rollEndsAt,
                          List<Participant> participants, List<Round> rounds,
                          String proposedOwnerId, String outcomeDecision, String claimStatus,
                          String terminationReason, Instant createdAt, Integer myRollValue) {
        public Session {
            participants = List.copyOf(participants);
            rounds = List.copyOf(rounds);
        }
    }

    public record Outcome(String sessionId, int revision, String status, String proposedOwnerId,
                          String outcomeDecision, String claimStatus, String taskId,
                          int expectedTaskRevision) {
    }
}
