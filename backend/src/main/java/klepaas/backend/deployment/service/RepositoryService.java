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
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.GitHubAppInstallationRequiredException;
import klepaas.backend.global.exception.GitHubAppNotInstalledException;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepositoryService {

    private final SourceRepositoryRepository sourceRepositoryRepository;
    private final DeploymentConfigRepository deploymentConfigRepository;
    private final UserRepository userRepository;
    private final GitHubAppClient gitHubAppClient;
    private final GitHubAppConfig gitHubAppConfig;

    @Value("${deployment.domain.suffix:klepaas.io}")
    private String deploymentDomainSuffix = "klepaas.io";

    @Transactional
    public RepositoryResponse createRepository(Long userId, CreateRepositoryRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));

        sourceRepositoryRepository.findByOwnerAndRepoName(request.owner(), request.repoName())
                .ifPresent(r -> {
                    throw new DuplicateResourceException(ErrorCode.REPOSITORY_ALREADY_EXISTS);
                });

        String domainUrl = resolveCreateDomainUrl(request);
        validateDomainUrlAvailable(domainUrl);

        checkGitHubAppInstalled(request.owner(), request.repoName());

        SourceRepository repository = SourceRepository.builder()
                .user(user)
                .owner(request.owner())
                .repoName(request.repoName())
                .gitUrl(request.gitUrl())
                .cloudVendor(request.cloudVendor())
                .build();
        sourceRepositoryRepository.save(repository);

        DeploymentConfig defaultConfig = DeploymentConfig.builder()
                .sourceRepository(repository)
                .minReplicas(1)
                .maxReplicas(1)
                .envVars(new HashMap<>())
                .containerPort(8080)
                .domainUrl(domainUrl)
                .buildStrategy(defaultBuildStrategy(request.cloudVendor()))
                .build();
        saveDeploymentConfig(defaultConfig);

        log.info("Repository created: {}/{} (id={})", request.owner(), request.repoName(), repository.getId());
        return RepositoryResponse.from(repository);
    }

    private void checkGitHubAppInstalled(String owner, String repoName) {
        try {
            gitHubAppClient.getInstallationId(owner, repoName);
        } catch (GitHubAppNotInstalledException e) {
            String installUrl = "https://github.com/apps/" + gitHubAppConfig.getAppSlug() + "/installations/new";
            throw new GitHubAppInstallationRequiredException(owner, repoName, installUrl);
        }
    }

    public List<RepositoryResponse> getRepositories(Long userId) {
        return sourceRepositoryRepository.findAllByUserId(userId).stream()
                .map(RepositoryResponse::from)
                .toList();
    }

    public RepositoryResponse getRepository(Long repositoryId) {
        SourceRepository repository = sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));
        return RepositoryResponse.from(repository);
    }

    @Transactional
    public void deleteRepository(Long repositoryId) {
        SourceRepository repository = sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .ifPresent(deploymentConfigRepository::delete);

        sourceRepositoryRepository.delete(repository);
        log.info("Repository deleted: id={}", repositoryId);
    }

    public DeploymentConfigResponse getDeploymentConfig(Long repositoryId) {
        sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));
        return DeploymentConfigResponse.from(config);
    }

    @Transactional
    public DeploymentConfigResponse updateDeploymentConfig(Long repositoryId,
                                                            UpdateDeploymentConfigRequest request) {
        sourceRepositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.REPOSITORY_NOT_FOUND));

        DeploymentConfig config = deploymentConfigRepository.findBySourceRepositoryId(repositoryId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.DEPLOYMENT_CONFIG_NOT_FOUND));

        String domainUrl = resolveUpdateDomainUrl(repositoryId, config, request.domainUrl());
        ServiceExposure serviceExposure = resolveServiceExposure(config, request);
        List<String> envFromConfigMaps = normalizeEnvFromRefs(request.envFromConfigMaps(), "env_from_config_maps");
        List<String> envFromSecrets = normalizeEnvFromRefs(request.envFromSecrets(), "env_from_secrets");
        config.updateConfig(
                request.minReplicas(),
                request.maxReplicas(),
                request.envVars(),
                request.containerPort(),
                domainUrl,
                request.buildStrategy(),
                request.imageUriTemplate(),
                request.imagePullSecretName(),
                serviceExposure.serviceType(),
                serviceExposure.nodePort(),
                envFromConfigMaps,
                envFromSecrets
        );
        flushDeploymentConfigChanges();

        log.info("DeploymentConfig updated: repositoryId={}", repositoryId);
        return DeploymentConfigResponse.from(config);
    }

    private ServiceExposure resolveServiceExposure(DeploymentConfig config, UpdateDeploymentConfigRequest request) {
        KubernetesServiceType serviceType = request.serviceType() != null
                ? request.serviceType()
                : config.getServiceType();
        Integer nodePort = request.nodePort() != null ? request.nodePort() : config.getNodePort();

        if (serviceType == KubernetesServiceType.NODE_PORT && nodePort == null) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "NODE_PORT 서비스는 nodePort가 필요합니다");
        }

        if (serviceType == KubernetesServiceType.CLUSTER_IP) {
            if (request.nodePort() != null) {
                throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "CLUSTER_IP 서비스에는 nodePort를 설정할 수 없습니다");
            }
            return new ServiceExposure(serviceType, null);
        }

        return new ServiceExposure(serviceType, nodePort);
    }

    private BuildStrategy defaultBuildStrategy(CloudVendor cloudVendor) {
        return switch (cloudVendor) {
            case NCP, AWS -> BuildStrategy.KANIKO;
            case ON_PREMISE -> BuildStrategy.GITHUB_ACTIONS_GHCR;
        };
    }

    private String resolveCreateDomainUrl(CreateRepositoryRequest request) {
        if (StringUtils.hasText(request.domainUrl())) {
            return canonicalizeDomainHost(request.domainUrl(), "domain_url");
        }
        String suffix = canonicalizeDomainHost(normalizeDomainSuffix(), "DEPLOYMENT_DOMAIN_SUFFIX");
        return canonicalizeDomainHost(normalizeRepoName(request.repoName()) + "." + suffix, "domain_url");
    }

    private String normalizeRepoName(String repoName) {
        String normalized = repoName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+|-+$", "");
        if (!StringUtils.hasText(normalized)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "repoName을 DNS-safe 도메인 이름으로 변환할 수 없습니다");
        }
        return normalized;
    }

    private String normalizeDomainSuffix() {
        String suffix = deploymentDomainSuffix == null ? "" : deploymentDomainSuffix.trim();
        if (!StringUtils.hasText(suffix)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "deployment domain suffix가 설정되지 않았습니다");
        }
        return suffix;
    }

    private void validateDomainUrlAvailable(String domainUrl) {
        if (deploymentConfigRepository.existsByDomainUrl(domainUrl)) {
            throw new DuplicateResourceException(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private void saveDeploymentConfig(DeploymentConfig config) {
        try {
            deploymentConfigRepository.save(config);
            deploymentConfigRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throwDomainDuplicateIfApplicable(e);
            throw e;
        }
    }

    private void flushDeploymentConfigChanges() {
        try {
            deploymentConfigRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throwDomainDuplicateIfApplicable(e);
            throw e;
        }
    }

    private void throwDomainDuplicateIfApplicable(DataIntegrityViolationException exception) {
        if (isDomainUrlIntegrityViolation(exception)) {
            throw new DuplicateResourceException(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    private boolean isDomainUrlIntegrityViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalizedMessage = message.toLowerCase(Locale.ROOT);
                if (normalizedMessage.contains("domain_url")
                        || normalizedMessage.contains("domainurl")
                        || normalizedMessage.contains("uk_deployment_configs_domain_url")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveUpdateDomainUrl(Long repositoryId, DeploymentConfig config, String domainUrl) {
        if (domainUrl == null) {
            return config.getDomainUrl();
        }

        if (!StringUtils.hasText(domainUrl)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "domain_url은 비워둘 수 없습니다");
        }

        String canonicalDomainUrl = canonicalizeDomainHost(domainUrl, "domain_url");
        if (deploymentConfigRepository.existsByDomainUrlAndSourceRepositoryIdNot(canonicalDomainUrl, repositoryId)) {
            throw new DuplicateResourceException(ErrorCode.DUPLICATE_RESOURCE);
        }
        return canonicalDomainUrl;
    }

    private String canonicalizeDomainHost(String value, String fieldName) {
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (!isValidDomainHost(host)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, fieldName + "이 올바른 DNS host 형식이 아닙니다");
        }
        return host;
    }

    private boolean isValidDomainHost(String host) {
        if (host.length() > 253 || host.startsWith(".") || host.endsWith(".")) {
            return false;
        }

        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }

        for (String label : labels) {
            if (!isValidDomainLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidDomainLabel(String label) {
        return !label.isBlank()
                && label.length() <= 63
                && label.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?");
    }

    private List<String> normalizeEnvFromRefs(List<String> names, String fieldName) {
        if (names == null) {
            return List.of();
        }
        LinkedHashSet<String> normalizedNames = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalizedName = name.trim();
            if (!isValidKubernetesObjectName(normalizedName)) {
                throw new InvalidRequestException(
                        ErrorCode.INVALID_REQUEST,
                        fieldName + "에는 올바른 Kubernetes ConfigMap/Secret 이름만 사용할 수 있습니다: " + normalizedName
                );
            }
            normalizedNames.add(normalizedName);
        }
        return new ArrayList<>(normalizedNames);
    }

    private boolean isValidKubernetesObjectName(String name) {
        if (name.length() > 253 || name.startsWith(".") || name.endsWith(".")) {
            return false;
        }
        String[] labels = name.split("\\.", -1);
        for (String label : labels) {
            if (!isValidDomainLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private record ServiceExposure(KubernetesServiceType serviceType, Integer nodePort) {
    }

}
