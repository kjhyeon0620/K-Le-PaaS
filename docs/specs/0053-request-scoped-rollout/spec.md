---
issue: 53
title: 배포 요청 단위 rollout 성공·실패 판정
status: done
size: M
type: fix
branch: fix/#53-request-scoped-rollout
---

# 배포 요청 단위 rollout 성공·실패 판정

## 목적 / 성공 조건
- 목적: 배포 요청의 결과를 **그 요청이 적용한 Deployment spec**의 rollout으로만 판정한다. 다른 배포의 완료를 이 요청의 성공으로 기록하지 않고, 회복될 가능성이 낮은 실패는 타임아웃까지 기다리지 않는다.
- 기술적 완료: apply 응답의 `metadata.generation`을 기준으로 `observedGeneration`, updated·available replica를 확인한다. 결과를 성공, 실패, 대체, 타임아웃으로 구분해 기록한다.
- 운영: 사용자는 배포 상태(`SUCCESS`/`FAILED`/`CANCELED`)와 `fail_reason`에서 실패 종류와 근거(Pod 이름, 대기 사유, generation)를 본다. 실패·중단 시에도 이 요청이 적용한 이미지와 Deployment 식별 정보가 남아 #57이 대조에 쓸 수 있다.

## 현재 동작 (2026-10-10 코드 확인)
- `KubernetesManifestGenerator.waitForDeploymentAvailable(appName)`은 매번 앱 이름으로 **현재** Deployment를 읽고 `observedGeneration >= generation`과 replica 카운터만 본다. 대기 중 다른 apply·restart·scale로 generation이 바뀌어도 그 rollout이 끝나면 성공을 반환한다.
- 실패 조건이 없다. 이미지 pull 실패, 컨테이너 반복 종료도 `kubernetes.rollout.timeout-ms`(기본 120초, `application.yaml`에서 덮어쓰지 않음)까지 기다린다.
- manifest는 `progressDeadlineSeconds`를 지정하지 않아 Kubernetes 기본값 600초가 적용된다. 120초 대기 안에서는 `Progressing=False(ProgressDeadlineExceeded)`가 거의 나타나지 않는다. 다른 경로로 600초 넘게 멈춰 있던 Deployment에서만 관측될 수 있어, 판정 규칙에는 남기되 조기 실패는 Pod 대기 사유로 판정한다.
- `DeploymentPipelineStepService.executeK8sDeploy`는 `DEPLOYING` 전이, `image_uri` 저장, apply, rollout 대기를 **한 트랜잭션**(REQUIRES_NEW)에서 한다. 대기가 실패하면 롤백되어 `DEPLOYING`과 KANIKO 경로의 `image_uri`가 남지 않는다. 대기 동안 DB에는 `BUILDING`으로 보이고 DB 연결을 최대 120초 점유한다.
- 같은 저장소의 배포는 #52로 직렬화되지만 scale·restart, 직접 `kubectl` 변경, 진행 중 판정 창을 넘긴 배포는 직렬화 대상이 아니다. 대체 판정이 필요한 경로다.

## 범위
- 포함
  - apply 결과의 generation과 이미지를 요청 단위 대기 대상으로 사용
  - 결과 판정: 성공 / 명확한 실패 / 다른 변경으로 대체 / 타임아웃
  - 새 ReplicaSet의 Pod 대기 사유로 명확한 실패를 조기 판정
  - `executeK8sDeploy` 트랜잭션 분리: apply 전에 `DEPLOYING`·`image_uri`를 커밋하고, 대기는 트랜잭션 밖에서 실행
  - 판정 로직 단위 테스트, 실제 k3s(임시 컨테이너) 확인
