package klepaas.backend.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubAppClientTest {
    @Test
    void installationAccountIncludesImmutableGitHubIdAndType() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.github.com/repos/alice/app/installation"))
                .andRespond(withSuccess("""
                        {"id":123,"account":{"id":456,"type":"User"}}
                        """, MediaType.APPLICATION_JSON));
        GitHubAppJwtProvider jwt = mock(GitHubAppJwtProvider.class);
        when(jwt.generateAppJwt()).thenReturn("fake-test-jwt");

        GitHubAppClient client = new GitHubAppClient(builder.build(), jwt);
        assertThat(client.getInstallationAccount("alice", "app"))
                .isEqualTo(new GitHubAppClient.InstallationAccount(456L, "User"));
        server.verify();
    }
}
