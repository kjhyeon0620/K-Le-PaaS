package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.deployment.entity.ContainerResources;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.entity.HealthProbe;
import klepaas.backend.deployment.entity.KubernetesServiceType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 배포 요청 상세 (#95): 요청 기록, 적용한 설정, 직전 성공 배포와의 차이, 실패 종류별 설명.
 * 기록되지 않은 값은 null이고 현재 값으로 채우지 않는다.
 */
public record DeploymentDetailResponse(
        DeploymentResponse deployment,
        Requester requestedBy,
        CommandSummary command,
        AppliedConfig config,
        PreviousSuccess previousSuccess,
        Comparison comparison,
        List<Change> changes,
        Explanation explanation
) {

    public record Requester(Long userId, String name) {
    }

    public record CommandSummary(Long id, String rawCommand, String status) {
    }

    /** 적용한 배포 설정. env는 이름만 준다 (값은 기록하지 않음). */
    public record AppliedConfig(
            BuildStrategy buildStrategy,
            String imageUriTemplate,
            int minReplicas,
            int maxReplicas,
            int containerPort,
            String domainUrl,
            KubernetesServiceType serviceType,
            Integer nodePort,
            List<String> envNames,
            List<String> envFromConfigMaps,
            List<String> envFromSecrets,
            boolean imagePullSecretEnabled,
            String imagePullSecretName,
            HealthProbe healthProbe,
            ContainerResources resources
    ) {
    }

    public record PreviousSuccess(Long id, String commitHash, String imageUri, String imageDigest,
                                  LocalDateTime finishedAt) {
    }

    public enum Comparison {
        AVAILABLE,      // 두 배포 모두 설정이 기록돼 있어 전부 비교했다
        NOT_RECORDED,   // 한쪽 설정 기록이 없어 이미지만 비교했다
        NO_PREVIOUS     // 직전 성공 배포가 없다
    }

    public enum ChangeKind { CHANGED, ADDED, REMOVED, VALUE_CHANGED, UNKNOWN }

    /** before·after는 값이 아니라 표시용 문자열이다. env는 값 대신 null이다. */
    public record Change(String field, String before, String after, ChangeKind kind) {
    }

    public record Explanation(FailureKind kind, String title, String summary, List<String> checks) {

        public static Explanation of(FailureKind kind) {
            return switch (kind) {
                case IMAGE_PULL -> new Explanation(kind, "이미지를 받지 못했습니다",
                        "노드가 이미지를 내려받지 못해 새 Pod가 시작되지 않았습니다.",
                        List.of("이미지 태그가 레지스트리에 있는지 확인합니다 (이벤트의 not found)",
                                "비공개 이미지면 pull secret 설정과 자격 증명 만료를 확인합니다",
                                "이미지 주소의 오타를 확인합니다"));
                case CRASH_LOOP -> new Explanation(kind, "컨테이너가 시작 후 반복해서 종료됩니다",
                        "컨테이너가 실행 직후 종료돼 Kubernetes가 재시작을 반복하고 있습니다.",
                        List.of("Logs의 직전 컨테이너 로그에서 종료 이유를 확인합니다",
                                "필수 환경 변수와 설정이 빠지지 않았는지 확인합니다 (변경 내역의 env)",
                                "마지막 종료 사유가 OOMKilled면 메모리 limit을 확인합니다"));
                case CONFIG_ERROR -> new Explanation(kind, "컨테이너를 만들 수 없습니다",
                        "컨테이너 설정에서 참조한 리소스가 없어 컨테이너를 만들지 못했습니다.",
                        List.of("envFrom으로 참조한 ConfigMap·Secret이 namespace에 있는지 확인합니다",
                                "Pod 이벤트의 오류 메시지를 확인합니다"));
                case READINESS_TIMEOUT -> new Explanation(kind, "readiness probe를 통과하지 못했습니다",
                        "컨테이너는 실행 중이지만 제한 시간 안에 Ready가 되지 않았습니다. 기존 Pod는 계속 응답합니다.",
                        List.of("이벤트의 Readiness probe failed 메시지(상태 코드)를 확인합니다",
                                "health probe 경로·포트가 앱과 맞는지 확인합니다",
                                "앱 기동이 rollout 제한 시간보다 오래 걸리는지 확인합니다"));
                case UNSCHEDULABLE -> new Explanation(kind, "Pod를 배치할 노드가 없습니다",
                        "스케줄러가 새 Pod를 둘 노드를 찾지 못했습니다.",
                        List.of("이벤트의 FailedScheduling 메시지를 확인합니다",
                                "CPU·메모리 requests가 노드 여유보다 큰지 확인합니다"));
                case ROLLOUT_TIMEOUT -> new Explanation(kind, "제한 시간 안에 새 Pod가 준비되지 않았습니다",
                        "다른 종류로 분류되지 않은 rollout 타임아웃입니다.",
                        List.of("실패 원인의 replica 수와 Pod 상태를 확인합니다", "Logs의 이벤트를 확인합니다"));
                case PROGRESS_DEADLINE -> new Explanation(kind, "Kubernetes가 진행 기한 초과로 판정했습니다",
                        "Deployment가 진행 기한(ProgressDeadlineExceeded) 안에 진행되지 않았습니다.",
                        List.of("이전 배포부터 멈춰 있던 상태인지 배포 목록을 확인합니다", "Logs의 이벤트를 확인합니다"));
                case DEPLOYMENT_MISSING -> new Explanation(kind, "대기 중 Deployment가 사라졌습니다",
                        "rollout을 기다리는 동안 Kubernetes Deployment가 삭제됐습니다.",
                        List.of("같은 시각에 리소스를 지운 작업이 있었는지 확인합니다"));
                case SUPERSEDED -> new Explanation(kind, "다른 변경으로 대체됐습니다",
                        "결과를 기다리는 동안 다른 배포·재시작·스케일이 적용돼 이 요청의 결과를 판정하지 않았습니다.",
                        List.of("같은 시각의 다른 배포와 재시작·스케일 기록을 확인합니다"));
                case BUILD_FAILED -> new Explanation(kind, "이미지 준비 단계에서 실패했습니다",
                        "Kubernetes에 적용하기 전, 빌드나 배포할 이미지를 정하는 단계에서 실패했습니다.",
                        List.of("실패 원인 메시지를 확인합니다", "이미지 템플릿 또는 요청의 image_uri를 확인합니다"));
                case APPLY_FAILED -> new Explanation(kind, "Kubernetes 리소스 적용에 실패했습니다",
                        "Deployment·Service·Ingress를 적용하는 단계에서 실패했습니다.",
                        List.of("실패 원인 메시지를 확인합니다",
                                "허용된 ConfigMap·Secret·pull secret 참조인지 확인합니다"));
                case OTHER -> new Explanation(kind, "분류되지 않은 실패입니다",
                        "알려진 종류에 해당하지 않는 실패입니다.", List.of("실패 원인 메시지를 확인합니다"));
            };
        }
    }
}