- 제외
  - #57 재시작 후 대조 자체, 멈춘 배포 상태 정리
  - 배포 로그·실패 원인 API(#54), 새 화면, CLI 변경, AI·MCP·RAG
  - scale·restart 직렬화, 앱별 rollout 타임아웃, `progressDeadlineSeconds` 설정
  - #55 Node 샘플 앱 (이번 확인은 공개 이미지로 시나리오를 재현)

## 입출력·계약
- API: 변경 없음. 기존 `status`, `fail_reason` 값만 달라진다.
- 상태 전이: `DEPLOYING → SUCCESS | FAILED | CANCELED`

| 판정 | 조건 | 상태 | `fail_reason` 형식 |
|---|---|---|---|
| 성공 | Deployment `generation == G`, `observedGeneration >= G`, `Available=True`, updated = available = desired, unavailable = 0 | `SUCCESS` | - |
| 명확한 실패 | `observedGeneration >= G`이고 `Progressing=False`(`ProgressDeadlineExceeded`), 또는 새 ReplicaSet Pod의 컨테이너 대기 사유가 `ImagePullBackOff`, `InvalidImageName`, `ErrImageNeverPull`, `CreateContainerConfigError`, `CrashLoopBackOff` 중 하나 | `FAILED` | `rollout 실패: <사유> (pod=<이름>, generation=G): <kubelet 메시지>` |
| 대체 | Deployment `generation != G` (다른 apply·restart·scale, 삭제 후 재생성) | `CANCELED` | `다른 변경으로 대체됨: generation G → <현재>` |
| 삭제 | Deployment가 없음 | `FAILED` | `rollout 실패: Deployment를 찾을 수 없음 (generation=G)` |
| 타임아웃 | `kubernetes.rollout.timeout-ms` 안에 위 판정이 없음 | `FAILED` | `rollout 타임아웃(<초>초, generation=G): updated=…, available=…, unavailable=…` |

- G는 apply(create 또는 replace) 응답의 `metadata.generation`이다. 전체 교체(PUT)는 설정이 같아도 generation을 올린다(k3s 확인). Pod template이 같으면 새 ReplicaSet 없이 바로 완료로 관측된다.
- 새 ReplicaSet: Deployment가 소유하고 `deployment.kubernetes.io/revision`이 Deployment의 같은 annotation과 같은 ReplicaSet. Pod는 그 ReplicaSet의 `pod-template-hash` label로 찾는다.
- Kubernetes: Deployment `metadata.annotations["klepaas.io/deployment-id"]`에 요청 ID를 쓴다. Pod template은 바꾸지 않으므로 같은 설정 재배포가 새 rollout을 만들지 않는다.
- 저장: 스키마 변경 없음. `DEPLOYING`과 `image_uri`는 apply 전에 별도 트랜잭션으로 커밋한다.
- 설정: 새 키 없음. `kubernetes.rollout.timeout-ms`(120초), `poll-interval-ms`(2초) 유지.

## 권한·안전
- 호출 주체: 비동기 파이프라인. 사용자 요청 경로·scope 변경 없음.
- Kubernetes 조회는 설정 namespace 안에서 앱 label로 한정한다. 추가 조회는 ReplicaSet·Pod `list`(같은 kubeconfig, 이미 Pod 조회 사용 중).
- 비밀값: kubelet 대기 메시지에는 이미지 주소·레지스트리 응답이 들어갈 수 있다. 자격 증명은 포함되지 않지만 `fail_reason`에 넣는 메시지는 300자로 자른다.

## 제약
- 참조: [architecture §3.2, §5, §9](../../architecture.md), [ADR-0006](../../adr/0006-resource-access-scope.md), [0051](../0051-app-probes-resources/spec.md), [0052](../0052-serialize-deployments/spec.md)
- 판정은 관측값만 쓴다. 상태를 읽을 수 없으면(조회 예외가 계속되면) 성공으로 두지 않고 타임아웃·실패로 기록한다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 같은 설정 재배포 | 이미 정상이면 즉시 `SUCCESS` | generation은 오르지만 template이 같아 새 Pod 없이 완료 |
| 대기 중 restart·scale·다른 apply | 대체 판정 | 다른 rollout의 결과를 이 요청에 기록하지 않음 |
| 존재하지 않는 이미지 | 타임아웃 전 `FAILED`, 사유 `ImagePullBackOff` | Pod 대기 사유로 판정 |
| 컨테이너 반복 종료 (필수 env 누락 등) | 타임아웃 전 `FAILED`, 사유 `CrashLoopBackOff` | Pod 대기 사유로 판정 |
| readiness 실패 | 타임아웃 `FAILED` (기존 Pod 유지) | 회복 가능성과 구분할 신호가 없어 타임아웃까지 대기 |
| 스케줄 불가(자원 부족) | 타임아웃 `FAILED` | 자원이 생기면 회복될 수 있어 조기 실패로 보지 않음 |
| Kubernetes 조회 일시 실패 | 다음 poll에서 재시도 | 타임아웃까지 계속 실패하면 타임아웃 `FAILED` |
| 백엔드 재시작 중 대기 | `DEPLOYING`과 `image_uri`가 DB에, 요청 ID가 Deployment annotation에 남음 | 대조는 #57. annotation이 이 요청 ID면 이 요청이 적용한 spec이고, 다르면 다른 요청이 덮어쓴 것이다. apply 전에 멈췄으면 annotation이 이전 요청 ID로 남는다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-10 등급 M. 배포 상태 판정 변경이고 backend 한 모듈(infra·deployment) 안의 변경이다.
- 2026-10-10 타임아웃은 기존 `kubernetes.rollout.timeout-ms` 120초를 유지한다. `progressDeadlineSeconds`(기본 600초)는 설정하지 않는다. (manifest 변경은 모든 앱에 spec 변경을 일으키고, 조기 실패는 Pod 대기 사유로 충족된다. startup probe 대기가 120초보다 긴 앱은 지금처럼 타임아웃 실패하며, 앱별 타임아웃은 범위 밖 이슈 후보)
- 2026-10-10 `ErrImagePull`은 첫 시도의 일시 오류일 수 있어 조기 실패로 보지 않는다. kubelet이 곧 `ImagePullBackOff`로 바꾼다.
- 2026-10-10 Q1 #57 대조용 식별 정보 → Deployment metadata annotation `klepaas.io/deployment-id`. DB 마이그레이션은 하지 않는다. (클러스터 쪽에서 현재 spec의 적용 요청을 직접 확인할 수 있고, 운영 DB 사본 검증이 필요 없다. 이미지·상태는 트랜잭션 분리로 DB에 남는다)
- 2026-10-10 Q2 대체된 배포 → `CANCELED`, `fail_reason`에 generation 변화. (enum·DB check에 이미 있고 콘솔 `api.ts`와 CLI `deployments wait`가 실패 종료로 처리한다. API 계약 추가 없음)
- 2026-10-10 Q3 조기 실패 사유 → `ImagePullBackOff`, `InvalidImageName`, `ErrImageNeverPull`, `CreateContainerConfigError`, `CrashLoopBackOff`. (#55의 이미지 오류·필수 env 누락 시나리오를 타임아웃 전에 판정)
- 2026-10-10 대체 판정은 generation 비교로 한다. scale처럼 template을 바꾸지 않는 변경도 대체로 본다. (성공을 잘못 기록하지 않는 쪽을 택함)

- 2026-10-10 (구현 중) 전체 교체(PUT)는 설정이 같아도 generation을 올린다 → "같은 spec이면 generation 그대로" 전제를 정정. 판정 규칙은 그대로 둔다. (k3s 확인 2단계, 새 ReplicaSet 없이 즉시 완료)

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 판정 규칙(성공, 대체, 미관측 대기, ProgressDeadlineExceeded, Pod 대기 사유와 `ErrImagePull` 제외, 삭제, 타임아웃 메시지) | `KubernetesManifestGeneratorTest` | 로컬 | 통과 |
| apply 전 `DEPLOYING`·`image_uri` 커밋, apply가 요청 ID annotation과 generation 반환, 대체 → `CANCELED`, 실패 → `FAILED`(성공 기록 없음) | `DeploymentPipelineStepServiceTest`, `DeploymentPipelineServiceTest`, `KubernetesManifestGeneratorTest` | 로컬 | 통과 |
| 정상 배포 성공과 annotation | 임시 확인 테스트 `LocalK3sRolloutCheckTest` (`deploy` + `waitForRollout`) | 실제 k3s v1.31.4 (docker 임시 컨테이너) | 통과, 약 2~8초 |
| 같은 설정 재배포 즉시 성공 | 같은 절차 | 실제 k3s | 통과, generation +1, 새 ReplicaSet 없음 |
| 없는 이미지는 120초 타임아웃 전 실패, 기존 Pod 유지 | 같은 절차 | 실제 k3s | 통과, 약 14초 `ImagePullBackOff`, available=1 |
| 반복 종료 컨테이너는 타임아웃 전 실패 | 같은 절차 (busybox 기본 명령) | 실제 k3s | 통과, 약 8초 `CrashLoopBackOff` |
| 대기 중 다른 apply가 끝나도 앞 요청은 성공이 아님 | 느린 readiness 요청 A 대기 중 요청 B 적용 | 실제 k3s | 통과, A `SUPERSEDED`(generation 6 → 7), B `SUCCEEDED`, annotation = B |
| readiness 실패는 타임아웃 실패 | 타임아웃 30초로 잘못된 경로 배포 | 실제 k3s | 통과, `rollout 타임아웃(30초, generation=8)` |
| 백엔드 회귀 | `./gradlew test` (200개) | 로컬 (Testcontainers PostgreSQL) | 통과 |

- 원자료: `.local/evidence/0053-request-scoped-rollout/2026-10-10/` (확인 코드와 출력). k3s 컨테이너와 이미지는 확인 후 삭제했다.
- 운영 클러스터·운영 DB 검증은 하지 않았다. 운영에서의 첫 확인은 머지 후 다음 사용자 앱 배포다.

## 운영 반영
- 2026-10-09T17:11:58Z PR #84 머지(596ac6b), main `Build and deploy` run 37964563039: build·deploy success. 마이그레이션 없음.
- 새 rollout 판정은 운영 클러스터에서 아직 실행되지 않았다(머지 후 사용자 앱 배포 없음, 2026-10-10 #54 PR 시점). 첫 사용자 앱 배포 결과는 그 뒤 이슈 PR에서 기록한다.

## 회고
- 어긋난 점: (1) #51 완료 증거의 "잘못된 경로 재배포는 성공으로 판정하지 않음"은 앱 이름 기준 판정으로 통과했지만, 대기 중 다른 변경이 끝나면 성공으로 기록되는 경로는 검증 대상이 아니었다. (2) rollout 대기가 상태 기록과 같은 트랜잭션이라 실패 시 `DEPLOYING`과 KANIKO 경로의 `image_uri`가 롤백되어 남지 않았다. (3) 스펙 초안의 "같은 spec이면 generation 그대로" 전제가 실제 k3s와 달랐다.
- 원인 분류: 테스트 부재 (동시 변경 시나리오), 외부 제약 미인지 (PUT generation 동작)
- 고친 위치: 판정 단위 테스트(대체·조기 실패), 파이프라인 테스트(대체·실패가 성공으로 기록되지 않음). 외부 동작 전제는 이번처럼 실제 클러스터 확인으로 정정하며, 새 검증 장치는 추가하지 않는다.
- 범위 밖 이슈 후보: rollout 타임아웃이 앱의 startup probe 허용 시간과 무관한 고정 120초다 ([architecture §9](../../architecture.md#9-알려진-격차)).
