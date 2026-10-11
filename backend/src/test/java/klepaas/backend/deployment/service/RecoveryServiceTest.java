package klepaas.backend.deployment.service;

import klepaas.backend.auth.config.GitHubAppConfig;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.deployment.dto.DeploymentDetailResponse.ChangeKind;
import klepaas.backend.deployment.entity.*;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.kubernetes.DeploymentObservationReader;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 이전 성공 배포로 복구 (#101)를 실제 PostgreSQL 행·설정 검증 경로로 확인한다. 파이프라인과 Kubernetes는 흉내 낸다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "jwt.secret=recovery-service-test-secret-at-least-32-bytes")
@Import({klepaas.backend.ai.service.ApprovalTargetResolver.class, RecoveryService.class, ResourceAccessService.class, DeploymentConfigSnapshots.class, RepositoryService.class,
        DeploymentService.class, RuntimeResourcePolicy.class, JpaConfig.class})
class RecoveryServiceTest {
    @Autowired private RecoveryService recovery;
    @Autowired private klepaas.backend.ai.service.ApprovalTargetResolver approvalTargets;
    @Autowired private DeploymentConfigSnapshots snapshots;
    @Autowired private DeploymentRepository deployments;
    @Autowired private DeploymentConfigRepository configs;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DeploymentPipelineService pipeline;
    @MockitoBean private DeploymentPipelineStepService pipelineSteps;
    @MockitoBean private CloudInfraProviderFactory infraProviders;
    @MockitoBean private KubernetesManifestGenerator k8s;
    @MockitoBean private DeploymentObservationReader observations;
    @MockitoBean private GitHubAppClient gitHubAppClient;
    @MockitoBean private GitHubAppConfig gitHubAppConfig;

    private static final DeploymentOrigin WEB = new DeploymentOrigin(TriggerSource.WEB, null, null);

    @Test
    void recoversPreviousImageAndConfigOnlyWithTheConfirmedPlan() {
        User owner = users.save(User.builder().name("owner").email("owner@example.com").role(Role.USER).build());
        User stranger = users.save(User.builder().name("stranger").email("stranger@example.com").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("owner").repoName("app")
                .gitUrl("https://github.com/owner/app").cloudVendor(CloudVendor.ON_PREMISE).build());
        DeploymentConfig config = configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(1)
                .envVars(Map.of("GREETING", "hello")).containerPort(8080).domainUrl("app.example.test")
                .buildStrategy(BuildStrategy.PREBUILT_IMAGE).imagePullSecretEnabled(false).build());

        // v1 성공 → 설정 변경(replica·probe) 후 v2 성공 → v3 실패
        Long v1 = deployment(repo, "aaaaaaa1", "ghcr.io/owner/app:v1", "sha256:111", DeploymentStatus.SUCCESS, config);
        jdbc.update("update deployment_configs set min_replicas = 2, max_replicas = 2, health_probe_path = '/health' where id = ?", config.getId());
        Long v2 = deployment(repo, "bbbbbbb2", "ghcr.io/owner/app:v2", null, DeploymentStatus.SUCCESS, config);
        deployment(repo, "ccccccc3", "ghcr.io/owner/app:v3", null, DeploymentStatus.FAILED, config);

        assertThat(recovery.candidates(repo.getId(), owner.getId())).extracting("deploymentId").containsExactly(v2, v1);
        assertThat(recovery.findTarget(repo.getId(), null)).map(Deployment::getId).contains(v1); // 직전 성공
        assertThat(recovery.findTarget(repo.getId(), "bbbb")).map(Deployment::getId).contains(v2);
        assertThatThrownBy(() -> recovery.plan(v1, stranger.getId())).isInstanceOf(EntityNotFoundException.class);

        var plan = recovery.plan(v1, owner.getId());
        assertThat(plan.executable()).isTrue();
        assertThat(plan.image()).isEqualTo("ghcr.io/owner/app@sha256:111");
        assertThat(plan.digestPinned()).isTrue();
        assertThat(plan.configChanges()).extracting("field").contains("min_replicas", "max_replicas", "health_probe");
        assertThat(plan.envDifferences()).isEmpty();
        assertThat(recovery.plan(v2, owner.getId()).digestPinned()).isFalse();

        // 자연어 ROLLBACK(commit 없음)은 직전 성공 배포의 복구 계획을 승인 대상으로 보이고 계획 지문을 고정한다
        var nlp = approvalTargets.resolve(klepaas.backend.ai.entity.Intent.ROLLBACK,
                Map.of("owner", "owner", "repo", "app"), owner.getId()).orElseThrow();
        assertThat(nlp.fingerprint()).isEqualTo(plan.planFingerprint());
        assertThat(nlp.target().recoveredFromDeploymentId()).isEqualTo(v1);
        assertThat(nlp.target().image()).isEqualTo("ghcr.io/owner/app@sha256:111");
        assertThat(nlp.target().blockedReasons()).isEmpty();
        assertThat(approvalTargets.resolve(klepaas.backend.ai.entity.Intent.ROLLBACK,
                Map.of("owner", "owner", "repo", "app"), stranger.getId())).isEmpty();

        // 계획 확인 후 설정이 바뀌면 실행하지 않는다
        jdbc.update("update deployment_configs set container_port = 9090 where id = ?", config.getId());
        long before = deployments.count();
        assertThatThrownBy(() -> recovery.recover(v1, plan.planFingerprint(), owner.getId(), WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECOVERY_PLAN_CHANGED));
        assertThat(deployments.count()).isEqualTo(before);
        assertThat(configs.findById(config.getId()).orElseThrow().getContainerPort()).isEqualTo(9090);

        // env가 대상과 다르면 실행할 수 없다 (값은 기록되지 않음)
        jdbc.update("update deployment_configs set env_vars = '{\"GREETING\":\"bye\"}' where id = ?", config.getId());
        var blocked = recovery.plan(v1, owner.getId());
        assertThat(blocked.executable()).isFalse();
        assertThat(blocked.planFingerprint()).isNull();
        assertThat(blocked.envDifferences()).singleElement()
                .satisfies(c -> assertThat(c.field()).isEqualTo("env:GREETING"))
                .satisfies(c -> assertThat(c.kind()).isEqualTo(ChangeKind.VALUE_CHANGED))
                .satisfies(c -> assertThat(c.before()).isNull());
        assertThatThrownBy(() -> recovery.recover(v1, "anything", owner.getId(), WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECOVERY_BLOCKED));

        // 진행 중 배포가 있으면 설정 복원도 되돌린다
        jdbc.update("update deployment_configs set env_vars = '{\"GREETING\":\"hello\"}' where id = ?", config.getId());
        Long running = deployment(repo, "ddddddd4", "ghcr.io/owner/app:v4", null, DeploymentStatus.BUILDING, config);
        String fingerprint = recovery.plan(v1, owner.getId()).planFingerprint();
        assertThatThrownBy(() -> recovery.recover(v1, fingerprint, owner.getId(), WEB))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DEPLOYMENT_IN_PROGRESS));
        assertThat(configs.findById(config.getId()).orElseThrow().getMinReplicas()).isEqualTo(2);
        jdbc.update("update deployments set status = 'FAILED' where id = ?", running);
        verifyNoInteractions(pipeline);

        // 확인한 계획과 같으면 env 외 설정을 복원하고 대상 이미지로 배포한다
        var result = recovery.recover(v1, fingerprint, owner.getId(), WEB);
        DeploymentConfig restored = configs.findById(config.getId()).orElseThrow();
        assertThat(restored.getMinReplicas()).isEqualTo(1);
        assertThat(restored.getContainerPort()).isEqualTo(8080);
        assertThat(restored.getHealthProbe()).isNull();
        assertThat(restored.getEnvVars()).containsEntry("GREETING", "hello");
        Deployment created = deployments.findById(result.deployment().id()).orElseThrow();
        assertThat(created.getImageUri()).isEqualTo("ghcr.io/owner/app@sha256:111");
        assertThat(created.getCommitHash()).isEqualTo("aaaaaaa1");
        assertThat(created.getRecoveredFromDeploymentId()).isEqualTo(v1);
        assertThat(created.getApprovedConfigFingerprint()).isEqualTo(snapshots.fingerprint(restored));
        assertThat(created.getTriggerSource()).isEqualTo(TriggerSource.WEB);
        assertThat(result.deployment().recoveredFromDeploymentId()).isEqualTo(v1);
        verify(pipeline).executePipeline(created.getId());
        verify(pipeline, times(1)).executePipeline(anyLong());
    }

    @Test
    void blocksTargetsWithoutConfigRecordOrKanikoBuild() {
        User owner = users.save(User.builder().name("owner2").email("owner2@example.com").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("owner2").repoName("app")
                .gitUrl("https://github.com/owner2/app").cloudVendor(CloudVendor.ON_PREMISE).build());
        DeploymentConfig config = configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(1)
                .containerPort(8080).domainUrl("app2.example.test").buildStrategy(BuildStrategy.KANIKO).build());
        Long kaniko = deployment(repo, "eeeeeee5", "ncr.example/app:v1", null, DeploymentStatus.SUCCESS, config);
        Long legacy = deployment(repo, "fffffff6", "ncr.example/app:v0", null, DeploymentStatus.SUCCESS, null);

        assertThat(recovery.plan(kaniko, owner.getId()).blockedReasons()).anyMatch(r -> r.contains("KANIKO"));
        assertThat(recovery.plan(legacy, owner.getId()).blockedReasons()).anyMatch(r -> r.contains("설정 기록이 없습니다"));
        assertThat(recovery.candidates(repo.getId(), owner.getId()))
                .anySatisfy(c -> assertThat(c.configRecorded()).isFalse());
    }

    private Long deployment(SourceRepository repo, String commit, String image, String digest,
                            DeploymentStatus status, DeploymentConfig configAtThatTime) {
        Deployment d = Deployment.builder().sourceRepository(repo).branchName("main").commitHash(commit).build();
        d.setImageUri(image);
        if (configAtThatTime != null) {
            d.recordAppliedConfig(snapshots.toJson(snapshots.capture(configs.findById(configAtThatTime.getId()).orElseThrow())));
        }
        deployments.saveAndFlush(d);
        jdbc.update("update deployments set status = ?, image_digest = ? where id = ?", status.name(), digest, d.getId());
        return d.getId();
    }
}
