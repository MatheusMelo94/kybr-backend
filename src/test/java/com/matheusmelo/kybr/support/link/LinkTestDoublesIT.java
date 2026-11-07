package com.matheusmelo.kybr.support.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.matheusmelo.kybr.model.link.LinkCase;
import com.matheusmelo.kybr.model.workspace.Workspace;
import com.matheusmelo.kybr.repository.LinkCaseRepository;
import com.matheusmelo.kybr.repository.WorkspaceRepository;
import com.matheusmelo.kybr.service.company.CompanyDataProvider;
import com.matheusmelo.kybr.service.verification.VerificationDispatcher;
import com.matheusmelo.kybr.support.MongoIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** The app context starts with the link test doubles standing in for the provider and the dispatcher. */
@Import(LinkTestDoublesConfiguration.class)
class LinkTestDoublesIT extends MongoIntegrationTest {

    @Autowired
    private CompanyDataProvider provider;

    @Autowired
    private VerificationDispatcher dispatcher;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private LinkCaseRepository cases;

    @Test
    void theDoublesAreThePrimaryBeans() {
        assertThat(provider).isInstanceOf(FakeCompanyDataProvider.class);
        assertThat(dispatcher).isInstanceOf(RecordingDispatcher.class);
    }

    @Test
    void theBuildersProduceStorableRecords() {
        Workspace workspace = workspaces.save(TestWorkspaces.builder().build());
        TestCases.Built built = TestCases.builder(workspace).build();

        cases.save(built.linkCase());

        LinkCase stored = cases.findByTokenHashAndWorkspaceId(TestCases.hash(built.token()), workspace.id())
                .orElseThrow();
        assertThat(stored.politica()).isEqualTo(workspace.politica());
        assertThat(workspaces.findBySlug(workspace.slug())).contains(workspace);
    }

    @Test
    void theRecordingDispatcherRecordsEachSubmission() {
        RecordingDispatcher recording = (RecordingDispatcher) dispatcher;
        recording.clear();
        UUID caseId = UUID.randomUUID();

        dispatcher.submit(caseId);

        assertThat(recording.submissions()).containsExactly(new RecordingDispatcher.Submission(caseId, false));
        assertThat(recording.submitted()).containsExactly(caseId);
    }
}
