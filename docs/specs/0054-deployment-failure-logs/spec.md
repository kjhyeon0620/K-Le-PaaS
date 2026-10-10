---
issue: 54
title: 배포 실패 원인 기록과 배포 로그 API 구현
status: done
size: L
type: feat
branch: feat/#54-deployment-failure-logs
---

# 배포 실패 원인 기록과 배포 로그 API 구현

## 목적 / 성공 조건
- 목적: 앱 소유자가 배포 한 건을 열면 실패 원인과, **그 요청이 적용한 Pod**의 최근 로그·Kubernetes 이벤트를 본다. 다른 배포의 Pod 로그를 이 배포의 근거로 보여 주지 않고, 볼 수 없으면 볼 수 없다고 표시한다.
- 기술적 완료: `GET /api/v1/deployments/{id}/logs`가 placeholder 대신 관측값(Pod 상태, 로그, 이벤트)을 돌려준다. rollout 실패 사유에 OOMKilled·readiness 미통과·스케줄 불가처럼 관측한 근거가 들어간다.
- 운영: 사용자는 CLI `deployments logs`와 콘솔 Logs 대화상자에서 같은 정보를 본다. Kubernetes 조회가 실패하거나 Pod가 이미 없으면 빈 정상 결과가 아니라 관측 상태와 이유를 본다.

