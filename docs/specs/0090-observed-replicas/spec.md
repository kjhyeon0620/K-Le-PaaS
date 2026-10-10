---
issue: 90
title: 스케일 대화상자가 설정값을 현재 레플리카로 표시해 되돌릴 수 없음
status: done
size: M
type: fix
branch: fix/#90-observed-replicas
---

# 스케일 대화상자가 설정값을 현재 레플리카로 표시해 되돌릴 수 없음

## 목적 / 성공 조건
- 목적: Scale 대화상자의 "현재 레플리카"가 저장된 설정값이 아니라 클러스터에서 관측한 값이다. 실제 2개인 앱을 1개로 되돌릴 수 있다.
- 기술적 완료: 백엔드가 저장소 앱의 현재 Deployment replica(원하는 수·ready·available·updated)를 조회 API로 준다. 콘솔이 그 값으로 표시·검증한다.
- 운영: 관측할 수 없으면 "확인 불가"와 이유를 보고, 그래도 1~10 범위로 스케일할 수 있다.

## 현재 동작 (2026-10-10 코드 확인)
- `deployment-status-monitoring.tsx`가 `currentReplicas={deploymentConfigs[...]?.replica_count}`를 넘긴다. `replica_count`는 백엔드 응답에 없는 필드이고, `normalizeDeploymentConfigResponse`가 `max_replicas`로 채운다. 설정값을 현재 값처럼 표시한다 (architecture 원칙 위반).
- `ScaleDialog`는 목표 == 현재면 실행 버튼을 막는다. 범위 안내는 0~10, 0 경고가 있다.
- 백엔드 scale(`POST /deployments/{id}/scale`)은 1 미만을 400으로 거부하고, 실제 Deployment의 `spec.replicas`를 읽어 바꾼다. 상한은 없다.
- 실제 replica를 주는 사용자 API는 없다 (자연어 조회 `KubectlService`에만 있음).
- 배포는 설정 `min_replicas`로 replica를 다시 쓰므로, 스케일 값은 다음 배포 때 바뀐다.

## 범위
- 포함
  - `GET /api/v1/repositories/{repositoryId}/replicas` (관측값)
  - `ScaleDialog`가 관측값을 표시하고, 관측 불가면 "확인 불가", 범위 1~10, 다음 배포 시 설정값으로 바뀐다는 안내
  - `deployment-status-monitoring.tsx`의 `replica_count` 전달 제거
- 제외
  - scale API 변경(상한, 설정 동기화), CLI 변경, 다른 화면의 replica 표시 (`api.ts`의 `replica_count` 정규화는 설정 편집 화면이 쓰므로 유지)
  - Deployment 상태 자동 갱신(polling)

## 입출력·계약
- API `GET /api/v1/repositories/{repositoryId}/replicas`

```json
{
  "repository_id": 1,
  "observation": "AVAILABLE",
  "observation_message": null,
  "desired": 2,
  "ready": 2,
  "available": 2,
  "updated": 2
}
```

