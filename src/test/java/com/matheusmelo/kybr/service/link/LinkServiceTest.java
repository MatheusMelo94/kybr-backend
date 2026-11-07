package com.matheusmelo.kybr.service.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.matheusmelo.kybr.exception.ApiException;
import com.matheusmelo.kybr.exception.ErrorCode;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.support.link.TestWorkspaces;
import java.util.Optional;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkServiceTest {

    private final WorkspaceRepository repository = mock(WorkspaceRepository.class);
    private final LinkService service = new LinkService(repository);

    @ParameterizedTest
    @ValueSource(strings = {"Loja", "-loja", "loja-", "lo..ja", "loja/x", "loja x", "",
        "a1234567890123456789012345678901234567890123456789012345678901234"})
    void anOffPatternSlugIsLinkNotFoundWithoutReadingTheDatabase(String slug) {
        assertLinkNotFound(() -> service.resolveActive(slug));
        assertLinkNotFound(() -> service.resolveReadable(slug));
        verifyNoInteractions(repository);
    }

    @Test
    void aNullSlugIsLinkNotFoundWithoutReadingTheDatabase() {
        assertLinkNotFound(() -> service.resolveActive(null));
        verifyNoInteractions(repository);
    }

    @Test
    void anUnknownSlugIsLinkNotFoundOnEveryRoute() {
        when(repository.findBySlug("loja-desconhecida")).thenReturn(Optional.empty());

        assertLinkNotFound(() -> service.resolveActive("loja-desconhecida"));
        assertLinkNotFound(() -> service.resolveReadable("loja-desconhecida"));
    }

    @Test
    void aWorkspaceWithoutAContactIsLinkNotFoundOnEveryRoute() {
        Workspace notOnboarded = stored(builder -> builder.contato(null));

        assertLinkNotFound(() -> service.resolveActive(notOnboarded.slug()));
        assertLinkNotFound(() -> service.resolveReadable(notOnboarded.slug()));
    }

    @Test
    void aDisabledLinkIsNotFoundForNewActivityButStillReadable() {
        Workspace disabled = stored(builder -> builder.linkAtivo(false));

        assertLinkNotFound(() -> service.resolveActive(disabled.slug()));
        assertThat(service.resolveReadable(disabled.slug())).isEqualTo(disabled);
    }

    @Test
    void anActiveOnboardedWorkspaceResolves() {
        Workspace active = stored(Function.identity());

        assertThat(service.resolveActive(active.slug())).isEqualTo(active);
        assertThat(service.resolveReadable(active.slug())).isEqualTo(active);
    }

    private Workspace stored(Function<TestWorkspaces.Builder, TestWorkspaces.Builder> customise) {
        Workspace workspace = customise.apply(TestWorkspaces.builder()).build();
        when(repository.findBySlug(workspace.slug())).thenReturn(Optional.of(workspace));
        return workspace;
    }

    private static void assertLinkNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.LINK_NAO_ENCONTRADO));
    }
}
