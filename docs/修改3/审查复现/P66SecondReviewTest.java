package cn.gulupai.server.platform;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.*;
import cn.gulupai.server.common.security.AuthenticatedUser;
import cn.gulupai.server.identity.domain.Role;
import cn.gulupai.server.platform.api.PlatformRequests;
import cn.gulupai.server.platform.application.*;
import cn.gulupai.server.platform.domain.ManagementAuditEvent;
import cn.gulupai.server.platform.persistence.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Historical defect probes: passing means the defect was reproduced, not acceptance. */
class P66SecondReviewTest {
    @Test void firstGrantFailsBeforeBusinessBecauseReplayCheckRequiresExistingOperation() {
        reproduce(false);
    }
    @Test void firstRevocationFailsBeforeBusinessBecauseReplayCheckRequiresExistingOperation() {
        reproduce(true);
    }
    private void reproduce(boolean revoke) {
        var principal = new AuthenticatedUser("actor", "session", "actor", "school", "Actor",
                "VERIFIED", List.of(Role.SUPER_ADMIN), false);
        var auth = mock(PlatformAuthorizationService.class);
        var repo = mock(PlatformRepository.class);
        var reauth = mock(AdminReauthenticationService.class);
        var access = new PlatformAuthorizationService.Access(principal,
                new PlatformRepository.ActiveGrant("grant", "actor", 1, true, List.of(), List.of()));
        when(auth.require(eq(principal), any())).thenReturn(access);
        when(repo.findUser("target")).thenReturn(Optional.of(new PlatformRepository.ManagedUser(
                "target", "school", "target", "Target", "VERIFIED", false, "ACTIVE", "VERIFIED_ACTIVE", 1, true)));
        when(repo.findOperation("actor", "op-new")).thenReturn(Optional.empty());
        var store = mock(IdempotencyStore.class);
        when(store.tryLock(anyLong())).thenReturn(true);
        var canonical = mock(CanonicalJson.class);
        when(canonical.hash(any())).thenReturn("hash");
        var idem = new IdempotencyService(store, canonical, mock(IdempotencyCipher.class),
                new ObjectMapper(), Clock.systemUTC(), new IdempotencyProperties());
        var service = new PlatformWriteService(auth, repo, reauth, idem, null, null, null, null,
                null, null, new ObjectMapper(), Clock.systemUTC());
        assertThatThrownBy(() -> {
            if (revoke) service.revokeSuperAdmin(principal, "target",
                    new PlatformRequests.RevocationRequest("review", 1, "reauth"), "op-new", "key-new", "/revoke");
            else service.grantSuperAdmin(principal,
                    new PlatformRequests.SuperAdminGrantRequest("target", "review", "reauth"),
                    "op-new", "key-new", "/grant");
        }).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiCode.RESOURCE_NOT_FOUND));
        verify(repo, never()).lockAdminConstraint();
        verify(reauth, never()).verifyUnused(any(), any(), any(), any());
    }
    @Test void httpReviewActionsAreMissingFromAuditWhitelist() {
        assertThat(ManagementAuditEvent.find("TEACHER_APPLICATION_APPROVED", "REGISTRATION")).isNull();
        assertThat(ManagementAuditEvent.find("TEACHER_APPLICATION_REJECTED", "REGISTRATION")).isNull();
        assertThat(ManagementAuditEvent.find("COURSE_CLAIM_APPROVED", "TEACHER_CLAIM")).isNull();
        assertThat(ManagementAuditEvent.find("COURSE_CLAIM_REJECTED", "TEACHER_CLAIM")).isNull();
    }
}
