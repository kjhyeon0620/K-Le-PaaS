package klepaas.backend.deployment.service;

import klepaas.backend.auth.config.GitHubAppConfig;
import klepaas.backend.auth.oauth.GitHubAppClient;
import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.DeploymentConfig;
import klepaas.backend.deployment.entity.KubernetesServiceType;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentConfigRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.global.exception.DuplicateResourceException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.GitHubAppInstallationRequiredException;
import klepaas.backend.global.exception.GitHubAppNotInstalledException;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RepositoryServiceTest {

    @Mock
    private SourceRepositoryRepository sourceRepositoryRepository;
    @Mock
    private DeploymentConfigRepository deploymentConfigRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GitHubAppClient gitHubAppClient;
    @Mock
    private GitHubAppConfig gitHubAppConfig;

    @InjectMocks
    private RepositoryService repositoryService;

    private User testUser;
    private SourceRepository testRepo;
    private DeploymentConfig testConfig;

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

        testConfig = DeploymentConfig.builder()
                .sourceRepository(testRepo)
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(new HashMap<>())
                .containerPort(8080)
                .domainUrl("testrepo.klepaas.io")
                .build();
    }

    @Nested
    @DisplayName("createRepository")
    class CreateRepository {

        @Test
        @DisplayName("성공: 레포지토리 + 기본 배포 설정 생성")
        void success() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willReturn(testRepo);
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willReturn(testConfig);

            RepositoryResponse response = repositoryService.createRepository(1L, request);

            assertThat(response.owner()).isEqualTo("testowner");
            assertThat(response.repoName()).isEqualTo("testrepo");
            assertThat(response.cloudVendor()).isEqualTo(CloudVendor.NCP);
            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl()).isEqualTo("testrepo.klepaas.io");
        }

        @Test
        @DisplayName("성공: 설정된 suffix로 기본 배포 도메인 생성")
        void successDefaultDomainWithConfiguredSuffix() {
            ReflectionTestUtils.setField(repositoryService, "deploymentDomainSuffix", "juhyeon.app");
            var request = new CreateRepositoryRequest("kjhyeon0620", "smart-sousvide-iot-platform",
                    "https://github.com/kjhyeon0620/smart-sousvide-iot-platform", CloudVendor.ON_PREMISE);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("kjhyeon0620", "smart-sousvide-iot-platform"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("kjhyeon0620", "smart-sousvide-iot-platform")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl())
                    .isEqualTo("smart-sousvide-iot-platform.juhyeon.app");
        }

        @Test
        @DisplayName("성공: repoName을 DNS-safe하게 변환해 기본 도메인 생성")
        void successNormalizesRepoNameForDefaultDomain() {
            var request = new CreateRepositoryRequest("testowner", "Smart_Sousvide IoT.Platform",
                    "https://github.com/testowner/Smart_Sousvide IoT.Platform", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "Smart_Sousvide IoT.Platform"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "Smart_Sousvide IoT.Platform")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl())
                    .isEqualTo("smart-sousvide-iot-platform.klepaas.io");
        }

        @Test
        @DisplayName("성공: custom domain_url을 우선 사용")
        void successUsesCustomDomainUrl() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP, "custom.example.com");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl()).isEqualTo("custom.example.com");
        }

        @Test
        @DisplayName("성공: custom domain_url은 lowercase canonical 값으로 저장")
        void successCanonicalizesCustomDomainUrl() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP, "Custom.Example.COM");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).existsByDomainUrl("custom.example.com");
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl()).isEqualTo("custom.example.com");
        }

        @Test
        @DisplayName("성공: custom domain_url이 blank면 기본 도메인 사용")
        void successBlankCustomDomainFallsBackToDefaultDomain() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP, " ");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            assertThat(configCaptor.getValue().getDomainUrl()).isEqualTo("testrepo.klepaas.io");
        }

        @Test
        @DisplayName("실패: custom domain_url이 DNS host 형식이 아니면 거절")
        void failInvalidCustomDomainUrl() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP, "https://example.com");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("domain_url");
        }

        @Test
        @DisplayName("성공: ON_PREMISE 저장소는 GitHub Actions GHCR 전략을 쓰되 이미지 템플릿은 명시 설정하게 둔다")
        void successOnPremiseDefaultBuildStrategy() {
            var request = new CreateRepositoryRequest("kjhyeon0620", "smart-sousvide-iot-platform",
                    "https://github.com/kjhyeon0620/smart-sousvide-iot-platform", CloudVendor.ON_PREMISE);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("kjhyeon0620", "smart-sousvide-iot-platform"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("kjhyeon0620", "smart-sousvide-iot-platform")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

            repositoryService.createRepository(1L, request);

            ArgumentCaptor<DeploymentConfig> configCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
            verify(deploymentConfigRepository).save(configCaptor.capture());
            DeploymentConfig savedConfig = configCaptor.getValue();
            assertThat(savedConfig.getBuildStrategy()).isEqualTo(BuildStrategy.GITHUB_ACTIONS_GHCR);
            assertThat(savedConfig.getImageUriTemplate()).isNull();
        }

        @Test
        @DisplayName("실패: GitHub App 미설치 저장소")
        void failGitHubAppNotInstalled() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo"))
                    .willThrow(new GitHubAppNotInstalledException("testowner", "testrepo"));
            given(gitHubAppConfig.getAppSlug()).willReturn("klepaas");

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(GitHubAppInstallationRequiredException.class)
                    .satisfies(e -> assertThat(
                            ((GitHubAppInstallationRequiredException) e).getInstallUrl())
                            .contains("github.com/apps/klepaas/installations/new"));
        }

        @Test
        @DisplayName("실패: 이미 존재하는 레포지토리")
        void failDuplicate() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.of(testRepo));

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: 기본 domain_url이 이미 사용 중")
        void failDuplicateDefaultDomainUrl() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(deploymentConfigRepository.existsByDomainUrl("testrepo.klepaas.io")).willReturn(true);

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: custom domain_url이 이미 사용 중")
        void failDuplicateCustomDomainUrl() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP, "custom.example.com");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(deploymentConfigRepository.existsByDomainUrl("custom.example.com")).willReturn(true);

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: DB unique 제약으로 domain_url 중복이 감지되면 409 예외로 변환")
        void failDuplicateDomainUrlOnFlush() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(new DataIntegrityViolationException("domain_url")).given(deploymentConfigRepository).flush();

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: domain_url과 무관한 DB 제약 위반은 원 예외를 유지")
        void failNonDomainIntegrityViolationKeepsOriginalException() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());
            given(gitHubAppClient.getInstallationId("testowner", "testrepo")).willReturn(123L);
            given(sourceRepositoryRepository.save(any(SourceRepository.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(deploymentConfigRepository.save(any(DeploymentConfig.class))).willAnswer(invocation -> invocation.getArgument(0));
            willThrow(new DataIntegrityViolationException("other_constraint"))
                    .given(deploymentConfigRepository).flush();

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("실패: repoName 정규화 결과가 비어 있음")
        void failBlankNormalizedRepoName() {
            var request = new CreateRepositoryRequest("testowner", "___",
                    "https://github.com/testowner/___", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "___"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("repoName");
        }

        @Test
        @DisplayName("실패: 기본 도메인의 repoName label이 63자를 초과하면 거절")
        void failDefaultDomainWithTooLongRepoLabel() {
            String repoName = "a".repeat(64);
            var request = new CreateRepositoryRequest("testowner", repoName,
                    "https://github.com/testowner/" + repoName, CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", repoName))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("domain_url");
        }

        @Test
        @DisplayName("실패: 설정된 suffix가 DNS host 형식이 아니면 거절")
        void failInvalidDeploymentDomainSuffix() {
            ReflectionTestUtils.setField(repositoryService, "deploymentDomainSuffix", ".juhyeon.app");
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(sourceRepositoryRepository.findByOwnerAndRepoName("testowner", "testrepo"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.createRepository(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("DEPLOYMENT_DOMAIN_SUFFIX");
        }

        @Test
        @DisplayName("실패: 존재하지 않는 사용자")
        void failUserNotFound() {
            var request = new CreateRepositoryRequest("testowner", "testrepo",
                    "https://github.com/testowner/testrepo", CloudVendor.NCP);
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.createRepository(999L, request))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getRepositories")
    class GetRepositories {

        @Test
        @DisplayName("성공: 사용자의 레포지토리 목록 조회")
        void success() {
            given(sourceRepositoryRepository.findAllByUserId(1L)).willReturn(List.of(testRepo));

            List<RepositoryResponse> result = repositoryService.getRepositories(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).owner()).isEqualTo("testowner");
        }
    }

    @Nested
    @DisplayName("deleteRepository")
    class DeleteRepository {

        @Test
        @DisplayName("성공: 레포지토리 + 배포 설정 삭제")
        void success() {
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L))
                    .willReturn(Optional.of(testConfig));

            repositoryService.deleteRepository(1L);

            verify(deploymentConfigRepository).delete(testConfig);
            verify(sourceRepositoryRepository).delete(testRepo);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 레포지토리")
        void failNotFound() {
            given(sourceRepositoryRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> repositoryService.deleteRepository(999L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("updateDeploymentConfig")
    class UpdateDeploymentConfig {

        @Test
        @DisplayName("성공: 배포 설정 업데이트")
        void success() {
            var request = new UpdateDeploymentConfigRequest(
                    2,
                    5,
                    Map.of("ENV", "prod"),
                    3000,
                    "custom.klepaas.io",
                    BuildStrategy.GITHUB_ACTIONS_GHCR,
                    "ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}",
                    "ghcr-pull-secret",
                    KubernetesServiceType.NODE_PORT,
                    30080
            );
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L))
                    .willReturn(Optional.of(testConfig));

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            assertThat(response.minReplicas()).isEqualTo(2);
            assertThat(response.maxReplicas()).isEqualTo(5);
            assertThat(response.containerPort()).isEqualTo(3000);
            assertThat(response.domainUrl()).isEqualTo("custom.klepaas.io");
            assertThat(response.buildStrategy()).isEqualTo(BuildStrategy.GITHUB_ACTIONS_GHCR);
            assertThat(response.imageUriTemplate()).isEqualTo("ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}");
            assertThat(response.imagePullSecretName()).isEqualTo("ghcr-pull-secret");
            assertThat(response.serviceType()).isEqualTo(KubernetesServiceType.NODE_PORT);
            assertThat(response.nodePort()).isEqualTo(30080);
        }

        @Test
        @DisplayName("실패: NODE_PORT 서비스는 nodePort가 필요하다")
        void failNodePortServiceWithoutNodePort() {
            var request = new UpdateDeploymentConfigRequest(
                    2,
                    5,
                    Map.of("ENV", "prod"),
                    3000,
                    "custom.klepaas.io",
                    BuildStrategy.GITHUB_ACTIONS_GHCR,
                    "ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}",
                    "ghcr-pull-secret",
                    KubernetesServiceType.NODE_PORT,
                    null
            );
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("nodePort");
        }

        @Test
        @DisplayName("성공: CLUSTER_IP로 전환하면 기존 nodePort를 제거한다")
        void successClearsNodePortWhenServiceTypeIsClusterIp() {
            DeploymentConfig nodePortConfig = DeploymentConfig.builder()
                    .sourceRepository(testRepo)
                    .minReplicas(1)
                    .maxReplicas(1)
                    .envVars(Map.of())
                    .containerPort(8080)
                    .domainUrl("repo.klepaas.io")
                    .serviceType(KubernetesServiceType.NODE_PORT)
                    .nodePort(30080)
                    .build();
            var request = new UpdateDeploymentConfigRequest(
                    1,
                    1,
                    Map.of(),
                    8080,
                    "repo.klepaas.io",
                    null,
                    null,
                    null,
                    KubernetesServiceType.CLUSTER_IP,
                    null
            );
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(nodePortConfig));

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            assertThat(response.serviceType()).isEqualTo(KubernetesServiceType.CLUSTER_IP);
            assertThat(response.nodePort()).isNull();
        }

        @Test
        @DisplayName("실패: CLUSTER_IP 서비스에는 nodePort를 설정할 수 없다")
        void failClusterIpServiceWithNodePort() {
            var request = new UpdateDeploymentConfigRequest(
                    2,
                    5,
                    Map.of("ENV", "prod"),
                    3000,
                    "custom.klepaas.io",
                    null,
                    null,
                    null,
                    KubernetesServiceType.CLUSTER_IP,
                    30080
            );
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("nodePort");
        }

        @Test
        @DisplayName("성공: 새 build 설정 필드가 생략되면 기존 값을 유지")
        void successPreservesBuildSettingsWhenRequestOmitsThem() {
            DeploymentConfig onPremiseConfig = DeploymentConfig.builder()
                    .sourceRepository(testRepo)
                    .minReplicas(1)
                    .maxReplicas(1)
                    .envVars(Map.of())
                    .containerPort(8080)
                    .domainUrl("repo.klepaas.io")
                    .buildStrategy(BuildStrategy.GITHUB_ACTIONS_GHCR)
                    .imageUriTemplate("ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}")
                    .imagePullSecretName("ghcr-pull-secret")
                    .serviceType(KubernetesServiceType.NODE_PORT)
                    .nodePort(30080)
                    .build();
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "custom.klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(onPremiseConfig));

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            assertThat(response.buildStrategy()).isEqualTo(BuildStrategy.GITHUB_ACTIONS_GHCR);
            assertThat(response.imageUriTemplate()).isEqualTo("ghcr.io/{owner}/{repoName}/backend:sha-{commitHash}");
            assertThat(response.imagePullSecretName()).isEqualTo("ghcr-pull-secret");
            assertThat(response.serviceType()).isEqualTo(KubernetesServiceType.NODE_PORT);
            assertThat(response.nodePort()).isEqualTo(30080);
        }

        @Test
        @DisplayName("성공: 자기 자신의 기존 domain_url은 유지 가능")
        void successAllowsOwnDomainUrl() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "testrepo.klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));
            given(deploymentConfigRepository.existsByDomainUrlAndSourceRepositoryIdNot("testrepo.klepaas.io", 1L))
                    .willReturn(false);

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            assertThat(response.domainUrl()).isEqualTo("testrepo.klepaas.io");
        }

        @Test
        @DisplayName("성공: domain_url을 생략하면 기존 domain_url을 유지")
        void successPreservesDomainUrlWhenRequestOmitsIt() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, null);
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            assertThat(response.domainUrl()).isEqualTo("testrepo.klepaas.io");
        }

        @Test
        @DisplayName("성공: 수정 domain_url은 lowercase canonical 값으로 저장")
        void successCanonicalizesDomainUrlOnUpdate() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "Custom.KLEPAAS.IO");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            DeploymentConfigResponse response = repositoryService.updateDeploymentConfig(1L, request);

            verify(deploymentConfigRepository).existsByDomainUrlAndSourceRepositoryIdNot("custom.klepaas.io", 1L);
            assertThat(response.domainUrl()).isEqualTo("custom.klepaas.io");
        }

        @Test
        @DisplayName("실패: 다른 저장소의 domain_url로 수정할 수 없다")
        void failDuplicateDomainUrlOnUpdate() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "custom.klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));
            given(deploymentConfigRepository.existsByDomainUrlAndSourceRepositoryIdNot("custom.klepaas.io", 1L))
                    .willReturn(true);

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: 수정 중 DB unique 제약으로 domain_url 중복이 감지되면 409 예외로 변환")
        void failDuplicateDomainUrlOnUpdateFlush() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "custom.klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));
            willThrow(new DataIntegrityViolationException("domain_url")).given(deploymentConfigRepository).flush();

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("실패: 수정 중 domain_url과 무관한 DB 제약 위반은 원 예외를 유지")
        void failNonDomainIntegrityViolationOnUpdateKeepsOriginalException() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "custom.klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));
            willThrow(new DataIntegrityViolationException("other_constraint"))
                    .given(deploymentConfigRepository).flush();

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("실패: 수정 domain_url이 DNS host 형식이 아니면 거절")
        void failInvalidDomainUrlOnUpdate() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, "repo..klepaas.io");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("domain_url");
        }

        @Test
        @DisplayName("실패: blank domain_url로 수정할 수 없다")
        void failBlankDomainUrlOnUpdate() {
            var request = new UpdateDeploymentConfigRequest(2, 5, Map.of("ENV", "prod"), 3000, " ");
            given(sourceRepositoryRepository.findById(1L)).willReturn(Optional.of(testRepo));
            given(deploymentConfigRepository.findBySourceRepositoryId(1L)).willReturn(Optional.of(testConfig));

            assertThatThrownBy(() -> repositoryService.updateDeploymentConfig(1L, request))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessageContaining("domain_url");
        }
    }
}
