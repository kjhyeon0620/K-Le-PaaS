---
issue: 57
title: 재시작 후 미완료 배포·명령 상태 대조
status: done
size: L
type: fix
branch: fix/#57-enable-startup-reconciliation
---

# 재시작 후 미완료 배포·명령 상태 대조

## 목적 / 성공 조건
- 목적: 백엔드가 중단된 뒤에도 배포·명령이 영원히 진행 중으로 남지 않는다. 실제 적용 근거와 관측 결과를 대조하고, 결과를 확정할 수 없으면 `UNKNOWN`으로 표시한다.
- 기술적 완료: 재시작 시 이전 프로세스의 미완료 작업만 대조한다. 배포는 성공·실패·대체·판정 불가를 구분하고, 실행 중 명령은 `UNKNOWN`으로 정리한다. 외부 변경을 자동 재실행하지 않는다.
- 운영: 요청별 상세와 CLI에서 결과 및 판단 이유를 확인한다. 이전 릴리스 JAR로 롤백해도 새로 생성한 행을 읽고 쓸 수 있다.

## 현재 동작 (2026-10-10 코드 확인)
- 배포는 `@Async` 메모리 작업이다. DB에는 단계·이미지·요청 ID·설정 스냅샷이 남지만, apply 응답의 generation은 `AppliedRollout`에만 있고 영속화되지 않는다. 리소스 UID도 기록하지 않는다.
- `klepaas.io/deployment-id`는 Deployment metadata annotation이다. restart·scale은 generation을 바꾸지만 이 annotation을 새 배포 ID로 바꾸지 않는다. annotation·이미지만 같다고 이 요청의 성공으로 판단하면 안 된다.
- `startK8sDeploy`가 `DEPLOYING`을 먼저 커밋하고, `applyK8sManifests`가 Deployment·Service·Ingress를 적용한다. 외부 적용과 DB 커밋 사이의 중단 가능성은 남는다.
- 배포 `DeploymentStatus`와 PostgreSQL CHECK에는 `UNKNOWN`이 없다. 현재 JAR의 JPA enum은 DB에 `UNKNOWN`이 있으면 읽을 수 없다. enum 추가와 동시에 새 상태를 쓰는 단일 릴리스는 ADR-0004의 이전 JAR 호환 조건과 충돌한다.
- 명령 `CommandStatus`에는 `UNKNOWN`이 이미 있다. 승인 명령은 `EXECUTING`으로 별도 커밋 후 실행하며 결과는 조건부 update로 끝낸다. 기동 시 상태 대조는 없다.
- CLI `deployments wait`는 SUCCESS / FAILED / CANCELED만 종료로 취급한다. 콘솔 상세의 나머지 상태는 진행 중과 같은 파란 badge다.

