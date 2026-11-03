package cn.gulupai.server.delivery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 份额是否被本人接受。只认本约定的 ACCEPT、产生本版的已生效提案确认，
 * 以及沿提案来源链追溯到的未变更份额。不读取 signed、提交、验收或贡献确认。
 */
public final class ShareAcceptance {

    private static final Comparator<Share> SHARE_ORDER = Comparator
            .comparing(Share::userId, Comparator.nullsLast(String::compareTo))
            .thenComparing(Share::responsibility, Comparator.nullsLast(String::compareTo))
            .thenComparing(Share::points, Comparator.nullsLast(String::compareTo));

    private ShareAcceptance() {
    }

    public record Share(String userId, String points, String responsibility) {
        public Share {
            points = canonicalPoints(points);
        }
    }

    public record Criterion(String id, String text) {
    }

    public record Unit(String id, String title, String points, String dueAt, List<Share> shares,
                       List<Criterion> criteria) {
        public Unit {
            shares = shares == null ? List.of() : List.copyOf(shares);
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            points = canonicalPoints(points);
        }
    }

    /**
     * @param sourceProposalId 产生该约定的已保存提案。人员变更、重建约定和首版都是 null。
     */
    public record Agreement(String id, int version, String status, String sourceProposalId, List<Unit> units,
                            Set<String> directAcceptUserIds) {
        public Agreement {
            units = units == null ? List.of() : List.copyOf(units);
            directAcceptUserIds = copyUsers(directAcceptUserIds);
        }
    }

    public record Proposal(String id, int baseVersion, String status, Set<String> changedUnitIds,
                           Set<String> confirmedUserIds) {
        public Proposal {
            changedUnitIds = changedUnitIds == null ? Set.of() : Set.copyOf(changedUnitIds);
            confirmedUserIds = copyUsers(confirmedUserIds);
        }
    }

    public static Catalog catalog(List<Agreement> agreements, List<Proposal> proposals) {
        return new Catalog(agreements == null ? List.of() : agreements, proposals == null ? List.of() : proposals);
    }

    public static final class Catalog {
        private final Map<String, Agreement> byId;
        private final Map<Integer, Agreement> byVersion;
        private final Set<Integer> duplicateVersions;
        private final Map<String, Proposal> proposals;

        private Catalog(List<Agreement> agreements, List<Proposal> proposals) {
            this.byId = new HashMap<>();
            this.byVersion = new HashMap<>();
            this.duplicateVersions = new HashSet<>();
            for (Agreement agreement : agreements) {
                if (agreement == null || agreement.id() == null) {
                    continue;
                }
                byId.putIfAbsent(agreement.id(), agreement);
                Agreement previous = byVersion.putIfAbsent(agreement.version(), agreement);
                if (previous != null && !previous.id().equals(agreement.id())) {
                    duplicateVersions.add(agreement.version());
                }
            }
            this.proposals = new HashMap<>();
            for (Proposal proposal : proposals) {
                if (proposal != null && proposal.id() != null) {
                    this.proposals.putIfAbsent(proposal.id(), proposal);
                }
            }
        }

        public boolean accepted(String agreementId, String unitId, String userId, String points, String responsibility) {
            Agreement agreement = byId.get(agreementId);
            if (agreement == null || unitId == null || userId == null || userId.isBlank()) {
                return false;
            }
            return accepted(agreement, unitId, new Share(userId, points, responsibility), new LinkedHashSet<>());
        }

        private boolean accepted(Agreement agreement, String unitId, Share share, Set<String> seen) {
            if (agreement == null || !seen.add(agreement.id())) {
                return false;
            }
            Unit unit = unit(agreement, unitId);
            if (unit == null || !containsShare(unit, share)) {
                return false;
            }
            if (agreement.directAcceptUserIds().contains(share.userId())) {
                return true;
            }
            if ("PENDING".equals(agreement.status()) || "CHANGES_REQUESTED".equals(agreement.status())) {
                return false;
            }
            Proposal proposal = agreement.sourceProposalId() == null ? null : proposals.get(agreement.sourceProposalId());
            if (proposal == null || !"APPLIED".equals(proposal.status())) {
                return false;
            }
            if (proposal.changedUnitIds().contains(unitId)) {
                return proposal.confirmedUserIds().contains(share.userId());
            }
            if (duplicateVersions.contains(proposal.baseVersion())) {
                return false;
            }
            Agreement base = byVersion.get(proposal.baseVersion());
            if (base == null || base.id().equals(agreement.id())) {
                return false;
            }
            Unit previous = unit(base, unitId);
            if (previous == null || !unchanged(unit, previous) || !containsShare(previous, share)) {
                return false;
            }
            return accepted(base, unitId, share, seen);
        }
    }

    private static Unit unit(Agreement agreement, String unitId) {
        for (Unit unit : agreement.units()) {
            if (unitId.equals(unit.id())) {
                return unit;
            }
        }
        return null;
    }

    private static boolean containsShare(Unit unit, Share share) {
        for (Share candidate : unit.shares()) {
            if (share.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提案没有列出的分项必须和前序快照一致，才算未受影响。
     * 旧快照缺截止时间时，不把缺失当成一次新的变更。
     */
    static boolean unchanged(Unit current, Unit previous) {
        if (current == null || previous == null || current.id() == null || !current.id().equals(previous.id())) {
            return false;
        }
        if (!java.util.Objects.equals(current.title(), previous.title()) || !samePoints(current.points(), previous.points())) {
            return false;
        }
        if (!sameDue(current.dueAt(), previous.dueAt()) || !sameCriteria(current.criteria(), previous.criteria())) {
            return false;
        }
        return sameShares(current.shares(), previous.shares());
    }

    private static boolean samePoints(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return left == null && right == null || (left != null && left.isBlank() && right != null && right.isBlank());
        }
        return left.equals(right);
    }

    private static boolean sameDue(String left, String right) {
        boolean leftPresent = left != null && !left.isBlank();
        boolean rightPresent = right != null && !right.isBlank();
        if (!leftPresent || !rightPresent) {
            return true;
        }
        try {
            return Instant.parse(left).equals(Instant.parse(right));
        } catch (RuntimeException ex) {
            return left.equals(right);
        }
    }

    private static boolean sameCriteria(List<Criterion> left, List<Criterion> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Criterion current = left.get(i);
            Criterion previous = right.get(i);
            if (!java.util.Objects.equals(current.text(), previous.text())) {
                return false;
            }
            if (current.id() != null && !current.id().isBlank()
                    && previous.id() != null && !previous.id().isBlank()
                    && !current.id().equals(previous.id())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameShares(List<Share> left, List<Share> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<Share> current = new ArrayList<>(left);
        List<Share> previous = new ArrayList<>(right);
        current.sort(SHARE_ORDER);
        previous.sort(SHARE_ORDER);
        return current.equals(previous);
    }

    private static Set<String> copyUsers(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<String> copied = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                copied.add(userId);
            }
        }
        return Set.copyOf(copied);
    }

    private static String canonicalPoints(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            return ShareLedger.text(ShareLedger.points(raw));
        } catch (RuntimeException ex) {
            return raw;
        }
    }
}
