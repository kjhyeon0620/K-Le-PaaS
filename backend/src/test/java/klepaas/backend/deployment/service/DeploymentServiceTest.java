package klepaas.backend.deployment.service;

import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.dto.DeploymentStatusResponse;
import klepaas.backend.deployment.dto.ScaleRequest;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.ScalingHistory;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.ScalingHistoryRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.infra.CloudInfraProviderFactory;
import klepaas.backend.infra.kubernetes.KubernetesManifestGenerator;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private SourceRepositoryRepository sourceRepositoryRepository;
    @Mock
    private DeploymentConfigRepository deploymentConfigRepository;
    @Mock
    private ScalingHistoryRepository scalingHistoryRepository;
    @Mock
    private DeploymentPipelineService pipelineService;
    @Mock
    private CloudInfraProviderFactory infraProviderFactory;
    @Mock
    private KubernetesManifestGenerator k8sGenerator;
    @Mock private ResourceAccessService resourceAccessService;
    @Mock private RuntimeResourcePolicy runtimeResourcePolicy;
    @InjectMocks
    private DeploymentService deploymentService;

    private User testUser;
    private SourceRepository testRepo;
    private Deployment testDeployment;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@github.com")
                .name("tester")
                .role(Role.USER)
                .providerId("12345")
                .build();

        org.springframework.test.util.ReflectionTestUtils.setField(testUser, "id", 1L);
        testRepo = SourceRepository.builder()
                .user(testUser)
                .owner("testowner")
                .repoName("testrepo")
                .gitUrl("https://github.com/testowner/testrepo")
                .cloudVendor(CloudVendor.NCP)
                .build();

        org.springframework.test.util.ReflectionTestUtils.setField(testRepo, "id", 1L);
        testDeployment = Deployment.builder()
                .sourceRepository(testRepo)
                .branchName("main")
                .commitHash("abc1234")
                .build();
    }

    @Nested
    @DisplayName("createDeployment")
    class CreateDeployment {

        @Test
        void foreignAndMissingActorNeverStartPipeline() {
            var request = new CreateDeploymentRequest(1L, "main", "abc1234");
            given(resourceAccessService.requireRepository(1L, 2L))
                    .willThrow(new EntityNotFoundException(klepaas.backend.global.exception.ErrorCode.REPOSITORY_NOT_FOUND));
            given(resourceAccessService.requireRepository(1L, null))
                    .willThrow(new EntityNotFoundException(klepaas.backend.global.exception.ErrorCode.REPOSITORY_NOT_FOUND));

            assertThatThrownBy(() -> deploymentService.createDeployment(request, 2L))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> deploymentService.createDeployment(request, null))
                    .isInstanceOf(EntityNotFoundException.class);
            verifyNoInteractions(pipelineService, deploymentRepository, k8sGenerator);
        }

        @Test
        @DisplayName("성공: 배포 생성 후 비동기 파이프라인 실행")
        void success() {
            var request = new CreateDeploymentRequest(1L, "main", "abc1234");
            given(resourceAccessService.requireRepository(1L, 1L)).willReturn(testRepo);
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.empty());
            given(deploymentRepository.save(any(Deployment.class))).willAnswer(invocation -> invocation.getArgument(0));

            TransactionSynchronizationManager.initSynchronization();
            try {
                DeploymentResponse response = deploymentService.createDeployment(request, 1L);

                assertThat(response.branchName()).isEqualTo("main");
                assertThat(response.commitHash()).isEqualTo("abc1234");
                assertThat(response.status()).isEqualTo(DeploymentStatus.PENDING);

                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }

            verify(pipelineService).executePipeline(any());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 레포지토리")
        void failRepositoryNotFound() {
            var request = new CreateDeploymentRequest(999L, "main", "abc1234");
            given(resourceAccessService.requireRepository(999L, 1L)).willThrow(new EntityNotFoundException(klepaas.backend.global.exception.ErrorCode.REPOSITORY_NOT_FOUND));

            assertThatThrownBy(() -> deploymentService.createDeployment(request, 1L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getDeployment")
    class GetDeployment {

        @Test
        @DisplayName("성공: 배포 상세 조회")
        void success() {
            given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(testDeployment);

            DeploymentResponse response = deploymentService.getDeployment(1L, 1L);

            assertThat(response.branchName()).isEqualTo("main");
            assertThat(response.repositoryName()).isEqualTo("testowner/testrepo");
        }

        @Test
        @DisplayName("실패: 존재하지 않는 배포")
        void failNotFound() {
            given(resourceAccessService.requireDeployment(999L, 1L)).willThrow(new EntityNotFoundException(klepaas.backend.global.exception.ErrorCode.DEPLOYMENT_NOT_FOUND));

            assertThatThrownBy(() -> deploymentService.getDeployment(999L, 1L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getDeploymentStatus")
    class GetDeploymentStatus {

        @Test
        @DisplayName("성공: 배포 상태 조회 - PENDING")
        void success() {
            given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(testDeployment);

            DeploymentStatusResponse response = deploymentService.getDeploymentStatus(1L, 1L);

            assertThat(response.status()).isEqualTo(DeploymentStatus.PENDING);
            assertThat(response.failReason()).isNull();
        }
    }

    @Nested
    @DisplayName("scaleDeployment")
    class ScaleDeployment {

        @Test
        void foreignActorNeverCallsKubernetes() {
            given(resourceAccessService.requireDeployment(1L, 2L))
                    .willThrow(new EntityNotFoundException(klepaas.backend.global.exception.ErrorCode.DEPLOYMENT_NOT_FOUND));
            assertThatThrownBy(() -> deploymentService.scaleDeployment(1L,
                    new klepaas.backend.deployment.dto.ScaleRequest(3), 2L))
                    .isInstanceOf(EntityNotFoundException.class);
            verifyNoInteractions(k8sGenerator, scalingHistoryRepository);
        }

        @Test
        @DisplayName("성공: 실제 이전 replica 수를 이력에 기록")
        void success() {
            given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(testDeployment);
            given(k8sGenerator.scale("testowner-testrepo", 3, 1L)).willReturn(2);
            given(scalingHistoryRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            int previous = deploymentService.scaleDeployment(1L, new ScaleRequest(3), "USER", 1L);

            assertThat(previous).isEqualTo(2);
            ArgumentCaptor<ScalingHistory> history = ArgumentCaptor.forClass(ScalingHistory.class);
            verify(scalingHistoryRepository).save(history.capture());
            assertThat(history.getValue().getPreviousReplicas()).isEqualTo(2);
            assertThat(history.getValue().getNewReplicas()).isEqualTo(3);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("실패: 1 미만 replicas는 Kubernetes 호출 없이 거부")
        void rejectsNonPositiveReplicas(int replicas) {
            assertThatThrownBy(() -> deploymentService.scaleDeployment(1L, new ScaleRequest(replicas), "NLP", 1L))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(k8sGenerator, scalingHistoryRepository);
        }
    }

    @Nested
    @DisplayName("restartDeployment")
    class RestartDeployment {

        @Test
        @DisplayName("성공: replica 수를 바꾸지 않고 rolling restart")
        void success() {
            given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(testDeployment);

            deploymentService.restartDeployment(1L, 1L);

            verify(k8sGenerator).restart("testowner-testrepo", 1L);
            verify(k8sGenerator, never()).scale(anyString(), anyInt(), anyLong());
        }
    }
}
