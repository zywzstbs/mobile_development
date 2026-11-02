package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcceptanceMatchTest {

    @Test
    void partialPassLocksOnlyFullyMetUnits() {
        AcceptanceMatch.Result result = AcceptanceMatch.match(List.of(
                new AcceptanceMatch.Required("a", "c1"),
                new AcceptanceMatch.Required("b", "c2"),
                new AcceptanceMatch.Required("b", "c3")), List.of(
                new AcceptanceMatch.Check("a", "c1", true),
                new AcceptanceMatch.Check("b", "c2", true),
                new AcceptanceMatch.Check("b", "c3", false)), List.of("还不能退出"));
        assertThat(result.decision()).isEqualTo("PARTIAL");
        assertThat(result.passedUnitIds()).containsExactly("a");
    }

    @Test
    void rejectsSkippedOrMismatchedReasons() {
        List<AcceptanceMatch.Required> required = List.of(new AcceptanceMatch.Required(null, "c1"));
        assertThatThrownBy(() -> AcceptanceMatch.match(required, List.of(), List.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AcceptanceMatch.match(required,
                List.of(new AcceptanceMatch.Check(null, "c1", false)), List.of()))
                .isInstanceOf(ApiException.class);
    }
}