| `observation` | 조건 | 숫자 필드 |
|---|---|---|
| `AVAILABLE` | 이 저장소 라벨이 붙은 Deployment를 읽음 | `desired`=`spec.replicas`(없으면 Kubernetes 기본값 1), `ready`·`available`·`updated`=status 값(없으면 0) |
| `NOT_FOUND` | Deployment가 없거나 다른 저장소 소유 | null, `observation_message`에 이유 |
| `UNAVAILABLE` | Kubernetes 조회 실패 | null, `observation_message`에 Kubernetes status 메시지(API 서버 주소 제외, #54 `clientMessage`) |

- 에러: 다른 사용자 저장소 404 (`requireRepository`), Kubernetes 호출 0회.
- 저장: 없음.

## 권한·안전
- GET이므로 `READ_ONLY`·`PROPOSE_ONLY`·`FULL`. `SecurityConfig` 변경 없음. `DEPLOY`·GitHub Actions OIDC는 기존 규칙대로 403.
- 소유권: `ResourceAccessService.requireRepository` 후 설정 namespace의 `owner-repo` Deployment를 읽고 `klepaas.io/repository-id` 라벨을 확인한다.
- 비밀값 없음.

## 제약
- 참조: [architecture §3.2, §6.1](../../architecture.md), [0054](../0054-deployment-failure-logs/spec.md)
- 관측값만 표시한다. 조회 실패를 설정값이나 1로 채우지 않는다.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| 다른 사용자 저장소 | 404 | Kubernetes 호출 0회 |
| Deployment 없음(배포 전) | 대화상자 "확인 불가: 클러스터에 Deployment가 없습니다" | scale도 기존대로 404 |
| Kubernetes 조회 실패 | "확인 불가" + 이유. 1~10 입력 가능 | 같은 값 차단 없음 |
| 관측값과 같은 목표 | 실행 버튼 비활성 | 변경 없음 |
| 스케일 직후 | 대화상자를 다시 열면 새 desired, ready는 Pod 준비에 따라 늦게 오른다 | 조회 시점 값 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-10 등급 M. API 하나 추가, 상태 표시 변경. 사용자가 프론트만 고치는 S 대신 실제 값 조회를 선택했다.
- 2026-10-10 기본값: 경로는 저장소 단위 `GET /repositories/{id}/replicas`. replica는 앱(저장소)의 현재 Deployment 값이고 특정 배포 요청의 값이 아니다. (기존 `scaling-history`와 같은 위치)
- 2026-10-10 기본값: 대화상자 입력 범위 1~10. 백엔드 최소 1과 맞추고, 상한 10은 기존 UI 값을 유지한다(백엔드 상한 추가는 범위 밖).
- 2026-10-10 기본값: 관측 불가면 같은 값 차단 없이 1~10 스케일을 허용한다. 실제 값을 모르는 상태에서 차단하면 이번 결함과 같은 상황이 된다.
- 2026-10-10 기본값: 대화상자에 "다음 배포 때 설정값(min_replicas)으로 바뀝니다"를 안내한다. 동작 변경 없이 사실만 알린다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 관측값 매핑(`spec.replicas` 없으면 1, status 없으면 0), NOT_FOUND(없음·다른 저장소), UNAVAILABLE에서 숫자 null, API 주소 비노출 | `DeploymentObservationReaderTest` | 로컬 | 통과 |
| 소유자 조회, 다른 사용자(JWT·CLI 토큰) 404, Kubernetes 조회는 소유자 1회뿐 | `Stage2HttpAccessTest` | 로컬 (Testcontainers PostgreSQL) | 통과 |
| 대화상자: 설정값(1)이 아닌 관측값 2 표시, Ready·Available·Updated 표시, 목표 1 입력 시 "Scale Down 2→1"과 실행 활성, `POST /deployments/4/scale` 호출·완료 토스트 | mock API + Next dev, 브라우저 | 로컬 | 통과 |
| 관측 불가: "확인 불가"와 이유 표시, 최소 1로 입력·실행 가능 | 같은 절차 (mock UNAVAILABLE) | 로컬 | 통과 |
| 회귀 | `./gradlew test` (210개), `npx tsc --noEmit -p .` | 로컬 | 통과 |
| 실제 Deployment replica 관측 | - | - | 하지 않음. 읽는 값이 Deployment `spec`·`status`뿐이고 같은 reader의 Deployment 조회는 #54에서 실제 k3s로 확인했다. 운영 콘솔에서 확인한다 |

## 운영 반영
- 2026-10-10T06:46:15Z PR #91 머지(9ea2459), main `Build and deploy` run 38032088536: build·deploy success. 마이그레이션 없음.
- 사용자가 운영 콘솔에서 Scale 대화상자의 실제 레플리카 표시와 축소 동작을 확인했다. 같은 시점에 #86 Restart, #88 Logs 대화상자도 확인했다 (#55 PR 시점).

## 회고
- 어긋난 점: 콘솔이 백엔드 응답에 없는 `replica_count`를 정규화 함수에서 설정값(`max_replicas`)으로 채워 "현재" 값처럼 표시했다. #78(콘솔·백엔드 응답 불일치)에서도 걸러지지 않았다. 대화상자 범위(0~10)도 백엔드 규칙(1 이상)과 달랐다.
- 원인 분류: 규칙 미전달(관측값과 저장값 구분 원칙이 프론트 정규화 코드에 적용되지 않음), 테스트 부재(실제 클러스터 scale을 한 번도 하지 않음)
- 고친 위치: 대화상자는 관측 API만 쓰고 관측 불가를 그대로 표시한다. 같은 정규화의 `replica_count`는 설정 편집 화면이 쓰므로 남겼다(설정값 의미). 새 검증 장치는 추가하지 않는다.
