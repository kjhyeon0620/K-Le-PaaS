package klepaas.backend.deployment.service;

import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Change;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.ChangeKind;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.Comparison;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

class DeploymentDetailServiceTest {

    private final ResourceAccessService access = Mockito.mock(ResourceAccessService.class);
    private final DeploymentRepository deployments = Mockito.mock(DeploymentRepository.class);
    private final DeploymentConfigSnapshots snapshots = new DeploymentConfigSnapshots("test-secret-0123456789abcdef");
    private final DeploymentDetailService service = new DeploymentDetailService(access, deployments, snapshots,
            Mockito.mock(UserRepository.class), Mockito.mock(CommandLogRepository.class));
    private final SourceRepository repo = repo();

    private SourceRepository repo() {
        var r = SourceRepository.builder().owner("o").repoName("r").gitUrl("https://x").cloudVendor(CloudVendor.ON_PREMISE).build();
        ReflectionTestUtils.setField(r, "id", 2L);
        return r;
    }

    private Deployment deployment(long id, String image, Map<String, String> env) {
        var d = Deployment.builder().sourceRepository(repo).branchName("main").commitHash("c080f65").build();
        ReflectionTestUtils.setField(d, "id", id);
        d.setImageUri(image);
        if (env != null) {
            var config = DeploymentConfig.builder().minReplicas(1).maxReplicas(1).containerPort(8080)
                    .domainUrl("app.test").envVars(env).build();
            d.recordAppliedConfig(snapshots.toJson(snapshots.capture(config)));
        }
        return d;
    }

    private void stub(Deployment current, Deployment previous) {
        given(access.requireDeployment(current.getId(), 1L)).willReturn(current);
        given(deployments.findFirstBySourceRepositoryIdAndStatusAndIdLessThanOrderByIdDesc(2L, DeploymentStatus.SUCCESS,
                current.getId())).willReturn(Optional.ofNullable(previous));
    }

    @Test
    @DisplayName("#55 사례: commit·이미지가 같고 env만 빠진 실패 배포는 직전 성공과 env 삭제로 구분되고, 종류별 설명이 붙는다")
    void sameImageEnvRemovedIsVisible() {
        var success = deployment(9, "ghcr.io/o/r:sha-42b9944", Map.of("SAMPLE_GREETING", "hello"));
        success.completeSuccess("sha256:aaa");
        var failed = deployment(10, "ghcr.io/o/r:sha-42b9944", Map.of());
        failed.fail("rollout 실패: CrashLoopBackOff", FailureKind.CRASH_LOOP);
        stub(failed, success);

        var detail = service.getDetail(10L, 1L);

        assertThat(detail.comparison()).isEqualTo(Comparison.AVAILABLE);
        assertThat(detail.previousSuccess().id()).isEqualTo(9L);
        assertThat(detail.changes()).containsExactly(new Change("env:SAMPLE_GREETING", null, null, ChangeKind.REMOVED));
        assertThat(detail.config().envNames()).isEmpty();
        assertThat(detail.explanation().kind()).isEqualTo(FailureKind.CRASH_LOOP);
        assertThat(detail.explanation().checks()).isNotEmpty();
    }

    @Test
    @DisplayName("기록이 없는 과거 배포와 비교하면 이미지만 비교하고 설정은 NOT_RECORDED, 직전 성공이 없으면 NO_PREVIOUS")
    void unrecordedOrNoPrevious() {
        var old = deployment(5, "ghcr.io/o/r:sha-1", null);
        var current = deployment(6, "ghcr.io/o/r:sha-2", Map.of("A", "1"));
        stub(current, old);

        var detail = service.getDetail(6L, 1L);
        assertThat(detail.comparison()).isEqualTo(Comparison.NOT_RECORDED);
        assertThat(detail.changes()).containsExactly(
                new Change("image_uri", "ghcr.io/o/r:sha-1", "ghcr.io/o/r:sha-2", ChangeKind.CHANGED));
        assertThat(detail.explanation()).isNull();

        stub(old, null);
        var first = service.getDetail(5L, 1L);
        assertThat(first.comparison()).isEqualTo(Comparison.NO_PREVIOUS);
        assertThat(first.config()).isNull();
        assertThat(first.changes()).isEmpty();
    }

    @Test
    @DisplayName("env 값 변경은 지문으로만 판정하고, 지문 키가 다르면 UNKNOWN이다. 값은 응답에 없다")
    void envValueChangesUseFingerprintsOnly() {
        var config = DeploymentConfig.builder().minReplicas(1).maxReplicas(1).containerPort(8080).domainUrl("app.test");
        var before = snapshots.capture(config.envVars(Map.of("A", "1", "B", "x")).build());
        var after = snapshots.capture(config.envVars(Map.of("A", "2", "C", "y")).build());
        List<Change> changes = new ArrayList<>();
        DeploymentDetailService.compareConfig(changes, before, after);
        assertThat(changes).containsExactly(
                new Change("env:A", null, null, ChangeKind.VALUE_CHANGED),
                new Change("env:B", null, null, ChangeKind.REMOVED),
                new Change("env:C", null, null, ChangeKind.ADDED));

        var rotated = new DeploymentConfigSnapshots("rotated-secret-0123456789abcdef")
                .capture(config.envVars(Map.of("A", "1", "B", "x")).build());
        List<Change> unknown = new ArrayList<>();
        DeploymentDetailService.compareConfig(unknown, before, rotated);
        assertThat(unknown).containsExactly(
                new Change("env:A", null, null, ChangeKind.UNKNOWN),
                new Change("env:B", null, null, ChangeKind.UNKNOWN));
    }
}