## 1단계 이후 동작 (2026-10-11 코드 확인, 2단계 기준)
- 1단계(PR #97)로 `DeploymentStatus.UNKNOWN` 읽기, V5 nullable `applied_resource_uid`·`applied_generation`, apply 응답 UID·generation 저장이 운영에 있다. UNKNOWN을 기록하는 경로는 없다.
- 기동 hook·`@Scheduled`는 없다. Spring Boot는 웹 서버를 `ApplicationReadyEvent`·Runner보다 먼저 열기 때문에 기동 직후 요청이 대조와 겹칠 수 있다.
- 명령 `consume`·`finish`는 JPQL bulk update라 `updated_at`을 바꾸지 않는다. 재시작 전 만든 PENDING 명령을 재시작 후 승인하면 `created_at`·`updated_at`이 기동 이전인 EXECUTING 행이 생긴다. **기동 기준 시각 비교로는 이전 프로세스 작업을 구분할 수 없다.**
- `createDeployment`는 마지막 갱신 후 빌드+rollout 타임아웃+10분 안의 미완료 배포를 진행 중으로 보고 새 요청을 거절하거나 같은 요청이면 그 행을 돌려준다.
- 배포 receiver는 `/api/v1/system/ready`가 200이 아니면 이전 릴리스로 자동 롤백한다.
- 배포 상태 전이는 엔티티 저장뿐이고 이전 상태 조건 update는 없다. `failure_kind`는 CHECK 없는 varchar이며 새 값을 추가하지 않는다 (이전 릴리스 호환).

## 2단계 계약 (활성화 릴리스)
- 대상 확정: 웹 서버가 요청을 받기 전(`SmartInitializingSingleton`) DB에서 미완료 배포(PENDING / UPLOADING_SOURCE / BUILDING / DEPLOYING)와 EXECUTING 명령의 id만 읽어 둔다. 이 id 목록만 대조하며 시각 비교는 쓰지 않는다. 이 시점에는 이 프로세스가 시작한 작업이 없다.
- 실행: `ApplicationReadyEvent`에서 목록을 한 번 처리한다. 행마다 짧은 읽기 트랜잭션 → (DEPLOYING이면) 트랜잭션 밖 Kubernetes 조회 → 이전 상태를 조건으로 한 update. 한 행의 실패가 다른 행·기동을 막지 않는다.
- 저장 실패: 로그만 남기고 그 행은 원래 상태로 둔다. readiness와 기동에는 반영하지 않는다 (receiver 자동 롤백 방지). 다음 재기동 때 다시 대상이 된다.
- 동시 요청: 대조가 끝나기 전 같은 저장소의 새 배포는 기존 진행 중 규칙(`DEPLOYMENT_IN_PROGRESS` 또는 같은 요청 재사용)을 그대로 따른다.
- 기록 값: FAILED(중단) `failure_kind=OTHER`, FAILED(Deployment 없음) `DEPLOYMENT_MISSING`, rollout 실패는 기존 판정 종류, CANCELED `SUPERSEDED`, UNKNOWN은 `failure_kind` null. 모든 경우 `fail_reason`에 “재시작 대조:” 접두의 이유, `finished_at`·`updated_at`을 기록한다. 명령 UNKNOWN은 `is_executed=false`, `error_message`에 이유.
- 알림·WebSocket: 대조 결과는 보내지 않는다 (재시작 직후 구독자 없음, 범위 밖).
- Kubernetes 조회: Deployment 1회, 판정이 필요할 때 현재 ReplicaSet·Pod 목록. rollout을 기다리지 않는다.

## 이번 1단계의 완료 조건
- T1만 구현한다. UNKNOWN을 기록하는 도메인 메서드·기동 hook·대조 서비스는 추가하지 않는다.
- 기존 파이프라인은 기존 상태만 쓰며 새 nullable UID·generation을 추가로 기록한다.
- UNKNOWN 표시를 모의 API/로컬 테스트 데이터로 확인한다. 운영 DB에 UNKNOWN을 주입하지 않는다.
- V4 → V5 적용, 기존 행 유지, 변경 전 JAR가 V5의 기존 상태 행을 읽고 쓰는지 검증한다.
- 1단계 이전 JAR로 되돌리면 UID·generation 미기록 행이 생길 수 있다. 2단계는 그런 행을 추정 복원하지 않는다.

## 범위
- 포함
  - apply 응답의 Kubernetes Deployment UID·generation을 요청에 저장한다.
  - 배포 `UNKNOWN` 읽기·표시·CLI 대기 종료 지원.
  - 기동 시 이전 프로세스의 미완료 배포·명령 대조 및 결과 기록.
  - 기존 rollout 판정 재사용, 소유권·리소스 식별 대조, 조건부 상태 저장.
  - 중단 경계·권한·외부 오류·마이그레이션·클라이언트 검증.
  - 스펙 0095에 운영 배포 #12~#14 확인 결과 기록.
- 제외
  - 자동 재배포·재빌드·restart·scale·rollback, 주기적 재대조, 수동 재대조 API.
  - 다중 백엔드 인스턴스의 작업 소유권·lease (현재 단일 systemd 프로세스 전제).
  - 승인 설정 고정(#56), 설정 스냅샷과 apply 사이의 재조회 창 수정, AI 진단·MCP.
  - 앱별 rollout 타임아웃 변경, 과거 UID·generation 추정 복원.

## 입출력·계약
- API: 새 endpoint·필드는 만들지 않는다. 배포 응답의 기존 `status`에 `UNKNOWN` 추가. 기존 `fail_reason`에 중단 및 판단 불가 이유를 기록한다.
- CLI: `deployments wait`는 UNKNOWN에서 대기를 끝내고 기존 비성공 배포와 같은 exit 3을 반환한다. `--json.final_status`는 UNKNOWN이다.
- 콘솔: 목록·상세·배포 카드에서 UNKNOWN을 “판정 불가”로 표시하고 이유를 보여 준다. 진행 중으로 표시하지 않는다. 기존 디자인을 사용한다.
- 저장: `V5__deployment_reconciliation_evidence.sql` 하나로 nullable `applied_resource_uid`·`applied_generation` 추가, 기존 status CHECK에 UNKNOWN 허용. 기존 행의 새 필드는 null.
- 적용 근거: Deployment·Service·Ingress 적용이 모두 반환된 뒤, apply 응답에서 얻은 UID·generation을 같은 요청의 DB에 커밋한다. 현재 리소스를 재조회해 적용 당시 값처럼 기록하지 않는다.
- Kubernetes: 대조 중에는 조회만 한다. 새 리소스 변경·외부 빌드 재개 없음.
- 대조는 기동 시 한 번 수행한다. 기동 기준 시각 이전 작업만 대상으로 하고 새 요청·이미 terminal인 행은 건드리지 않는다. 조회 동안 DB 트랜잭션을 길게 유지하지 않고, 마지막에 이전 상태를 조건으로 기록한다.
- 기본값: rollout 완료를 다시 120초 기다리지 않고 한 번 관측한다. 아직 진행 중이거나 조회에 실패하면 UNKNOWN이다. 이후 자동 재대조는 하지 않는다.
- UNKNOWN은 자동 실행이 끝난 상태다. 새 명시적 배포 요청은 기존 권한·동시성 규칙으로 받을 수 있다. 성공 비교 대상에는 포함하지 않는다.

### 재시작 판정 (2단계 활성화 시)
| 이전 상태 / 관측 | 결과 | 근거 |
|---|---|---|
| PENDING / UPLOADING_SOURCE / BUILDING | FAILED | 파이프라인이 중단됐고 이 단계에서는 앱 리소스를 적용하지 않는다. 외부 빌드 완료 여부나 취소를 주장하지 않으며 자동 재개하지 않는다 |
| DEPLOYING, 적용 UID·generation 누락 | UNKNOWN | apply 이전·직후 중단 또는 이전 릴리스 기록. annotation만으로 성공 추정 금지 |
| DEPLOYING, 소유권·리소스 라벨 불일치 | UNKNOWN | 다른 리소스의 상태를 이 요청에 귀속하지 않음 |
| DEPLOYING, Kubernetes 조회 실패 | UNKNOWN | `KubernetesErrorMessages.userMessage(e)`로 주소를 제외한 이유 기록 |
| DEPLOYING, 기록된 적용 근거가 있고 Deployment 없음 | FAILED | 현재 적용 리소스 없음. 과거에 성공한 적 없었다고 단정하지 않음 |
| DEPLOYING, UID·generation 또는 요청 annotation이 달라짐 | CANCELED | 다른 변경으로 대체됨. 그 변경의 성공을 현재 요청 성공으로 쓰지 않음 |
| DEPLOYING, 식별자·요청 ID·이미지 일치, #53 성공 조건 충족 | SUCCESS | 새 revision Pod의 digest를 관측 가능할 때만 기록 |
| DEPLOYING, 식별자 일치, #53 명확한 실패 조건 충족 | FAILED | 기존 FailureKind와 관측 이유 재사용 |
| DEPLOYING, 식별자 일치하나 이미지 불일치·아직 진행 중·근거 부족 | UNKNOWN | 조회 시점에 이 요청의 완료를 확정할 수 없음 |
| 명령 EXECUTING | UNKNOWN | 외부 실행 결과를 확정할 수 없음. 재실행·재승인 소비 없음 |
| 명령 PENDING / 나머지 terminal 상태 | 유지 | 기존 승인 만료·소비 계약 유지 |

## 권한·안전
- 기동 작업은 사용자 API가 아닌 서버 내부 대조다. 저장된 저장소 소유자로 `ResourceAccessService.requireDeployment`를 거친 뒤 해당 namespace·repository label의 리소스만 읽는다. 소유권을 확인하지 못하면 Kubernetes 호출 없음.
- UID·generation·annotation·이미지 대조 없이 다른 rollout을 성공 처리하지 않는다.
- UNKNOWN을 성공·실패로 추정하지 않는다. 자동 외부 실행은 0회다.
- 운영 서버·DB 변경은 별도 승인 대상이다. 운영 DB 사본 검증은 사용자가 실행한다.
- 새 비밀값·설정 키는 추가하지 않는다. 공개 문서에는 운영 주소·토큰을 넣지 않는다.

## 제약
- 참조: [architecture §3.2·§5·§6·§9](../../architecture.md), [ADR-0004](../../adr/0004-flyway-forward-compatible-migrations.md), [0053](../0053-request-scoped-rollout/spec.md), [0095](../0095-deployment-request-detail/spec.md).
- UNKNOWN을 쓰는 릴리스의 바로 이전 릴리스도 UNKNOWN을 읽을 수 있어야 한다. 사용자가 승인한 두 릴리스 순서를 지킨다.
- apply는 여러 외부 요청이다. 적용 직후 DB 기록 이전에 중단되면 UNKNOWN으로 남는 구간을 허용한다. exactly-once를 주장하지 않는다.

## 예외·경계 상황
| 상황 | 처리 |
|---|---|
| DB 대조 결과 저장 실패 | 로그만 남기고 행은 원래 상태 유지. 기동·readiness에 반영하지 않고 완료를 주장하지 않는다. 다음 재기동에 다시 대상 |
| 기동 중 새 요청 | 웹 서버 시작 전 확정한 id 목록에 없으므로 대조하지 않는다. 같은 저장소 배포는 기존 진행 중 규칙을 따른다 |
| 재시작 전 PENDING 명령을 재시작 후 승인 | 목록 확정 시 PENDING이었으므로 대상 아님. 새 실행 결과를 그대로 둔다 |
| 대조 사이 다른 경로가 이미 terminal로 변경 | 조건부 update가 기존 결과를 보존 |
| 삭제 후 같은 이름·generation으로 재생성 | UID 불일치로 CANCELED |
| 같은 이미지로 restart·scale | generation 불일치로 CANCELED |
| 새 ReplicaSet Pod 미생성 / 관측 generation 미도달 | UNKNOWN, 완료 추정 없음 |
| 이전 릴리스 미완료 행 | 새 적용 근거 null이면 UNKNOWN (활성화 이후) |

## 미결 질문 (Open)
- 없음 (2026-10-11 2단계 기동 정책 확정, Decisions 참고).

## 결정 기록 (Decisions)
- 2026-10-10 Q1 → 사용자 결정: 같은 #57 아래 두 릴리스로 진행한다. 1단계는 UNKNOWN 읽기·표시와 적용 UID/generation 저장만 구현·배포한다. 이슈는 닫지 않고 스펙을 in-progress로 유지한다. 2단계는 1단계 운영 배포 확인 후 별도 브랜치/PR에서 대조를 활성화한다. 각 PR은 별도로 미커밋 보고와 사용자 승인을 거친다. 준비 PR은 Closes 대신 Refs #57을 써 이슈를 닫지 않는다.
- 2026-10-10 사용자 선택: #57 하나만 진행. 등급 L (상태 계약·스키마·backend/frontend/CLI·운영 반영).
- 2026-10-10 기본값: 자동 재실행 없음, 기동 시 한 번 관측, 미완료 rollout은 UNKNOWN, 주기적·수동 재대조는 제외.
- 2026-10-10 기본값: UID·generation·요청 ID·이미지를 함께 확인한다. 과거 값은 추정 복원하지 않는다.
- 2026-10-10 기본값: UNKNOWN에서 새 명시적 배포는 허용하고 CLI wait는 exit 3으로 끝낸다. 기존 성공 배포 비교 기준에는 포함하지 않는다.
- 2026-10-10 사실 확인: #53은 generation을 영속화하지 않았다. #57 이슈 본문의 “기록된 generation”을 사용하려면 영속화가 선행되어야 한다.

- 2026-10-10 (구현 중) CLI 비성공 종료는 wait와 공통 catch에서 JSON을 중복 출력해 파싱되지 않았다. UNKNOWN도 같은 경로를 쓰므로 기존 final_status·fail_reason·timeline·exit 3을 유지하고 공통 catch에서 한 번만 출력하도록 수정한다. FAILED/CANCELED도 같은 회귀 테스트로 확인한다.

- 2026-10-11 사실 확인: 명령 bulk update는 `updated_at`을 갱신하지 않아 기동 기준 시각으로 이전 EXECUTING과 재시작 후 승인된 EXECUTING을 구분할 수 없다. Spring Boot는 Runner보다 웹 서버를 먼저 연다.
- 2026-10-11 Q1 → 사용자 결정(권장안): 웹 서버 시작 전 `SmartInitializingSingleton`에서 대상 id만 확정하고, Kubernetes 관측과 기록은 기동 후 처리한다. 시각 비교는 쓰지 않는다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 대조 완료 전 같은 저장소 새 배포는 기존 진행 중 규칙(`DEPLOYMENT_IN_PROGRESS`/같은 요청 재사용)을 유지한다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 대조 결과 저장 실패는 로그만 남기고 행을 그대로 둔다. readiness·기동에 반영하지 않는다.
- 2026-10-11 Q4 → 사용자 결정(권장안): 실제 클러스터 검증용 k3s 이미지는 2단계 검증 직전에 내려받는다.
- 2026-10-11 (구현 중) 기동 후 처리는 `ApplicationReadyEvent` 리스너에서 순차 실행한다. 이미 웹 서버가 요청을 받는 중이므로 별도 executor를 두지 않는다. 중단 FAILED는 새 enum 값 대신 기존 `OTHER`를 쓴다 (이전 릴리스 호환).

## 완료 증거

### 1단계 실행 결과 (2026-10-10)
- 로컬: 단계 서비스·manifest generator·PostgreSQL 저장 테스트 통과. apply 응답 UID/generation 저장과 apply 실패 시 null 유지를 확인했다.
- 로컬: `./gradlew test bootJar` 통과. 전체 테스트 224개, 실패 0개, 오류 0개, 건너뜀 0개.
- 로컬: 패키징한 변경 전 JAR로 `spring.flyway.target=4` 스키마를 만들고 7개 상태 행을 넣은 뒤, 새 JAR로 V5 migrate·Hibernate validate·readiness UP·기존 행 유지·새 컬럼 null을 확인했다.
- 로컬: V5 DB에 변경 전 JAR를 다시 기동해 7개 행 조회, 새 배포 행 생성과 FAILED 갱신을 확인했다 (설정 없는 로컬 저장소이므로 외부 실행 전 실패). 새 JAR로 재기동해 테스트용 UNKNOWN 행의 상세·상태 API 조회도 확인했다. 변경 전 JAR는 revision이 unknown인 로컬 빌드이며 운영 바이너리 자체를 검증한 것은 아니다.
- 로컬: Node HTTP mock 계약 테스트 3개 통과 (UNKNOWN/FAILED/CANCELED: 조회 1회, JSON 1개, final_status·fail_reason·timeline, exit 3).
- 로컬: TypeScript 타입 검사·Next.js production build 통과. 의존성의 Browserslist/baseline 데이터 갱신 안내는 있으나 빌드는 성공했다.
- 로컬 모의 API·브라우저: 1440×900에서 카드→History→상세→로그의 판정 불가·이유 표시 확인. 390×844에서 상세·로그 확인. 기존 사이드바 자동 접힘 미지원으로 본문이 좁아지는 한계는 §9에 이미 기록돼 있으며 수동으로 접은 뒤 새 표시의 줄바꿈·배지·이유를 확인했다. mock API/Next 서버·검증 탭·임시 launch 설정·임시 DB 컨테이너는 정리했다.
- 증거: `.local/evidence/0057-reconcile-interrupted-work/` (테스트 집계, migration-result.json, JAR 실행 로그, 데스크톱·모바일 스크린샷).
- 운영 DB 사본 (2026-10-10, 사용자 실행 결과): V4 사본의 배포 14개 행에 V5 migrate·Hibernate validate·readiness UP 통과. 기존 14개 행·값 유지와 새 UID·generation 컬럼 모두 NULL 확인. 검증 스크립트는 원본을 변경하지 않고 종료 시 사본·확인용 JAR 프로세스를 정리한다. 운영 원본에 V5를 적용한 결과가 아니다.
- 2단계 대조·실제 클러스터 중단 재현은 이번 준비 릴리스에서 실행하지 않는다.

### 2단계 실행 결과 (2026-10-11)
- 로컬: `KubernetesManifestGeneratorTest` 한 번 관측 판정 5개 (성공·digest 미관측 시 null, UID·요청 ID·generation 변경과 같은 이미지 restart → 대체, 다른 저장소 라벨·이미지 불일치·진행 중·generation 미관측 → 판정 불가, ProgressDeadline·Deployment 없음 → 실패, 조회 실패 → API 주소 없는 판정 불가).
- 로컬 PostgreSQL: `InterruptedWorkReconcilerTest` — PENDING/UPLOADING_SOURCE/BUILDING → FAILED(OTHER), 근거 없는 DEPLOYING → UNKNOWN, 성공·대체 기록, 조회 중 다른 경로가 먼저 terminal로 바꾼 행 보존, 한 행 예외가 다른 행을 막지 않음, 소유자 없는 저장소는 Kubernetes 호출 없이 UNKNOWN, 대상 확정 후 생긴 배포와 재시작 후 승인된 이전 PENDING 명령 보존, EXECUTING 명령만 UNKNOWN, terminal 유지, Kubernetes는 근거·소유권이 확인된 행만 `observeOnce` 1회씩이고 다른 호출 0회, 같은 프로세스 재호출 시 대상 없음.
- 로컬: 전체 `./gradlew test` 230개, 실패·오류·건너뜀 0개.
- 로컬 실제 클러스터 (패키징 JAR + PostgreSQL 16 + Docker 임시 k3s v1.30.6): 배포 5개를 apply 후 rollout 대기 중 SIGKILL로 종료 (적용 근거 generation 1 저장 확인). 종료 중 restart·삭제·ImagePullBackOff·rollout 완료가 일어난 뒤 재기동하여 다음 결과를 확인했다.
  - rollout 완료 → SUCCESS + 새 Pod digest
  - `kubectl rollout restart` → CANCELED(SUPERSEDED, generation 1 → 2)
  - 존재하지 않는 이미지 → FAILED(IMAGE_PULL)
  - Deployment 삭제 → FAILED(DEPLOYMENT_MISSING)
  - 스케줄 불가(cpu 64) → UNKNOWN(진행 중)
  - DB로 재현한 apply 전 중단: BUILDING → FAILED(OTHER), 근거 없는 DEPLOYING → UNKNOWN
  - EXECUTING 명령 → UNKNOWN, PENDING·SUCCEEDED 명령 유지
  - 대조 전후 Kubernetes Deployment UID·generation·요청 annotation·Pod template annotation 동일 (재실행·restart·scale 0회), 배포 행 추가 없음
  - 대조 후 새 배포 SUCCESS, UNKNOWN 저장소의 새 배포 접수
- 실제 클러스터 검증의 apply 전 중단은 프로세스 종료 시점을 맞추지 않고 DB 상태로 재현했다. k3s가 기동하며 자체 시스템 이미지를 받았고 앱 이미지는 로컬 redis를 import했다. 검증 후 k3s·DB 컨테이너·kubeconfig·백엔드 프로세스를 정리했다.
- 마이그레이션 없음 (V5 그대로, 새 `failure_kind` 값 없음). 운영 DB 사본 검증 대상 아님.
- 증거: `.local/evidence/0057-reconcile-interrupted-work/stage2/` (restart-k3s-result.json, 기동·재기동 로그).

### 전체 이슈 검증 계획
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| UID·generation이 apply 응답 그대로 저장, apply 실패 시 완료 근거를 만들지 않음 | 파이프라인·단계 서비스 테스트 | 로컬 |
| 상태별 판정, 동일 이미지 다른 generation·UID·요청 ID, 권한 밖 호출 0회, 조회 실패 UNKNOWN | 대조 서비스·Kubernetes 판정 테스트 | 로컬 |
| 과거 EXECUTING 명령만 UNKNOWN, 새 작업·terminal 상태 보존, 재실행 0회 | DB 통합 테스트 | 로컬 PostgreSQL |
| V4 기존 행에 V5 적용·validate·readiness, 새 컬럼 null, 이전 릴리스 호환 | 패키징 JAR·실제 행 read/write | 로컬 PostgreSQL, 운영 DB 사본 |
| apply 전/후·결과 기록 전 프로세스 중단 후 재기동 대조 | 패키징 앱 + 임시 k3s. 이미지 다운로드는 먼저 사용자 승인 | 로컬 실제 클러스터 |
| UNKNOWN CLI wait 종료·JSON, 콘솔 목록·상세의 판정 불가 | mock API + CLI·브라우저, tsc·build | 로컬 |
| 회귀 | PR 전 `./gradlew test` 전체 1회 | 로컬 |

## Tasks (두 릴리스로 분리)
### T1. 호환성 준비와 적용 근거
- 변경: V5, Deployment 필드·enum, Kubernetes apply 반환값, 파이프라인 저장, API 클라이언트 UNKNOWN 처리.
- 금지: 호환성 준비 릴리스에서 DB에 UNKNOWN을 생성하거나 기동 대조를 활성화하지 않는다.
- 검증: 적용 근거 저장, enum 읽기, 클라이언트, 이전 JAR 호환·마이그레이션.
### T2. 기동 대조 활성화
- 변경: 기동 hook, 대조 서비스, 조건부 DB 결과 저장, 조회 전 권한·리소스 귀속 확인.
- 검증: 판정 표 전 항목·명령 정리·재실행 0회·프로세스 중단 실증.
### T3. 문서·운영 반영·회고
- 변경: architecture 상태·흐름·격차, backend/frontend/루트 README, CLI_REFERENCE, CICD, product, 이 스펙.
- 운영 DB 사본 결과를 확보하고 승인 전 미커밋 보고한다. 각 릴리스별로 보고·승인·PR·사용자 머지를 거친다.

## 운영 반영
- 1단계 (2026-10-10): PR #97 머지 `1865e5f`, main 배포 run 38062074153 build·deploy success. receiver는 `validate` 강제 기동·readiness·revision을 확인한 뒤에만 성공하므로, 새 엔티티 컬럼을 요구하는 이 릴리스가 기동한 것은 V5가 운영 원본에 적용됐다는 간접 증거다. 운영 API·DB는 직접 조회하지 않았다. 이 시점부터 2단계 롤백 대상은 1단계 릴리스다.
- 2단계 (2026-10-10): PR #98 머지 `2bfb73e`, main 배포 run 38073148420 build·deploy success. receiver가 readiness·revision을 확인한 뒤 성공했으므로 활성화 릴리스가 운영에서 기동했다. 운영 기동 시 대조 대상·결과 로그와 운영 API·DB는 조회하지 않았다. 롤백 대상은 1단계 릴리스(`1865e5f`) 이상이다.

## 회고
### 1단계
- enum 추가도 이전 JAR의 데이터 읽기를 깨뜨릴 수 있다. nullable 컬럼만 확인하는 것으로는 충분하지 않아, UNKNOWN 읽기 준비와 기록 활성화를 두 릴리스로 나눴다. 배포 순서는 CICD에 남겼다.
- generation을 재조회하면 다른 작업의 값을 기록할 수 있다. create/replace 응답의 UID·generation만 반환하고 전체 apply가 끝난 후 저장하며, 실패 시 근거가 남지 않는 테스트를 추가했다.
- CLI는 종료 코드만 확인하면 JSON 중복 출력 결함을 놓친다. 별도 프로세스의 stdout을 JSON으로 파싱하는 계약 테스트로 UNKNOWN과 기존 비성공 종료를 함께 확인했다.
- 1단계 구현·로컬 검증·운영 DB 사본 검증을 마쳤다. 스펙은 이슈 전체 완료 전까지 in-progress다. 사용자 승인 후 1단계 PR·운영 배포를 거쳐 2단계 대조를 활성화한다.
### 2단계
- 기동 기준 시각으로 이전 작업을 구분하려던 초기 계약은 명령 bulk update가 `updated_at`을 바꾸지 않아 성립하지 않았다. 웹 서버 시작 전에 대상 id를 확정하는 방식으로 바꾸고, 재시작 후 승인된 이전 PENDING 명령을 통합 테스트로 고정했다.
- 권한 거절 예외가 공유 트랜잭션을 rollback-only로 만들어 소유권 미확인 행이 기록되지 않는 결함을 통합 테스트가 잡았다. 읽기는 바깥 트랜잭션 없이 하고 기록만 짧은 트랜잭션으로 한다.
- 실제 클러스터에서 프로세스를 apply 후 대기 중에 끊는 방법으로 poll 간격 설정을 늘려 대기 구간을 확보했다. 종료 시점에 의존하지 않아 같은 결과를 재현할 수 있다.
- 남은 한계: 단일 프로세스 전제, 대조 후 UNKNOWN은 자동 재대조하지 않는다. 필요하면 별도 이슈로 다룬다.
