# 로드맵

> Phase 1~5 완료 기준 (2026-03-05)

---

## Phase 6 — 핵심 미완성 기능 완성

가장 가시적인 효과를 낼 수 있는 항목들. 프론트엔드 화면은 이미 준비되어 있어 백엔드 구현만으로 동작 가능.

### 6-1. Slack 알림 (`SlackNotificationService`)

`NotificationService` 인터페이스 구현체 작성.

```java
// 구현 위치: global/service/SlackNotificationService.java
notifyDeploymentStarted(deployment)  // 배포 시작 시
notifyDeploymentSuccess(deployment)  // 배포 성공 시
notifyDeploymentFailed(deployment, reason)  // 배포 실패 시
```

- Slack Incoming Webhook 방식 우선 (OAuth는 설정 복잡도 높음)
- `DeploymentPipelineService`의 각 단계에서 이미 호출 포인트 준비됨
- 환경변수: `SLACK_WEBHOOK_URL`

### 6-2. WebSocket 배포 상태 스트리밍

배포 단계가 바뀔 때마다 프론트로 실시간 push. 현재 프론트 `use-global-websocket.ts`가 연결 대기 중.

```
PENDING → UPLOADING_SOURCE → BUILDING → DEPLOYING → SUCCESS/FAILED
```

- Spring WebSocket + SseEmitter 또는 STOMP over WebSocket
- `DeploymentPipelineService` 각 단계에서 상태 변경 이벤트 publish
- 엔드포인트: `ws://host/ws/deployments/{deploymentId}`

### 6-3. 실제 배포 로그 조회

Kaniko Job Pod 로그를 K8s API로 스트리밍.

```java
// DeploymentService.getDeploymentLogs()의 TODO 구현
kubernetesClient.pods()
    .inNamespace(namespace)
    .withLabel("klepaas.io/deployment-id", deploymentId)
    .watchLog(...)
```

- 빌드 로그: `klepaas-build-{deploymentId}` Job Pod
- 앱 로그: `{owner}-{repoName}` Deployment Pod

### 6-4. CLI 기반 제어면 초안

웹 콘솔을 대체하지 않는 보조 제어면으로 CLI를 도입한다. 상세 방향은 [`docs/CLI_STRATEGY.md`](CLI_STRATEGY.md) 참조.

- 1차 목표: `auth`, `ask`, `confirm`, `history`, `deployments`
- 비용 MVP: `cost plan`, `cost diff`, `cost explain`, `cost check`
- 공통 계약: `--json`, `--quiet`, 종료 코드 표준화
- 원칙: 기존 백엔드 API/NLP 엔진을 재사용하는 thin client로 시작

---

## Phase 7 — GitOps (자동 배포)

GitHub Webhook 수신 → PR merge 시 자동 배포 트리거.

```
GitHub Push/PR merge 이벤트
  → POST /api/v1/webhooks/github (HMAC-SHA256 서명 검증)
  → 브랜치 필터링 (main/master만)
  → DeploymentService.createDeployment() 자동 호출
```

**필요 작업:**
- Webhook 엔드포인트 구현 (`cicd/` 패키지 신설)
- `SourceRepository`에 `webhookEnabled`, `webhookSecret` 필드 추가
- GitHub App Webhook 등록 API 연동
- 프론트 `github-integration-panel.tsx`의 자동 배포 토글 활성화

---

## Phase 8 — 실시간 리소스 모니터링

현재 모니터링 화면 UI는 완성되어 있으나 실제 데이터 없음.

### 8-1. K8s Metrics Server 연동

```java
// fabric8 Metrics API
kubernetesClient.top().nodes().metrics()
kubernetesClient.top().pods().inNamespace(ns).metrics()
```

엔드포인트 예시:
```
GET /api/v1/monitoring/cluster/overview    # 노드별 CPU/메모리
GET /api/v1/monitoring/pods/{namespace}    # 파드별 리소스 사용량
```

### 8-2. Prometheus 연동 (선택)

Prometheus가 배포되어 있을 경우 PromQL 쿼리 직접 실행.
- `GET /api/prometheus/query?q={promql}`

---

## Phase 9 — 배포 품질 개선

### 9-1. 이미지 태그 commit SHA 적용

현재 `:latest` 고정 → 롤백 시 이전 이미지가 없어 정확한 버전 복원 불가.

```java
// 변경: NcpInfraService.triggerBuild()
String imageUri = registryEndpoint + "/" + imageName + ":" + shortSha;
//                                                      ↑ latest → commit SHA
```

### 9-2. HPA (Horizontal Pod Autoscaler)

CPU 임계값 기반 자동 스케일링.

```java
// KubernetesManifestGenerator에 추가
kubernetesClient.autoscaling().v2().horizontalPodAutoscalers()
    .inNamespace(namespace)
    .resource(buildHpa(appName, config))
    .serverSideApply();
```

`DeploymentConfig`에 `hpaEnabled`, `minReplicas`, `maxReplicas`, `targetCpuPercent` 필드 추가 필요.

### 9-3. 배포 diff 시각화

배포 전후 이미지 태그, 레플리카 수, 환경변수 변경사항 비교 API.

---

## Phase 10 — 멀티 클라우드 + 엔터프라이즈

### 10-1. AWS 인프라 프로바이더

`CloudInfraProvider` 인터페이스 구현체 추가. 인터페이스는 이미 준비됨.

```java
@Service("awsInfraService")
public class AwsInfraService implements CloudInfraProvider {
    // S3 업로드, ECR push, EKS 배포
}
```

### 10-2. 환경 분리 (dev/staging/prod)

K8s 네임스페이스별 환경 관리.

```
SourceRepository.environment = "dev" | "staging" | "prod"
  → K8s namespace: klepaas-dev / klepaas-staging / klepaas-prod
```

### 10-3. 감사 로그 (AuditLog)

배포/스케일/롤백 등 모든 운영 행위 기록.

| 필드 | 내용 |
|------|------|
| `userId` | 실행한 사용자 |
| `action` | DEPLOY / SCALE / ROLLBACK / RESTART |
| `resourceType` | DEPLOYMENT / POD |
| `result` | SUCCESS / FAILED |
| `timestamp` | 실행 시각 |

### 10-4. Rate Limiting

인증 엔드포인트 및 AI 명령 엔드포인트 요청 제한.
- `POST /api/v1/nlp/command` — 사용자당 분당 20회
- `POST /api/v1/auth/oauth2/login` — IP당 분당 10회

---

## 기술 부채 / 코드 품질

| 항목 | 내용 |
|------|------|
| **테스트 코드** | 현재 전무. NLP 파싱, 파이프라인 서비스 단위 테스트 우선 작성 |
| **prod DB 전환** | H2(개발) → PostgreSQL(운영) 전환 시 `ddl-auto: validate`로 변경 필요 |
| **CORS 설정** | 현재 모든 origin 허용 → 운영 시 특정 도메인으로 제한 |
| **JWT Refresh Token 순환** | 리프레시 토큰 재사용 방지 (발급 시 기존 토큰 무효화) |
| **NCP 인프라 구성 문서화** | `k8s/` 디렉토리에 Secret, StorageClass 등 사전 준비 매니페스트 추가 |
