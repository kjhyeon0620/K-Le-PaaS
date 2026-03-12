package klepaas.backend.infra.service;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.infra.CloudInfraProvider;
import klepaas.backend.infra.dto.BuildResult;
import klepaas.backend.infra.dto.BuildStatusResult;
import klepaas.backend.infra.util.ImageTagGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service("ncpInfraService")
@RequiredArgsConstructor
public class NcpInfraService implements CloudInfraProvider {

    private final S3Client s3Client;
    private final KubernetesClient kubernetesClient;

    @Value("${cloud.ncp.storage.bucket}")
    private String bucketName;

    @Value("${cloud.ncp.credentials.access-key}")
    private String ncpAccessKey;

    @Value("${cloud.ncp.credentials.secret-key}")
    private String ncpSecretKey;

    @Value("${cloud.ncp.container-registry.endpoint}")
    private String registryEndpoint;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    @Value("${kubernetes.image-pull-secret:ncp-cr}")
    private String imagePullSecretName;

    @Value("${kaniko.image:gcr.io/kaniko-project/executor:latest}")
    private String kanikoImage;

    private static final String NCP_STORAGE_ENDPOINT = "https://kr.object.ncloudstorage.com";

    // GitHub redirect를 수동으로 처리하기 위해 redirect 비활성화
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public String uploadSourceToStorage(String gitToken, Deployment deployment) {
        SourceRepository repo = deployment.getSourceRepository();
        String storageKey = "builds/" + deployment.getId() + "/source.zip";

        try {
            resolveCommitHashIfNeeded(gitToken, deployment);

            // Step 1: GitHub API 호출 → 302 redirect URL 획득 (auth 필요)
            String apiUrl = "https://api.github.com/repos/" + repo.getOwner() + "/" +
                    repo.getRepoName() + "/zipball/" + deployment.getCommitHash();

            HttpRequest authRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + gitToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<Void> redirectResponse = httpClient.send(
                    authRequest, HttpResponse.BodyHandlers.discarding());

            String downloadUrl = redirectResponse.headers().firstValue("Location")
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.SOURCE_UPLOAD_FAILED, "GitHub ZIP redirect URL을 받지 못했습니다"));

            // Step 2: redirect URL에서 실제 ZIP 다운로드 (auth 헤더 없이)
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .GET()
                    .build();

            byte[] zipBytes = httpClient.send(
                    downloadRequest, HttpResponse.BodyHandlers.ofByteArray()).body();

            if (zipBytes == null || zipBytes.length == 0) {
                throw new BusinessException(ErrorCode.SOURCE_UPLOAD_FAILED, "GitHub ZIP 다운로드 결과가 비어있습니다");
            }

            // Step 3: GitHub ZIP 최상위 디렉토리 제거 후 재패키징
            // GitHub 아카이브 구조: "owner-repo-sha/Dockerfile" → "Dockerfile" (Kaniko context 호환)
            byte[] repackaged = stripTopLevelDir(zipBytes);