## 현재 동작 (2026-10-10 코드 확인)
- `DeploymentService.getDeploymentLogs`는 소유권만 검사하고 `["로그 조회 기능은 향후 구현 예정입니다."]`를 반환한다. 응답은 `{deployment_id, logs}`다.
- Pod 로그 조회는 `KubectlService.getPodLogs`(자연어 LOW 명령)에만 있다. 사용자 저장소 label로 Pod를 찾고 앱 이름이 맞는 **첫 Pod**의 로그를 준다. 어느 배포 요청의 Pod인지는 구분하지 않는다.
- 콘솔 Deployments 화면의 Logs 버튼은 `DeploymentLogsDialog`를 연다. `api.getDeploymentPods`·`getDeploymentLogs`가 항상 빈 값을 주는 stub이다 (architecture §9).
- CLI `deployments get`은 이미 `fail_reason`을 보여 준다. 로그 명령은 없다.
- #53 이후 `fail_reason`에는 rollout 결과의 종류와 근거(`ImagePullBackOff`, `CrashLoopBackOff`, 타임아웃, 대체)가 들어간다. 조기 실패는 Pod 대기 사유만 적고, `CrashLoopBackOff`의 마지막 종료 사유(OOMKilled 등)와 타임아웃 시 Pod 상태는 적지 않는다.
- Deployment metadata annotation `klepaas.io/deployment-id`가 현재 spec을 적용한 요청 ID다 (#53).

## 범위
- 포함
  - 배포 로그 API: 이 요청이 적용한 Pod의 상태, 최근 로그(재시작한 컨테이너는 직전 로그 포함), 관련 이벤트, 관측 상태
  - rollout 실패 사유 보강: `CrashLoopBackOff`의 마지막 종료 사유·exit code, 타임아웃 시 관측한 Pod 상태(스케줄 불가, 컨테이너 실행 중이나 Ready 아님)
  - CLI `deployments logs <id> [--lines <n>]`, `--json`
  - 콘솔 `DeploymentLogsDialog`를 이 API에 연결하고 실패 원인을 표시 (기존 대화상자 교체, 새 화면 없음)
  - 단위·컨트롤러 테스트, 실제 k3s 확인
- 제외
  - 로그 스트리밍, 로그 저장·장기 보관 (조회 시점의 클러스터 관측값만 사용)
  - 빌드(Kaniko) 로그
  - 배포 요청 상세 화면, AI 진단, MCP, RAG
  - #57 재시작 후 대조, 앱별 rollout 타임아웃
  - 자연어 `logs` 명령 변경 (배포 ID로 조회하는 경로가 placeholder를 쓰던 부분만 새 응답에 맞춤, 아래 Decisions)
  - apply 실패 시 `fail_reason`의 Fabric8 예외 메시지 정리 (범위 밖 이슈 후보)

## 입출력·계약
- API `GET /api/v1/deployments/{id}/logs?lines=<n>` (`lines` 기본 100, 1~200. 범위 밖은 400 `COMMON_003`)

```json
{
  "deployment_id": 42,
  "status": "FAILED",
  "fail_reason": "rollout 실패: ImagePullBackOff (pod=app-6d..., generation=5): Back-off pulling image ...",
  "observation": "AVAILABLE",
  "observation_message": null,
  "applied_deployment_id": 42,
  "pods": [
    {
      "name": "app-6d...-x1",
      "phase": "Pending",
      "ready": false,
      "restart_count": 0,
      "state": "waiting",
      "state_reason": "ImagePullBackOff",
      "state_message": "Back-off pulling image ...",
      "last_termination_reason": null,
      "last_exit_code": null,
      "logs": [],
      "previous_logs": null,
      "logs_error": "container is waiting to start"
    }
  ],
  "events": [
    { "type": "Warning", "reason": "Failed", "message": "Failed to pull image ...",
      "object": "Pod/app-6d...-x1", "count": 3, "last_seen": "2026-10-10T01:02:03Z" }
  ]
}
```

| `observation` | 조건 | `pods`·`events` |
|---|---|---|
| `AVAILABLE` | 클러스터 Deployment의 `klepaas.io/deployment-id`가 이 배포 ID | 현재 revision ReplicaSet의 Pod(최대 5개)와 그 Pod·ReplicaSet의 이벤트 |
| `NOT_CURRENT` | Deployment가 없거나, annotation이 없거나 다른 ID (다른 요청이 적용, #53 이전 배포, apply 전에 끝난 배포) | 빈 배열. `observation_message`에 이유, `applied_deployment_id`에 현재 적용 요청 ID(없으면 null) |
| `UNAVAILABLE` | Kubernetes 조회 실패 | 빈 배열. `observation_message`에 오류 요약 |

- Pod 하나의 로그 조회가 실패하면 그 Pod의 `logs_error`에만 적고 나머지는 돌려준다.
- `previous_logs`: `restart_count > 0`이면 직전 컨테이너 로그(같은 `lines`), 아니면 null.
- 이벤트: Kubernetes 보존 기간(기본 1시간) 안의 것만 있다. 최근 순 최대 20개.
- 시각은 UTC `Z` (#81).
- 기존 `logs` 필드는 제거한다 (placeholder만 담았고 이를 쓰는 클라이언트가 없음).
- rollout 실패 사유 보강 (`fail_reason` 문구만 바뀐다)
  - `CrashLoopBackOff`: `… CrashLoopBackOff (pod=…, generation=G, 마지막 종료=OOMKilled, exit=137): …`
  - 타임아웃: `rollout 타임아웃(120초, generation=G): observedGeneration=…, updated=…, available=…, unavailable=…; pod=… 스케줄 불가: <message>` 또는 `pod=… 실행 중이나 Ready 아님`
- CLI `klepaas deployments logs <id> [--lines <n>]`
  - 사람용: 상태·실패 원인·관측 상태, Pod별 상태와 로그, 이벤트
  - `--json`: API `data` 그대로
  - exit code: 조회 성공 0 (관측 상태와 무관), 입력 오류 1, 인증 2, API 오류 3
- 저장: 스키마 변경 없음 (Q1)

## 권한·안전
- 호출 주체와 scope: GET이므로 `READ_ONLY`·`PROPOSE_ONLY`·`FULL`. `DEPLOY` 토큰·GitHub Actions OIDC는 기존 규칙대로 403. `SecurityConfig` 변경 없음.
- 소유권: `ResourceAccessService.requireDeployment`가 먼저 실행된다. 다른 사용자 배포는 404이며 Kubernetes 호출은 0회다.
- Kubernetes 조회 범위: 설정 namespace, 이름 `owner-repo` Deployment, 그 Deployment의 `klepaas.io/repository-id`가 배포 저장소와 같을 때만. ReplicaSet·Pod는 Deployment 소유·revision으로 한정, 이벤트는 해당 Pod·ReplicaSet 이름으로 한정한다.
- 비밀값: 로그는 저장하지 않고 조회 시 소유자에게만 보여 준다 (Q3). 이벤트·상태 메시지에는 Secret 값이 들어가지 않는다.
- 운영 호스트 정보: Kubernetes 조회 실패 메시지(`logs_error`, `UNAVAILABLE`)에는 Kubernetes가 돌려준 status 메시지만 넣는다. Fabric8 예외 메시지에는 API 서버 주소가 들어 있어 서버 로그에만 남긴다.

## 제약
- 참조: [architecture §3.2, §4.2, §6.1, §9](../../architecture.md), [ADR-0006](../../adr/0006-resource-access-scope.md), [0053](../0053-request-scoped-rollout/spec.md)
- Pod를 이 요청에 귀속하는 근거는 annotation뿐이다. 이름·이미지로 추정하지 않는다.
- 콘솔은 [`frontend/DESIGN.md`](../../../frontend/DESIGN.md)의 기존 Dialog·Card·Badge·Button 구성을 쓴다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 잘못된 `lines` | 400 `COMMON_003` | Kubernetes 호출 없음 |
| 다른 사용자 배포 | 404 | Kubernetes 호출 0회 |
| 진행 중 배포 | `AVAILABLE`, 현재 Pod 상태·로그 | 같은 조회 |
| 뒤에 다른 배포가 적용됨 | `NOT_CURRENT`, "현재 Deployment는 배포 #N이 적용" | 이전 Pod 로그를 보여 주지 않음 |
| #53 이전 배포, apply 전에 실패한 배포(빌드 실패 등) | `NOT_CURRENT` + `fail_reason` | |
| 컨테이너가 아직 시작 전 (`ImagePullBackOff` 등) | Pod `state_reason`, `logs_error`, 이벤트 | |
| 반복 종료 | `previous_logs`, `last_termination_reason` | |
| Kubernetes 조회 실패 | `UNAVAILABLE` + 오류 요약 | 200 응답. 빈 정상 결과로 보이지 않게 관측 상태로 구분 |
| 같은 설정 재배포로 ReplicaSet 재사용 | `AVAILABLE`, 이전 요청과 같은 Pod | Pod template이 같으면 같은 Pod가 이 요청의 spec을 실행한다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-10 등급 L. backend API·CLI·콘솔에 걸친다.
- 2026-10-10 Q1 실패 원인 → 구조화 필드 없이 `fail_reason` 문구에 관측 근거를 보강한다. 마이그레이션 없음. (#53 형식이 이미 사유 이름을 담고, 구조화 코드는 쓰는 곳이 생길 때 추가)
- 2026-10-10 Q2 이 요청이 적용하지 않은 Deployment → 로그·이벤트를 보여 주지 않고 `NOT_CURRENT`와 현재 적용 요청 ID를 준다. (다른 요청의 Pod를 이 배포의 근거로 보이지 않게)
- 2026-10-10 Q3 로그 마스킹 → 이번에는 하지 않는다. 로그는 저장하지 않고 소유자 조회 시에만 보여 준다(기존 자연어 logs와 같은 수준). AI 입력으로 넘기는 이슈에서 다룬다.
- 2026-10-10 Q4 CLI → `deployments logs <id> [--lines <n>]`를 추가한다. 조회 성공은 관측 상태와 무관하게 exit 0.
- 2026-10-10 `lines` 기본 100, 최대 200 (자연어 logs 상한과 같음). Pod 최대 5개, 이벤트 최대 20개로 가정.
- 2026-10-10 Kubernetes 조회 실패는 5xx가 아니라 200 + `UNAVAILABLE`로 둔다. 배포 기록(상태·`fail_reason`)은 조회할 수 있어야 하기 때문이다.
- 2026-10-10 (구현 중) 자연어 LOGS 의도에 `deployment_id`가 있으면 `ActionDispatcher.executeLogs`가 이 placeholder를 로그처럼 돌려주고 있었다 → 같은 서비스의 새 응답에서 첫 Pod 로그를 쓰고, 관측 상태·실패 원인을 metadata에 넣는다. 출력 형식(`logs`)은 유지한다. (placeholder 제거가 완료 조건이고 같은 메서드를 호출한다)
- 2026-10-10 (구현 중) 실제 k3s에서 Fabric8 예외 메시지가 Kubernetes API 서버 URL을 포함함을 확인 → 사용자 메시지에는 Kubernetes status 메시지만, 없으면 "Kubernetes API 오류 (HTTP n)"/"연결하지 못했습니다"를 쓴다. (운영 호스트 정보 비노출)
- 2026-10-10 (구현 중) Pod 상태가 아직 없으면(스케줄 전) `logs_error`에 "컨테이너 상태가 아직 없습니다"를 적고 로그를 조회하지 않는다.
- 2026-10-10 (구현 중) 이벤트 정렬은 `last_seen`을 시각으로 해석해 최근 순으로 한다 (`eventTime`은 마이크로초 형식이라 문자열 정렬이 맞지 않음).

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 관측 상태 분기(다른 요청·기록 없음·Deployment 없음·다른 저장소 → `NOT_CURRENT`, 조회 실패 → `UNAVAILABLE`), 현재 revision ReplicaSet Pod만 읽기, 로그·직전 로그·이벤트 매핑, Pod 단위 로그 실패, API 주소 비노출 | `DeploymentObservationReaderTest` | 로컬 | 통과 |
| 다른 사용자(JWT·CLI) 404와 Kubernetes 조회 0회, `lines` 범위 밖 400, 소유자 조회 | `Stage2HttpAccessTest` | 로컬 (Testcontainers PostgreSQL) | 통과 |
| OOMKilled 등 마지막 종료 사유, 타임아웃 시 스케줄 불가·Ready 아님 | `KubernetesManifestGeneratorTest` | 로컬 | 통과 |
| 정상 배포: 이 요청 Pod 로그 20줄·이벤트 | 임시 확인 테스트 `LocalK3sObservationCheckTest` | 실제 k3s v1.31.4 (docker 임시 컨테이너) | 통과 |
| 없는 이미지: Pod `ImagePullBackOff`, `logs_error`(API 주소 없음), `Failed` 이벤트(이미지 not found), 앞 요청은 `NOT_CURRENT`(applied=뒤 요청) | 같은 절차 | 실제 k3s | 통과 |
| 반복 종료: `fail_reason`에 `마지막 종료=Completed, exit=0`, `previous_logs`, `last_termination_reason` | 같은 절차 (busybox) | 실제 k3s | 통과 |
| readiness 실패: 타임아웃 사유 `실행 중이나 Ready 아님`, 이벤트 `Unhealthy … statuscode: 404` | 같은 절차 | 실제 k3s | 통과 |
| 스케줄 불가: 타임아웃 사유 `스케줄 불가: 0/1 nodes are available: 1 Insufficient memory…`, `FailedScheduling` 이벤트 | 메모리 100Gi 요청 | 실제 k3s | 통과 |
| CLI 사람용 출력·`--json`·`NOT_CURRENT` 표시·`--lines 500` exit 1 | 로컬 mock API에 CLI 실행 | 로컬 | 통과 |
| 콘솔 타입·빌드 | `npx tsc --noEmit -p .`, `npm run build` | 로컬 | 통과 |
| 콘솔 화면 실제 조작 | - | - | 하지 않음 (로그인·백엔드 기동 필요. 머지 후 운영 콘솔에서 확인) |
| 백엔드 회귀 | `./gradlew test` (207개) | 로컬 (Testcontainers PostgreSQL) | 통과 |

- 원자료: `.local/evidence/0054-deployment-failure-logs/2026-10-10/` (확인 코드와 출력). k3s 컨테이너·이미지와 mock 서버는 확인 후 삭제했다.
- 운영 클러스터·운영 DB 검증은 하지 않았다. 스키마 변경이 없다.

## Tasks
### T1. 백엔드 조회 (reader + API)
- 먼저 읽을 것: `KubernetesManifestGenerator`(새 ReplicaSet 찾기), `ResourceAccessService`, `DeploymentController`
- 변경: Kubernetes 조회 클래스(infra), `DeploymentLogResponse` 교체, `DeploymentService.getDeploymentLogs`, 컨트롤러 `lines` 파라미터
- 금지: 소유권 검사 전 Kubernetes 호출, 이름·이미지 기반 Pod 추정
- 검증: 단위·컨트롤러 테스트
### T2. rollout 실패 사유 보강
- 변경: `evaluatePods`(마지막 종료 사유), 타임아웃 메시지(Pod 상태)
- 검증: `KubernetesManifestGeneratorTest`
### T3. CLI `deployments logs`
- 변경: `frontend/cli/index.mjs`, `docs/CLI_REFERENCE.md`
- 검증: CLI 실행
### T4. 콘솔 Logs 대화상자
- 변경: `frontend/lib/api.ts`(stub 제거), `components/deployment-logs-dialog.tsx`, 호출부(배포 ID 전달)
- 검증: `npm run build`, 화면 확인
### T5. 실제 k3s 확인과 문서 동기화
- 변경: architecture(§3.2, §9 로그 격차 삭제), `backend/README.md`, `README.md`, `ORACLE_K3S_GHCR_DEPLOYMENT.md`, `product.md`

## 운영 반영
- 2026-10-10T03:49:38Z PR #85 머지(4807f7e), main `Build and deploy` run 38021923170: build·deploy success. 마이그레이션 없음.
- 운영 콘솔 Logs 확인: #53 이전에 적용된 IoT 배포 #3은 `NOT_CURRENT`("현재 Deployment에 적용한 배포 요청 기록이 없습니다"), Pod·이벤트 없음. 의도한 동작이다. 이 배포 요청이 적용한 Pod의 로그 표시는 다음 사용자 앱 배포 후 확인한다.
- 같은 확인 중 콘솔 Restart가 500으로 실패하는 기존 결함을 발견해 #86으로 처리했다 (`managedFields` 직렬화 오류, #54 변경과 무관).

## 회고
- 어긋난 점: (1) 자연어 LOGS의 배포 ID 경로가 같은 placeholder를 쓰고 있다는 사실을 스펙 초안에서 놓쳤다(컴파일 단계에서 발견). (2) Fabric8 예외 메시지에 API 서버 주소가 들어간다는 점을 실제 클러스터 확인 전에는 몰랐다. 같은 메시지가 apply 실패 `fail_reason`에도 이미 들어가고 있다.
- 원인 분류: 스펙 누락 (호출부 조사), 외부 제약 미인지 (클라이언트 예외 형식)
- 고친 위치: 호출부는 컴파일러가 잡으므로 별도 장치는 두지 않는다. 사용자 메시지 정리는 `DeploymentObservationReader.clientMessage`로 한 곳에 두고 단위 테스트로 고정했다. apply 실패 메시지는 [architecture §9](../../architecture.md#9-알려진-격차)에 이슈 후보로 기록했다.
