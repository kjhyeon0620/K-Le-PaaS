package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.Intent;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 앱 이름 해석 규칙 (#144, 스펙 0144) */
class NameTargetsTest {

    private final SourceRepositoryRepository repositories = mock(SourceRepositoryRepository.class);
    private final DeploymentRepository deployments = mock(DeploymentRepository.class);
    private final NameTargets targets = new NameTargets(repositories, deployments);

    @Test
    @DisplayName("부분 일치 후보가 하나면 그 저장소로 정하고 최근 성공 배포를 deployment_id로 고정한다")
    void uniquePartialMatchPinsLatestSuccess() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(2L, "kjhyeon0620", "klepaas-sample-node"),
                repo(1L, "kjhyeon0620", "smart-sousvide-iot-platform")));
        given(deployments.findFirstBySourceRepositoryIdAndStatusOrderByIdDesc(2L, DeploymentStatus.SUCCESS))
                .willReturn(Optional.of(deployment(22L)));

        var r = targets.resolve(Intent.RESTART, Map.of("app_name", "Sample-Node"), 1L);

        assertThat(r.question()).isNull();
        assertThat(r.args()).containsEntry("deployment_id", 22L).containsEntry("app_name", "Sample-Node");
    }

    @Test
    @DisplayName("정확 일치가 부분 일치보다 먼저다")
    void exactMatchWinsOverPartial() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(1L, "me", "api"), repo(2L, "me", "api-gateway")));
        given(deployments.findFirstBySourceRepositoryIdAndStatusOrderByIdDesc(1L, DeploymentStatus.SUCCESS))
                .willReturn(Optional.of(deployment(5L)));

        assertThat(targets.resolve(Intent.SCALE, Map.of("app_name", "api", "replicas", 2), 1L).args())
                .containsEntry("deployment_id", 5L);
    }

    @Test
    @DisplayName("같은 이름이 둘이면(owner 다름) 정하지 않고 후보를 보인다. owner/repo로 주면 하나로 정한다")
    void duplicateNamesAskWithCandidates() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(1L, "kjhyeon0620", "api"), repo(2L, "myorg", "api")));

        var ambiguous = targets.resolve(Intent.RESTART, Map.of("app_name", "api"), 1L);
        assertThat(ambiguous.question()).contains("여러 개").contains("kjhyeon0620/api").contains("myorg/api");
        assertThat(ambiguous.args()).doesNotContainKey("deployment_id");

        var rollback = targets.resolve(Intent.ROLLBACK, Map.of("app_name", "myorg/api"), 1L);
        assertThat(rollback.question()).isNull();
        assertThat(rollback.args()).containsEntry("owner", "myorg").containsEntry("repo", "api");
    }

    @Test
    @DisplayName("부분 일치 후보가 여럿이면 되묻는다. owner를 주면 부분 일치를 쓰지 않는다")
    void multiplePartialOrOwnerGivenAsk() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(1L, "me", "klepaas-sample-node"), repo(2L, "me", "old-sample-node")));

        assertThat(targets.resolve(Intent.RESTART, Map.of("app_name", "sample-node"), 1L).question()).contains("여러 개");
        assertThat(targets.resolve(Intent.ROLLBACK, Map.of("owner", "me", "repo", "sample-node"), 1L).question())
                .contains("이름의 저장소가 없습니다");
    }

    @Test
    @DisplayName("롤백·롤백 목록은 owner 없이 저장소 이름으로 owner·repo를 채운다")
    void rollbackFillsOwnerAndRepo() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(2L, "kjhyeon0620", "klepaas-sample-node")));

        assertThat(targets.resolve(Intent.ROLLBACK, Map.of("repo", "sample-node"), 1L).args())
                .containsEntry("owner", "kjhyeon0620").containsEntry("repo", "klepaas-sample-node");
        assertThat(targets.resolve(Intent.LIST_ROLLBACK, Map.of("repo", "klepaas-sample-node"), 1L).args())
                .containsEntry("owner", "kjhyeon0620");
        verify(deployments, never()).findFirstBySourceRepositoryIdAndStatusOrderByIdDesc(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("없는 이름은 본인 저장소 목록만 보이며 되묻고, 성공 배포가 없으면 대상이 없다고 알린다")
    void missingNameOrNoSuccessfulDeployment() {
        given(repositories.findAllByUserId(1L)).willReturn(List.of(repo(2L, "me", "klepaas-sample-node")));
        given(deployments.findFirstBySourceRepositoryIdAndStatusOrderByIdDesc(2L, DeploymentStatus.SUCCESS)).willReturn(Optional.empty());

        assertThat(targets.resolve(Intent.RESTART, Map.of("app_name", "billing"), 1L).question())
                .contains("'billing' 이름의 저장소가 없습니다").contains("me/klepaas-sample-node");
        assertThat(targets.resolve(Intent.RESTART, Map.of("app_name", "sample-node"), 1L).question())
                .contains("성공한 배포가 없어");
        given(repositories.findAllByUserId(3L)).willReturn(List.of());
        assertThat(targets.resolve(Intent.RESTART, Map.of("app_name", "sample-node"), 3L).question()).contains("등록된 저장소가 없습니다");
    }

    @Test
    @DisplayName("deployment_id가 있거나 이름이 없거나 다른 intent면 그대로 둔다 (DB 조회 없음)")
    void untouchedCases() {
        assertThat(targets.resolve(Intent.RESTART, Map.of("deployment_id", 7, "app_name", "x"), 1L).args())
                .containsEntry("deployment_id", 7);
        assertThat(targets.resolve(Intent.RESTART, Map.of(), 1L).question()).isNull();
        assertThat(targets.resolve(Intent.DEPLOY, Map.of("app_name", "x"), 1L).question()).isNull();
        verify(repositories, never()).findAllByUserId(anyLong());
    }

    private static SourceRepository repo(Long id, String owner, String name) {
        SourceRepository r = SourceRepository.builder().owner(owner).repoName(name)
                .gitUrl("https://github.com/" + owner + "/" + name).cloudVendor(CloudVendor.NCP).build();
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private static Deployment deployment(Long id) {
        Deployment d = Deployment.builder().branchName("main").commitHash("abcdef0").build();
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }
}
