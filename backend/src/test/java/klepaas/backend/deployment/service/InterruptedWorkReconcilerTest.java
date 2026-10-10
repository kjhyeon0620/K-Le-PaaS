package klepaas.backend.deployment.service;

import klepaas.backend.ai.entity.CommandLog;
import klepaas.backend.ai.entity.CommandStatus;
import klepaas.backend.ai.entity.ConversationSession;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.infra.kubernetes.RolloutResult;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 재시작 대조(#57)를 실제 PostgreSQL 행으로 확인한다. Kubernetes는 판정 결과만 흉내 낸다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({InterruptedWorkReconciler.class, ResourceAccessService.class, JpaConfig.class})
class InterruptedWorkReconcilerTest {
    @Autowired private InterruptedWorkReconciler reconciler;
    @Autowired private DeploymentRepository deployments;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private CommandLogRepository commands;
    @Autowired private ConversationSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private KubernetesManifestGenerator k8s;

    @Test
    void reconcilesOnlyPreviousProcessWorkAndNeverReExecutes() {
        User owner = users.save(User.builder().name("owner").email("owner@example.com").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("owner").repoName("app")
                .gitUrl("https://github.com/owner/app").cloudVendor(CloudVendor.ON_PREMISE).build());
        SourceRepository orphan = repositories.save(SourceRepository.builder().owner("orphan").repoName("app")
                .gitUrl("https://github.com/orphan/app").cloudVendor(CloudVendor.ON_PREMISE).build());

        Long pending = deployment(repo, DeploymentStatus.PENDING, false);
        Long uploading = deployment(repo, DeploymentStatus.UPLOADING_SOURCE, false);
        Long building = deployment(repo, DeploymentStatus.BUILDING, false);
        Long noEvidence = deployment(repo, DeploymentStatus.DEPLOYING, false);
        Long succeeded = deployment(repo, DeploymentStatus.DEPLOYING, true);
        Long replaced = deployment(repo, DeploymentStatus.DEPLOYING, true);
        Long raced = deployment(repo, DeploymentStatus.DEPLOYING, true);
        Long broken = deployment(repo, DeploymentStatus.DEPLOYING, true);
        Long unowned = deployment(orphan, DeploymentStatus.DEPLOYING, true);
        Long terminal = deployment(repo, DeploymentStatus.SUCCESS, true);

        Long executing = command(owner, CommandStatus.EXECUTING);
        Long pendingCommand = command(owner, CommandStatus.PENDING);
        Long done = command(owner, CommandStatus.SUCCEEDED);

        when(k8s.observeOnce(any(), anyLong(), eq(succeeded), any(), any(), anyLong()))
                .thenReturn(new RolloutResult(RolloutResult.Outcome.SUCCEEDED, null, null, "sha256:abc"));
        when(k8s.observeOnce(any(), anyLong(), eq(replaced), any(), any(), anyLong()))
                .thenReturn(new RolloutResult(RolloutResult.Outcome.SUPERSEDED, "다른 변경으로 대체됨: generation 3 → 4",
                        FailureKind.SUPERSEDED, null));
        // 조회하는 사이 다른 경로가 이미 종료 상태로 바꿨다
        when(k8s.observeOnce(any(), anyLong(), eq(raced), any(), any(), anyLong())).thenAnswer(inv -> {
            jdbc.update("update deployments set status = 'FAILED', fail_reason = 'other path' where id = ?", raced);
            return new RolloutResult(RolloutResult.Outcome.SUCCEEDED, null, null, null);
        });
        when(k8s.observeOnce(any(), anyLong(), eq(broken), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("boom"));

        reconciler.afterSingletonsInstantiated(); // 웹 서버가 열리기 전 대상 확정

        // 대상 확정 이후 생긴 요청: 새 배포, 재시작 후 승인된 이전 PENDING 명령
        Long fresh = deployment(repo, DeploymentStatus.DEPLOYING, true);
        jdbc.update("update command_log set status = 'EXECUTING' where id = ?", pendingCommand);

        reconciler.reconcile();

        for (Long id : new Long[]{pending, uploading, building}) {
            Deployment d = deployments.findById(id).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeploymentStatus.FAILED);
            assertThat(d.getFailureKind()).isEqualTo(FailureKind.OTHER);
            assertThat(d.getFailReason()).startsWith("재시작 대조: ").contains("중단").contains("다시 실행하지 않음");
            assertThat(d.getFinishedAt()).isNotNull();
        }
        assertThat(deployments.findById(noEvidence).orElseThrow())
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeploymentStatus.UNKNOWN))
                .satisfies(d -> assertThat(d.getFailReason()).contains("적용 근거"));
        assertThat(deployments.findById(succeeded).orElseThrow())
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS))
                .satisfies(d -> assertThat(d.getImageDigest()).isEqualTo("sha256:abc"))
                .satisfies(d -> assertThat(d.getFailReason()).isNull());
        assertThat(deployments.findById(replaced).orElseThrow())
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeploymentStatus.CANCELED))
                .satisfies(d -> assertThat(d.getFailureKind()).isEqualTo(FailureKind.SUPERSEDED));
        assertThat(deployments.findById(raced).orElseThrow())
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeploymentStatus.FAILED))
                .satisfies(d -> assertThat(d.getFailReason()).isEqualTo("other path"));
        assertThat(deployments.findById(broken).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.DEPLOYING);
        assertThat(deployments.findById(unowned).orElseThrow())
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(DeploymentStatus.UNKNOWN))
                .satisfies(d -> assertThat(d.getFailReason()).contains("소유권"));
        assertThat(deployments.findById(terminal).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(deployments.findById(fresh).orElseThrow().getStatus()).isEqualTo(DeploymentStatus.DEPLOYING);

        assertThat(commands.findById(executing).orElseThrow())
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(CommandStatus.UNKNOWN))
                .satisfies(c -> assertThat(c.getErrorMessage()).contains("다시 실행하지 않음"));
        assertThat(commands.findById(pendingCommand).orElseThrow().getStatus()).isEqualTo(CommandStatus.EXECUTING);
        assertThat(commands.findById(done).orElseThrow().getStatus()).isEqualTo(CommandStatus.SUCCEEDED);

        // Kubernetes는 근거가 있고 소유권을 확인한 DEPLOYING 행만 한 번씩 조회하며 아무것도 바꾸지 않는다
        for (Long id : new Long[]{succeeded, replaced, raced, broken}) {
            verify(k8s).observeOnce(eq("owner-app"), eq(repo.getId()), eq(id), eq("img:v1"), eq("uid-" + id), eq(3L));
        }
        verifyNoMoreInteractions(k8s);

        reconciler.reconcile(); // 같은 프로세스에서 다시 불려도 대상이 없다
        verifyNoMoreInteractions(k8s);
    }

    private Long deployment(SourceRepository repo, DeploymentStatus status, boolean evidence) {
        Deployment d = Deployment.builder().sourceRepository(repo).branchName("main").commitHash("abcdef1").build();
        d.setImageUri("img:v1");
        deployments.saveAndFlush(d);
        if (evidence) {
            jdbc.update("update deployments set applied_resource_uid = ?, applied_generation = 3 where id = ?",
                    "uid-" + d.getId(), d.getId());
        }
        jdbc.update("update deployments set status = ? where id = ?", status.name(), d.getId());
        return d.getId();
    }

    private Long command(User user, CommandStatus status) {
        Long id = commands.saveAndFlush(CommandLog.builder().user(user)
                .rawCommand("deploy").interpretedIntent(Intent.DEPLOY).intentArgs("{}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true)
                .session(sessions.save(new ConversationSession(user))).build()).getId();
        jdbc.update("update command_log set status = ? where id = ?", status.name(), id);
        return id;
    }
}
