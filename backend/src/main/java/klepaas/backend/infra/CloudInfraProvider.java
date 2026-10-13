package klepaas.backend.infra;

import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.infra.dto.BuildResult;
import klepaas.backend.infra.dto.BuildStatusResult;

public interface CloudInfraProvider {

    /**
     * 소스코드 스트리밍 업로드 (Source Staging)
     * GitHub ZIP 스트림 -> Object Storage
     */
    String uploadSourceToStorage(String gitToken, Deployment deployment);

    /**
     * 빌드 트리거 (빌드만 시작, 배포는 별도)
     */
    BuildResult triggerBuild(String storageKey, Deployment deployment);

    /**
     * 빌드 상태 조회
     */
    BuildStatusResult getBuildStatus(String projectId, String buildId);

    /**
     * 스케일링
     */
    void scaleService(String resourceName, int replicas);
}
