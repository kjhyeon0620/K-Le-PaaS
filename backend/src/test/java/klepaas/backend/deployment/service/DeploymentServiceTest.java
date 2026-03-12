package klepaas.backend.deployment.service;

import klepaas.backend.deployment.dto.CreateDeploymentRequest;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.dto.DeploymentStatusResponse;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.ScalingHistoryRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

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

        testRepo = SourceRepository.builder()
                .user(testUser)
                .owner("testowner")
                .repoName("testrepo")
                .gitUrl("https://github.com/testowner/testrepo")
                .cloudVendor(CloudVendor.NCP)
                .build();

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
        @DisplayName("성공: 배포 생성 후 비동기 파이프라인 실행")
        void success() {
            var request = new CreateDeploymentRequest(1L, "main", "abc1234");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentRepository.save(any(Deployment.class))).willAnswer(invocation -> invocation.getArgument(0));

            TransactionSynchronizationManager.initSynchronization();
            try {
                DeploymentResponse response = deploymentService.createDeployment(request);

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
            given(sourceRepositoryRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deploymentService.createDeployment(request))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getDeployment")
    class GetDeployment {

        @Test
        @DisplayName("성공: 배포 상세 조회")
        void success() {
            given(deploymentRepository.findById(1L)).willReturn(Optional.of(testDeployment));

            DeploymentResponse response = deploymentService.getDeployment(1L);

            assertThat(response.branchName()).isEqualTo("main");
            assertThat(response.repositoryName()).isEqualTo("testowner/testrepo");
        }

        @Test
        @DisplayName("실패: 존재하지 않는 배포")
        void failNotFound() {
            given(deploymentRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> deploymentService.getDeployment(999L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getDeploymentStatus")
    class GetDeploymentStatus {

        @Test
        @DisplayName("성공: 배포 상태 조회 - PENDING")
        void success() {
            given(deploymentRepository.findById(1L)).willReturn(Optional.of(testDeployment));

            DeploymentStatusResponse response = deploymentService.getDeploymentStatus(1L);

            assertThat(response.status()).isEqualTo(DeploymentStatus.PENDING);
            assertThat(response.failReason()).isNull();
        }
    }

    @Nested
    @DisplayName("scaleDeployment")
    class ScaleDeployment {

        @Test
        @DisplayName("성공: K8s 스케일링 호출")
        void success() {
            given(deploymentRepository.findById(1L)).willReturn(Optional.of(testDeployment));
            given(deploymentConfigRepository.findBySourceRepositoryId(testRepo.getId()))
                    .willReturn(Optional.of(klepaas.backend.deployment.entity.DeploymentConfig.builder()
                            .sourceRepository(testRepo)
                            .minReplicas(1)
                            .maxReplicas(3)
                            .envVars(Map.of())
                            .containerPort(8080)
                            .domainUrl("repo.klepaas.io")
                            .build()));
            given(scalingHistoryRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            doNothing().when(k8sGenerator).scale("testowner-testrepo", 3);

            deploymentService.scaleDeployment(1L, new klepaas.backend.deployment.dto.ScaleRequest(3));

            verify(k8sGenerator).scale("testowner-testrepo", 3);
        }
    }

    @Nested
    @DisplayName("restartDeployment")
    class RestartDeployment {

        @Test
        @DisplayName("성공: scale 0 → 1로 재시작")
        void success() {
            given(deploymentRepository.findById(1L)).willReturn(Optional.of(testDeployment));
            doNothing().when(k8sGenerator).scale(anyString(), any(int.class));

            deploymentService.restartDeployment(1L);

            verify(k8sGenerator).scale("testowner-testrepo", 0);
            verify(k8sGenerator).scale("testowner-testrepo", 1);
        }
    }
}