            // Step 4: NCP Object Storage 업로드
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storageKey)
                    .contentType("application/zip")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(repackaged));
            log.info("Source uploaded: bucket={}, key={}, originalSize={}, repackagedSize={}",
                    bucketName, storageKey, zipBytes.length, repackaged.length);

            return storageKey;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Source upload failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.SOURCE_UPLOAD_FAILED, "소스 업로드 실패: " + e.getMessage());
        }
    }

    @Override
    public BuildResult triggerBuild(String storageKey, Deployment deployment) {
        SourceRepository repo = deployment.getSourceRepository();
        String imageName = repo.getOwner() + "-" + repo.getRepoName();
        String imageUri = ImageTagGenerator.buildImageUri(registryEndpoint, imageName, deployment.getCommitHash());
        String jobName = "klepaas-build-" + deployment.getId();

        try {
            Job job = buildKanikoJob(jobName, storageKey, imageUri, deployment);
            kubernetesClient.batch().v1().jobs()
                    .inNamespace(namespace)
                    .resource(job)
                    .create();

            log.info("Kaniko Job created: jobName={}, deploymentId={}, image={}",
                    jobName, deployment.getId(), imageUri);

            // projectId = namespace (Job 조회 시 필요), externalBuildId = jobName
            return new BuildResult(jobName, namespace, imageUri);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Kaniko Job creation failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.BUILD_TRIGGER_FAILED, "Kaniko Job 생성 실패: " + e.getMessage());
        }
    }

    @Override
    public BuildStatusResult getBuildStatus(String projectId, String buildId) {
        // projectId = namespace, buildId = Kaniko Job name
        Job job = kubernetesClient.batch().v1().jobs()
                .inNamespace(projectId)
                .withName(buildId)
                .get();

        if (job == null) {
            throw new BusinessException(ErrorCode.BUILD_FAILED, "Kaniko Job을 찾을 수 없음: " + buildId);
        }

        JobStatus status = job.getStatus();
        boolean succeeded = status.getSucceeded() != null && status.getSucceeded() > 0;
        boolean failed = status.getFailed() != null && status.getFailed() > 0;

        // Job이 아직 완료 안 됐으면 Pod 상태도 체크 (FailedMount, ImagePullBackOff 등 조기 감지)
        if (!succeeded && !failed) {
            String podFailureReason = detectPodFailure(projectId, buildId);
            if (podFailureReason != null) {
                log.warn("Kaniko Pod early failure detected: job={}, reason={}", buildId, podFailureReason);
                return new BuildStatusResult(true, false, null, podFailureReason);
            }
        }

        boolean completed = succeeded || failed;
        String message = succeeded ? "success" : (failed ? "failed" : "running");

        log.debug("Kaniko Job status: job={}, succeeded={}, failed={}, active={}",
                buildId, status.getSucceeded(), status.getFailed(), status.getActive());

        return new BuildStatusResult(completed, succeeded, null, message);
    }

    /**
     * Pod가 영구적으로 시작 불가한 상태인지 감지.
     * Job.status.failed는 컨테이너가 실행 후 종료된 경우만 올라가므로,
     * FailedMount/ImagePullBackOff 등 컨테이너 시작 전 실패는 별도 체크 필요.
     */
    private String detectPodFailure(String namespace, String jobName) {
        PodList podList = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list();

        for (Pod pod : podList.getItems()) {
            PodStatus podStatus = pod.getStatus();

            // Pod 자체가 Failed 상태
            if ("Failed".equals(podStatus.getPhase())) {
                return "Pod failed: " + podStatus.getReason();
            }

            // initContainer 실패 감지 (source-downloader: S3 다운로드/압축 해제 실패 등)
            List<ContainerStatus> initStatuses = podStatus.getInitContainerStatuses();
            if (initStatuses != null) {
                for (ContainerStatus cs : initStatuses) {
                    if (cs.getState() != null && cs.getState().getTerminated() != null) {
                        int exitCode = cs.getState().getTerminated().getExitCode();
                        if (exitCode != 0) {
                            return "initContainer '" + cs.getName() + "' 실패 (exitCode=" + exitCode +
                                    "): kubectl logs -n " + namespace + " " +
                                    pod.getMetadata().getName() + " -c " + cs.getName();
                        }
                    }
                }
            }

            // 컨테이너 waiting reason 체크 (이미지 풀 실패, 설정 오류 등)
            List<ContainerStatus> containerStatuses = podStatus.getContainerStatuses();
            if (containerStatuses != null) {
                for (ContainerStatus cs : containerStatuses) {
                    if (cs.getState() != null && cs.getState().getWaiting() != null) {
                        String reason = cs.getState().getWaiting().getReason();
                        String message = cs.getState().getWaiting().getMessage();
                        if ("ImagePullBackOff".equals(reason) || "ErrImagePull".equals(reason)
                                || "CreateContainerConfigError".equals(reason)
                                || "InvalidImageName".equals(reason)) {
                            return reason + (message != null ? ": " + message : "");
                        }
                    }
                }
            }

            // Pod conditions 체크 — Unschedulable 등
            if (podStatus.getConditions() != null) {
                for (PodCondition condition : podStatus.getConditions()) {
                    if ("False".equals(condition.getStatus())
                            && "PodScheduled".equals(condition.getType())
                            && "Unschedulable".equals(condition.getReason())) {
                        return "Pod unschedulable: " + condition.getMessage();
                    }
                }
            }

            // FailedMount 감지 — Pod events 조회
            boolean hasFailedMount = kubernetesClient.v1().events()
                    .inNamespace(namespace)
                    .withField("involvedObject.name", pod.getMetadata().getName())
                    .list()
                    .getItems()
                    .stream()
                    .anyMatch(e -> "Warning".equals(e.getType()) && "FailedMount".equals(e.getReason()));

            if (hasFailedMount) {
                return "FailedMount: Secret 또는 ConfigMap을 찾을 수 없음 (kubectl describe pod " +
                        pod.getMetadata().getName() + " -n " + namespace + " 로 확인)";
            }
        }

        return null;
    }

    @Override
    public void scaleService(String resourceName, int replicas) {
        log.info("Scale requested via NCP: resource={}, replicas={}", resourceName, replicas);
    }

    private Job buildKanikoJob(String jobName, String storageKey, String imageUri, Deployment deployment) {
        String shortSha = ImageTagGenerator.toShortSha(deployment.getCommitHash());

        Map<String, String> labels = Map.of(
                "app.kubernetes.io/managed-by", "klepaas",
                "klepaas.io/deployment-id", String.valueOf(deployment.getId()),
                "klepaas.io/commit-sha", shortSha
        );

        // initContainer: NCP Object Storage에서 ZIP 다운로드 후 /workspace에 압축 해제
        // Kaniko에게 S3를 직접 읽게 하지 않으므로 endpoint 호환성 문제 제거
        String downloadCmd = "aws s3 cp s3://" + bucketName + "/" + storageKey + " /tmp/source.zip" +
                " --endpoint-url " + NCP_STORAGE_ENDPOINT +
                " && python3 -c \"import zipfile; zipfile.ZipFile('/tmp/source.zip').extractall('/workspace')\"" +
                " && rm /tmp/source.zip";

        Container initContainer = new ContainerBuilder()
                .withName("source-downloader")
                .withImage("amazon/aws-cli:latest")
                .withCommand("/bin/sh", "-c")
                .withArgs(downloadCmd)
                .withEnv(
                        new EnvVarBuilder().withName("AWS_ACCESS_KEY_ID").withValue(ncpAccessKey).build(),
                        new EnvVarBuilder().withName("AWS_SECRET_ACCESS_KEY").withValue(ncpSecretKey).build()
                )
                .withVolumeMounts(new VolumeMountBuilder()
                        .withName("workspace")
                        .withMountPath("/workspace")
                        .build())
                .build();

        // Kaniko: 로컬 디렉토리 컨텍스트 사용 (S3 endpoint 의존성 제거)
        Container kanikoContainer = new ContainerBuilder()
                .withName("kaniko")
                .withImage(kanikoImage)
                .withArgs(
                        "--context=dir:///workspace",
                        "--destination=" + imageUri,
                        "--compressed-caching=false",
                        "--snapshot-mode=redo"
                )
                .withVolumeMounts(
                        new VolumeMountBuilder()
                                .withName("docker-config")
                                .withMountPath("/kaniko/.docker")
                                .build(),
                        new VolumeMountBuilder()
                                .withName("workspace")
                                .withMountPath("/workspace")
                                .build()
                )
                .build();

        return new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withTtlSecondsAfterFinished(3600)
                    .withBackoffLimit(0)
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withInitContainers(initContainer)
                            .withContainers(kanikoContainer)
                            .withVolumes(
                                    new VolumeBuilder()
                                            .withName("workspace")
                                            .withNewEmptyDir()
                                            .endEmptyDir()
                                            .build(),
                                    new VolumeBuilder()
                                            .withName("docker-config")
                                            .withNewSecret()
                                                .withSecretName(imagePullSecretName)
                                                .withItems(new KeyToPathBuilder()
                                                        .withKey(".dockerconfigjson")
                                                        .withPath("config.json")
                                                        .build())
                                            .endSecret()
                                            .build()
                            )
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private void resolveCommitHashIfNeeded(String gitToken, Deployment deployment) throws IOException, InterruptedException {
        if (!"HEAD".equalsIgnoreCase(deployment.getCommitHash())) {
            return;
        }

        SourceRepository repo = deployment.getSourceRepository();
        String apiUrl = "https://api.github.com/repos/" + repo.getOwner() + "/" +
                repo.getRepoName() + "/commits/" + deployment.getBranchName();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "Bearer " + gitToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusinessException(ErrorCode.SOURCE_UPLOAD_FAILED,
                    "브랜치 HEAD 커밋 조회 실패: HTTP " + response.statusCode());
        }

        String resolvedSha = extractCommitSha(response.body());
        deployment.updateCommitHash(resolvedSha);
        log.info("Resolved HEAD to commit SHA: deploymentId={}, branch={}, sha={}",
                deployment.getId(), deployment.getBranchName(), resolvedSha);
    }

    private String extractCommitSha(String body) {
        int shaKeyIndex = body.indexOf("\"sha\"");
        if (shaKeyIndex < 0) {
            throw new BusinessException(ErrorCode.SOURCE_UPLOAD_FAILED, "GitHub 커밋 SHA 응답 파싱 실패");
        }

        int colonIndex = body.indexOf(':', shaKeyIndex);
        int firstQuote = body.indexOf('"', colonIndex + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (colonIndex < 0 || firstQuote < 0 || secondQuote < 0) {
            throw new BusinessException(ErrorCode.SOURCE_UPLOAD_FAILED, "GitHub 커밋 SHA 응답 파싱 실패");
        }
        return body.substring(firstQuote + 1, secondQuote);
    }

    /**
     * GitHub 아카이브 ZIP의 최상위 디렉토리 제거.
     * GitHub ZIP 구조: "owner-repo-sha/path" → "path" (Kaniko는 root에 Dockerfile 필요)
     */
    private byte[] stripTopLevelDir(byte[] zipBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes));
             ZipOutputStream zout = new ZipOutputStream(baos)) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                int firstSlash = name.indexOf('/');

                // 최상위 디렉토리 자체 엔트리 (예: "owner-repo-sha/") → 스킵
                if (firstSlash < 0 || firstSlash == name.length() - 1) {
                    continue;
                }

                String newName = name.substring(firstSlash + 1);
                zout.putNextEntry(new ZipEntry(newName));
                zin.transferTo(zout);
                zout.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
