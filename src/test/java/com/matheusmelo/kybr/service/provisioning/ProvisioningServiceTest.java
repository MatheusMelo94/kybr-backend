package com.matheusmelo.kybr.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.model.link.AuditEvent;
import com.matheusmelo.kybr.model.link.TokenSlot;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.AuditEventRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.RuntimeFlagsService;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.MongoTransactionManager;

/** Every provisioning write is one transaction, re-run on a transient transaction error. */
class ProvisioningServiceTest {

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final AuditEventRepository audits = mock(AuditEventRepository.class);
    private final RuntimeFlagsService flags = mock(RuntimeFlagsService.class);
    private final SlugAssigner slugs = mock(SlugAssigner.class);
    private final ProvisioningService service = new ProvisioningService(workspaces, audits, flags, slugs,
            mock(MongoTransactionManager.class), false, Clock.systemUTC());
    private final AtomicInteger auditCalls = new AtomicInteger();

    @Test
    void creatingAWorkspaceIsRetriedAfterATransientConflict() {
        when(slugs.assign(anyString())).thenReturn("loja-exemplo-abcdef");
        conflictOnFirstAudit();

        service.create(input(), TokenSlot.CURRENT);

        verify(workspaces, times(2)).insert(any(Workspace.class));
        assertThat(auditCalls).hasValue(2);
    }

    @Test
    void theLinkSwitchIsRetriedAfterATransientConflict() {
        Workspace workspace = TestWorkspaces.builder().build();
        when(workspaces.findBySlug(workspace.slug())).thenReturn(Optional.of(workspace));
        conflictOnFirstAudit();

        service.setLinkAtivo(workspace.slug(), false, TokenSlot.CURRENT);

        verify(workspaces, times(2)).save(any(Workspace.class));
        assertThat(auditCalls).hasValue(2);
    }

    @Test
    void theWorkspaceAutofillSwitchIsRetriedAfterATransientConflict() {
        Workspace workspace = TestWorkspaces.builder().build();
        when(workspaces.findBySlug(workspace.slug())).thenReturn(Optional.of(workspace));
        conflictOnFirstAudit();

        service.setAutofillAtivo(workspace.slug(), false, TokenSlot.CURRENT);

        assertThat(auditCalls).hasValue(2);
    }

    @Test
    void theGlobalSwitchesAreRetriedAfterATransientConflict() {
        conflictOnFirstAudit();
        service.setGlobalAutofill(false, TokenSlot.CURRENT);
        verify(flags, times(2)).setAutofill(false);

        auditCalls.set(0);
        conflictOnFirstAudit();
        service.setGastoProvedor(false, TokenSlot.CURRENT);
        verify(flags, times(2)).setProviderSpend(false);
    }

    private void conflictOnFirstAudit() {
        doAnswer(call -> {
            if (auditCalls.incrementAndGet() == 1) {
                MongoException conflict = new MongoException(112, "synthetic write conflict");
                conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                throw new DataIntegrityViolationException("synthetic", conflict);
            }
            return call.getArgument(0);
        }).when(audits).insert(any(AuditEvent.class));
    }

    private static Map<String, Object> input() {
        Map<String, Object> input = new HashMap<>();
        input.put("nome", "Loja Exemplo");
        input.put("categoria", "geral");
        input.put("contato_tipo", "email");
        input.put("contato_valor", "contato@loja.test");
        input.put("plano", "interno");
        return input;
    }
}
