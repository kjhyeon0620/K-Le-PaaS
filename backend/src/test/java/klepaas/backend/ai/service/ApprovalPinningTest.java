package klepaas.backend.ai.service;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.dto.NlpConfirmRequest;
import klepaas.backend.ai.entity.*;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.DeploymentConfigSnapshots;
import klepaas.backend.deployment.service.ResourceAccessService;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 승인한 배포 설정 고정 (#56)을 실제 PostgreSQL 행·소유권 검사·설정 지문으로 확인한다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = "jwt.secret=approval-pinning-test-secret-at-least-32-bytes")
@Import({CommandConfirmationService.class, NlpCommandService.class, ApprovalTargetResolver.class,
        ResourceAccessService.class, DeploymentConfigSnapshots.class, JpaConfig.class})
class ApprovalPinningTest {
    @Autowired private NlpCommandService commands;
    @Autowired private ApprovalTargetResolver approvalTargets;
    @Autowired private CommandLogRepository logs;
    @Autowired private ConversationSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentConfigRepository configs;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GeminiClient geminiClient;
    @MockitoBean private IntentParser intentParser;
    @MockitoBean private ActionDispatcher dispatcher;

    @Test
    void approvedConfigIsPinnedUntilExecution() {
        User owner = users.save(User.builder().name("owner").email("owner@example.com").role(Role.USER).build());
        User stranger = users.save(User.builder().name("stranger").email("stranger@example.com").role(Role.USER).build());
        SourceRepository repo = repositories.save(SourceRepository.builder().user(owner).owner("owner").repoName("app")
                .gitUrl("https://github.com/owner/app").cloudVendor(CloudVendor.ON_PREMISE).build());
        DeploymentConfig config = configs.save(DeploymentConfig.builder().sourceRepository(repo).minReplicas(1).maxReplicas(1)
                .envVars(Map.of("SAMPLE_GREETING", "hello-secret-value")).containerPort(8080).domainUrl("app.example.test").build());
        Map<String, Object> args = Map.of("repository_id", repo.getId(), "commit_hash", "abcdef1");

        var approved = approvalTargets.resolve(Intent.DEPLOY, args, owner.getId()).orElseThrow();
        assertThat(approved.target().repository()).isEqualTo("owner/app");
        assertThat(approved.target().envNames()).containsExactly("SAMPLE_GREETING");
        assertThat(approved.target().toString()).doesNotContain("hello-secret-value");
        assertThat(approved.target().configFingerprint()).isEqualTo(approved.fingerprint().substring(0, 8));
        assertThat(approvalTargets.resolve(Intent.DEPLOY, args, stranger.getId())).isEmpty(); // 권한 밖 저장소
        assertThat(approvalTargets.resolve(Intent.SCALE, args, owner.getId())).isEmpty();

        // env 값·레플리카가 바뀌면 지문이 바뀌고, 되돌리면 같아진다
        setEnv(config, "{\"SAMPLE_GREETING\":\"bye\"}");
        assertThat(fingerprint(args, owner)).isNotEqualTo(approved.fingerprint());
        setEnv(config, "{\"SAMPLE_GREETING\":\"hello-secret-value\"}");
        jdbc.update("update deployment_configs set min_replicas = 2, max_replicas = 2 where id = ?", config.getId());
        assertThat(fingerprint(args, owner)).isNotEqualTo(approved.fingerprint());
        jdbc.update("update deployment_configs set min_replicas = 1, max_replicas = 1 where id = ?", config.getId());
        assertThat(fingerprint(args, owner)).isEqualTo(approved.fingerprint());

        // 승인 대기 중 설정 변경: 실행하지 않고 409, 명령은 FAILED
        Long changed = pending(owner, repo, approved.fingerprint());
        setEnv(config, "{\"SAMPLE_GREETING\":\"bye\"}");
        assertThatThrownBy(() -> commands.confirmCommand(owner.getId(), new NlpConfirmRequest(changed, true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_TARGET_CHANGED));
        assertThat(logs.findById(changed).orElseThrow().getStatus()).isEqualTo(CommandStatus.FAILED);
        verifyNoInteractions(dispatcher);

        // 지문 없는 대상 명령(이전 릴리스·대상 확인 실패)도 실행하지 않는다
        setEnv(config, "{\"SAMPLE_GREETING\":\"hello-secret-value\"}");
        Long unpinned = pending(owner, repo, null);
        assertThatThrownBy(() -> commands.confirmCommand(owner.getId(), new NlpConfirmRequest(unpinned, true)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(dispatcher);

        // 설정이 그대로면 승인 지문과 함께 실행된다
        Long same = pending(owner, repo, approved.fingerprint());
        when(dispatcher.dispatch(any(), eq(owner.getId()), eq(same), eq(approved.fingerprint()))).thenReturn("ok");
        var response = commands.confirmCommand(owner.getId(), new NlpConfirmRequest(same, true));
        assertThat(response.approvalTarget().configFingerprint()).isEqualTo(approved.target().configFingerprint());
        assertThat(logs.findById(same).orElseThrow().getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
        verify(dispatcher).dispatch(any(), eq(owner.getId()), eq(same), eq(approved.fingerprint()));
        verify(dispatcher, never()).dispatch(any(), anyLong(), any());
    }

    private String fingerprint(Map<String, Object> args, User owner) {
        return approvalTargets.resolve(Intent.DEPLOY, args, owner.getId()).orElseThrow().fingerprint();
    }

    private void setEnv(DeploymentConfig config, String json) {
        jdbc.update("update deployment_configs set env_vars = ? where id = ?", json, config.getId());
    }

    private Long pending(User user, SourceRepository repo, String fingerprint) {
        CommandLog log = CommandLog.builder().user(user).rawCommand("deploy").interpretedIntent(Intent.DEPLOY)
                .intentArgs("{\"repository_id\":" + repo.getId() + ",\"commit_hash\":\"abcdef1\"}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true)
                .session(sessions.save(new ConversationSession(user))).build();
        log.pinApprovedConfig(fingerprint);
        return logs.saveAndFlush(log).getId();
    }
}
