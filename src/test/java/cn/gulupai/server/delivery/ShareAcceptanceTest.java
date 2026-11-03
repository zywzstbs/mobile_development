package cn.gulupai.server.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShareAcceptanceTest {

    @Test
    void directAcceptCoversThatAgreementOnly() {
        ShareAcceptance.Unit kept = unit("u2", "列表", share("b", "10.00", "实现列表"));
        ShareAcceptance.Agreement older = agreement("v1", 1, "SUPERSEDED", null, Set.of("b"), kept);
        ShareAcceptance.Agreement newer = agreement("v2", 2, "ACTIVE", null, Set.of(), kept);
        ShareAcceptance.Catalog catalog = ShareAcceptance.catalog(List.of(older, newer), List.of());

        assertThat(catalog.accepted("v1", "u2", "b", "10.00", "实现列表")).isTrue();
        assertThat(catalog.accepted("v2", "u2", "b", "10.00", "实现列表")).isFalse();
        assertThat(catalog.accepted("v1", "u2", "a", "10.00", "实现列表")).isFalse();
    }

    @Test
    void unchangedShareInheritsAcrossProposals() {
        ShareAcceptance.Unit first = unit("u1", "登录", share("a", "10.00", "实现登录"));
        ShareAcceptance.Unit second = unit("u2", "列表", share("b", "4", "实现列表"));
        ShareAcceptance.Agreement v1 = agreement("v1", 1, "SUPERSEDED", null, Set.of("a", "b"), first, second);
        ShareAcceptance.Agreement v2 = agreement("v2", 2, "SUPERSEDED", "p2", Set.of(),
                unit("u1", "登录改名", share("a", "10.00", "实现登录")), second);
        ShareAcceptance.Agreement v3 = agreement("v3", 3, "ACTIVE", "p3", Set.of(),
                unit("u1", "登录再改", share("a", "10.00", "实现登录")),
                unit("u2", "列表", share("b", "4.00", "实现列表")));
        ShareAcceptance.Catalog catalog = ShareAcceptance.catalog(List.of(v1, v2, v3), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of("u1"), Set.of("a")),
                new ShareAcceptance.Proposal("p3", 2, "APPLIED", Set.of("u1"), Set.of("a"))));

        assertThat(catalog.accepted("v3", "u2", "b", "4.00", "实现列表")).isTrue();
        assertThat(catalog.accepted("v2", "u2", "b", "4.00", "实现列表")).isTrue();
        assertThat(catalog.accepted("v3", "u1", "a", "10.00", "实现登录")).isTrue();
        assertThat(catalog.accepted("v1", "u1", "a", "10.00", "实现登录")).isTrue();
    }

    @Test
    void changedShareNeedsTheApplicableConfirmation() {
        ShareAcceptance.Unit original = unit("u2", "列表", share("b", "10.00", "实现列表"));
        ShareAcceptance.Unit changed = unit("u2", "列表", share("b", "10.00", "改过的列表"));
        ShareAcceptance.Agreement v1 = agreement("v1", 1, "SUPERSEDED", null, Set.of("b"), original);
        ShareAcceptance.Agreement v2 = agreement("v2", 2, "ACTIVE", "p2", Set.of(), changed);
        ShareAcceptance.Proposal unconfirmed = new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of("u2"), Set.of("a"));
        ShareAcceptance.Catalog withoutConfirmation = ShareAcceptance.catalog(List.of(v1, v2), List.of(unconfirmed));
        ShareAcceptance.Catalog confirmed = ShareAcceptance.catalog(List.of(v1, v2), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of("u2"), Set.of("b"))));

        assertThat(withoutConfirmation.accepted("v2", "u2", "b", "10.00", "改过的列表")).isFalse();
        assertThat(confirmed.accepted("v2", "u2", "b", "10.00", "改过的列表")).isTrue();
        assertThat(confirmed.accepted("v1", "u2", "b", "10.00", "实现列表")).isTrue();
        assertThat(withoutConfirmation.accepted("v2", "u2", "c", "10.00", "新份额")).isFalse();
    }

    @Test
    void oneConfirmationDoesNotCoverAnUnchangedUnitWithoutPriorAcceptance() {
        ShareAcceptance.Unit changed = unit("u1", "登录改名", share("a", "6.00", "实现登录"));
        ShareAcceptance.Unit untouched = unit("u2", "列表", share("a", "4.00", "接口"), share("b", "6.00", "实现列表"));
        ShareAcceptance.Agreement v1 = agreement("v1", 1, "SUPERSEDED", null, Set.of(), 
                unit("u1", "登录", share("a", "6.00", "实现登录")), untouched);
        ShareAcceptance.Agreement v2 = agreement("v2", 2, "ACTIVE", "p2", Set.of(), changed, untouched);
        ShareAcceptance.Catalog catalog = ShareAcceptance.catalog(List.of(v1, v2), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of("u1"), Set.of("a"))));

        assertThat(catalog.accepted("v2", "u1", "a", "6.00", "实现登录")).isTrue();
        assertThat(catalog.accepted("v2", "u2", "a", "4.00", "接口")).isFalse();
        assertThat(catalog.accepted("v2", "u2", "b", "6.00", "实现列表")).isFalse();
    }

    @Test
    void pendingAgreementDoesNotBorrowAnotherVersion() {
        ShareAcceptance.Unit unit = unit("u2", "列表", share("b", "10.00", "实现列表"));
        ShareAcceptance.Agreement active = agreement("v1", 1, "ACTIVE", null, Set.of("b"), unit);
        ShareAcceptance.Agreement pending = agreement("v2", 2, "PENDING", "p2", Set.of(), unit);
        ShareAcceptance.Agreement requested = agreement("v3", 3, "CHANGES_REQUESTED", null, Set.of("b"), unit);
        ShareAcceptance.Catalog catalog = ShareAcceptance.catalog(List.of(active, pending, requested), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of(), Set.of("b"))));

        assertThat(catalog.accepted("v2", "u2", "b", "10.00", "实现列表")).isFalse();
        assertThat(catalog.accepted("v3", "u2", "b", "10.00", "实现列表")).isTrue();
        assertThat(catalog.accepted("v1", "u2", "b", "10.00", "实现列表")).isTrue();
    }

    @Test
    void brokenOrDivergedSourceDoesNotInherit() {
        ShareAcceptance.Unit original = unit("u2", "列表", share("b", "10.00", "实现列表"));
        ShareAcceptance.Unit diverged = unit("u2", "列表改了", share("b", "10.00", "实现列表"));
        ShareAcceptance.Agreement v1 = agreement("v1", 1, "SUPERSEDED", "loop", Set.of("b"), original);
        ShareAcceptance.Agreement v2 = agreement("v2", 2, "ACTIVE", "p2", Set.of(), diverged);
        ShareAcceptance.Catalog divergedCatalog = ShareAcceptance.catalog(List.of(v1, v2), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of(), Set.of()),
                new ShareAcceptance.Proposal("loop", 2, "APPLIED", Set.of(), Set.of("b"))));
        ShareAcceptance.Catalog missingBase = ShareAcceptance.catalog(List.of(v2), List.of(
                new ShareAcceptance.Proposal("p2", 9, "APPLIED", Set.of(), Set.of("b"))));
        ShareAcceptance.Catalog notApplied = ShareAcceptance.catalog(List.of(
                agreement("v1", 1, "ACTIVE", null, Set.of("b"), original),
                agreement("v2", 2, "ACTIVE", "p2", Set.of(), original)), List.of(
                new ShareAcceptance.Proposal("p2", 1, "WITHDRAWN", Set.of(), Set.of("b"))));

        ShareAcceptance.Unit stable = unit("u9", "稳定", share("b", "10.00", "保持"));
        ShareAcceptance.Catalog cycle = ShareAcceptance.catalog(List.of(
                agreement("left", 1, "ACTIVE", "to-right", Set.of(), stable),
                agreement("right", 2, "ACTIVE", "to-left", Set.of(), stable)), List.of(
                new ShareAcceptance.Proposal("to-right", 2, "APPLIED", Set.of(), Set.of()),
                new ShareAcceptance.Proposal("to-left", 1, "APPLIED", Set.of(), Set.of())));

        assertThat(divergedCatalog.accepted("v2", "u2", "b", "10.00", "实现列表")).isFalse();
        assertThat(divergedCatalog.accepted("v1", "u2", "b", "10.00", "实现列表")).isTrue();
        assertThat(missingBase.accepted("v2", "u2", "b", "10.00", "实现列表")).isFalse();
        assertThat(notApplied.accepted("v2", "u2", "b", "10.00", "实现列表")).isFalse();
        assertThat(cycle.accepted("right", "u9", "b", "10.00", "保持")).isFalse();
    }

    @Test
    void oneProposalCoversOnlyTheUnitsItChanged() {
        ShareAcceptance.Unit first = unit("u1", "登录改名", share("a", "10.00", "实现登录"));
        ShareAcceptance.Unit second = unit("u2", "列表改名", share("a", "10.00", "实现列表"));
        ShareAcceptance.Unit third = unit("u3", "设置", share("a", "10.00", "保持"));
        ShareAcceptance.Agreement v1 = agreement("v1", 1, "SUPERSEDED", null, Set.of(),
                unit("u1", "登录", share("a", "10.00", "实现登录")),
                unit("u2", "列表", share("a", "10.00", "实现列表")), third);
        ShareAcceptance.Agreement v2 = agreement("v2", 2, "ACTIVE", "p2", Set.of(), first, second, third);
        ShareAcceptance.Catalog catalog = ShareAcceptance.catalog(List.of(v1, v2), List.of(
                new ShareAcceptance.Proposal("p2", 1, "APPLIED", Set.of("u1", "u2"), Set.of("a"))));

        assertThat(catalog.accepted("v2", "u1", "a", "10.00", "实现登录")).isTrue();
        assertThat(catalog.accepted("v2", "u2", "a", "10.00", "实现列表")).isTrue();
        assertThat(catalog.accepted("v2", "u3", "a", "10.00", "保持")).isFalse();
    }

    private static ShareAcceptance.Share share(String userId, String points, String responsibility) {
        return new ShareAcceptance.Share(userId, points, responsibility);
    }

    private static ShareAcceptance.Unit unit(String id, String title, ShareAcceptance.Share... shares) {
        return new ShareAcceptance.Unit(id, title, "10.00", "2026-11-06T15:59:59Z",
                List.of(shares), List.of(new ShareAcceptance.Criterion("c-" + id, title + "可以演示")));
    }

    private static ShareAcceptance.Agreement agreement(String id, int version, String status, String proposalId,
                                                      Set<String> acceptedBy, ShareAcceptance.Unit... units) {
        return new ShareAcceptance.Agreement(id, version, status, proposalId, List.of(units), acceptedBy);
    }
}
