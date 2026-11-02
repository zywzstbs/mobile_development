package cn.gulupai.server.delivery;

import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.task.api.TaskRequests;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareLedgerTest {

    @Test
    void conservedSharesMatchUnitPoints() {
        List<ShareLedger.Share> shares = ShareLedger.normalizeShares(new BigDecimal("10.00"), List.of(
                new TaskRequests.ShareInput("u1", "6", "页面"),
                new TaskRequests.ShareInput("u2", "4.00", "接口")));
        assertThat(shares).extracting(ShareLedger.Share::userId).containsExactly("u1", "u2");
        assertThat(ShareLedger.text(shares.get(0).points())).isEqualTo("6.00");
    }

    @Test
    void rejectsUnbalancedOrRepeatedShares() {
        assertThatThrownBy(() -> ShareLedger.normalizeShares(new BigDecimal("10.00"), List.of(
                new TaskRequests.ShareInput("u1", "6.00", "页面"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ShareLedger.normalizeShares(new BigDecimal("10.00"), List.of(
                new TaskRequests.ShareInput("u1", "5.00", "页面"),
                new TaskRequests.ShareInput("u1", "5.00", "接口"))))
                .isInstanceOf(ApiException.class);
    }
}
