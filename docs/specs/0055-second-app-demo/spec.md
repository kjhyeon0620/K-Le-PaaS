---
issue: 55
title: 두 번째 앱 배포·실패·이전 이미지 복구 실증
status: done
size: L
type: test
branch: test/#55-second-app-demo
---

# 두 번째 앱 배포·실패·이전 이미지 복구 실증

## 목적 / 성공 조건
- 목적: IoT와 다른 기술 스택(Node.js)의 두 번째 앱으로 등록 → 변경 → 실패 확인 → 복구를 K-Le-PaaS 코드 수정과 서버 수동 패치 없이 수행할 수 있음을 운영 환경에서 보인다 ([product §1](../../product.md)).
- 기술적 완료: 각 단계의 배포 상태·실패 원인·응답 버전이 기대값과 같고, 그 근거(배포 ID, 이미지, 상태, `fail_reason`, Logs 관측)가 기록된다.
- 운영: 실패 단계에서도 기존 Pod가 계속 응답하고, 이미지 재배포와 배포 설정 복구로 정상 상태로 돌아온다.

## 범위
- 포함
  - 공개 샘플 저장소 `kjhyeon0620/klepaas-sample-node`: Node.js HTTP 앱, Dockerfile, GitHub Actions workflow(arm64 빌드 → GHCR push → OIDC로 K-Le-PaaS 배포 호출, 수동 실행 시 이미지 태그 지정)
  - 운영 K-Le-PaaS에서 시나리오 실행과 결과 기록
  - 과정에서 발견한 결함의 이슈 분리
- 제외
  - K-Le-PaaS 코드 변경 (발견한 결함은 별도 이슈. 이 이슈 안에서 고치지 않음)
  - 앱 외부 공개(서버 Nginx 경로 추가), 도메인·HTTPS
  - 배포 요청 상세 화면, AI 진단, 롤백 API

## 샘플 앱 계약
| 항목 | 값 |
|---|---|
| 포트 | 8080 |
| `GET /version` | `{"version":"<앱 버전>","greeting":"<SAMPLE_GREETING>"}` |
| `GET /health` | 200 `ok` (v3에서는 `/ready`로 옮김) |
| 필수 env | `SAMPLE_GREETING`. 없으면 오류를 출력하고 종료(exit 1) |
| 이미지 | `ghcr.io/kjhyeon0620/klepaas-sample-node:sha-<commit>` (linux/arm64, 공개 패키지) |

## K-Le-PaaS 배포 설정 (사용자가 승인한 CLI 로그인으로 에이전트가 API로 입력, Decisions 참고)
| 항목 | 값 |
|---|---|
| cloud vendor / 빌드 방식 | `ON_PREMISE` / `GITHUB_ACTIONS_GHCR` |
| 이미지 템플릿 | `ghcr.io/kjhyeon0620/klepaas-sample-node:sha-{commitHash}` |
| container port | 8080 |
| service | `NODE_PORT`, 사용 중이지 않은 NodePort (IoT의 30080 제외) |
| replicas | min 1 / max 1 |
| health probe | path `/health`, period 5, failure 3, startup 미사용 |
| env | `SAMPLE_GREETING=hello` |
| image pull secret | 사용 안 함 (공개 이미지, #74) |
| domain | 등록 시 기본값 유지 (비울 수 없음, Decisions 참고) |

## 시나리오
| 단계 | 방법 | 기대 결과 |
|---|---|---|
| S1 v1 배포 | 저장소 등록 후 workflow 수동 실행 (main = v1) | `SUCCESS`, `/version` = 1.0.0, Logs `AVAILABLE` |
| S2 v2 배포 | 버전 문자열 변경 push | `SUCCESS`, 응답 2.0.0 |
| S3 v3 health 경로 변경 | 앱이 health를 `/ready`로 옮긴 커밋 push (설정 probe는 `/health`) | 120초 타임아웃 `FAILED`, 사유 "실행 중이나 Ready 아님", Logs 이벤트 `Readiness probe failed ... 404`, 응답은 계속 2.0.0 |
| S4 없는 이미지 | workflow 수동 실행, 존재하지 않는 태그 지정 | 타임아웃 전 `ImagePullBackOff` `FAILED`, 응답 계속 2.0.0 |
| S5 이미지 복구 | workflow 수동 실행, v1 이미지 태그 지정 | `SUCCESS`, 응답 1.0.0 |
| S6 필수 env 누락 | 콘솔 설정에서 `SAMPLE_GREETING` 삭제 후 workflow 수동 실행(v1 태그) | 타임아웃 전 `CrashLoopBackOff` `FAILED`, Logs 직전 컨테이너 로그에 env 누락 메시지, 응답 계속 1.0.0 |
| S7 설정 복구 | 콘솔에서 env 되돌린 뒤 같은 태그로 재실행 | `SUCCESS`, 응답 1.0.0 |

- 응답 확인: 서버에서 `curl http://127.0.0.1:<NodePort>/version` (읽기만, 패치 아님).
- 배포 상태·`fail_reason`·Logs: 운영 콘솔 또는 CLI. 결과는 사용자가 확인해 전달하고, 에이전트가 Actions 실행 기록(배포 ID 응답)과 함께 정리한다.

## 권한·안전
- CI 인증: GitHub Actions OIDC (#65). 배포 URL은 샘플 저장소 **secret**으로 둬 공개 Actions 로그에 운영 호스트가 남지 않게 한다. 저장소 ID는 비밀이 아니라 variable로 둔다 (Decisions).
- OIDC 제약: 요청 commit이 workflow를 실행한 commit과 같아야 한다. 이전 이미지 복구(S5)는 최신 main에서 수동 실행하며 이미지 태그만 지정한다. 따라서 배포 기록의 commit(실행한 main)과 이미지(v1)가 다르다. 결과에 이 차이를 기록한다.
- 실패 주입은 운영 k3s의 **샘플 앱 Deployment에만** 한다. IoT 앱과 같은 namespace지만 다른 Deployment·Service이며, IoT 응답은 각 실패 단계 뒤에 확인한다 ([architecture §8](../../architecture.md) "운영 앱에 실패를 주입하지 않는다"의 예외로 샘플 앱만 대상).
- 비밀값: 샘플 앱에는 비밀값이 없다. 운영 URL·호스트·NodePort 외 정보는 스펙·PR에 남기지 않고 원자료는 `.local/evidence/`에 둔다.

## 제약
- 참조: [product §1, §5](../../product.md), [ORACLE_K3S_GHCR_DEPLOYMENT.md](../../ORACLE_K3S_GHCR_DEPLOYMENT.md), [0053](../0053-request-scoped-rollout/spec.md), [0054](../0054-deployment-failure-logs/spec.md)
- K-Le-PaaS 코드 수정 0회, 서버 수동 패치 0회를 지킨다. GitHub App 설치·콘솔 등록·설정 입력은 제품 사용 경로이므로 패치로 보지 않는다.

## 예외·경계 상황
| 상황 | 처리 |
|---|---|
| GHCR 패키지가 비공개로 생김 | 패키지 설정에서 공개로 전환 (GitHub 설정, 서버 패치 아님). 결과에 기록 |
| 등록 전 첫 push의 workflow | 배포 secret이 없어 배포 단계에서 실패(이미지는 push됨). 등록 후 수동 실행으로 S1 진행 |
| 시나리오 중 K-Le-PaaS 결함 발견 | 별도 이슈로 분리하고, 우회 없이 가능한 범위까지 진행. 막히면 결과에 기록 |
| 같은 저장소 배포 진행 중 재요청 | 409 `DEPLOY_003` (#52). 이전 단계 결과가 나온 뒤 다음 단계 실행 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-10 등급 L. 외부 저장소 생성과 운영 실행 절차가 필요하다.
- 2026-10-10 샘플 저장소는 에이전트가 `gh`로 공개 저장소로 만든다 (사용자 결정).
- 2026-10-10 CI 인증은 OIDC로 한다. 이슈 본문의 배포 전용 토큰(#50) 대신 #65 이후 권장 방식 (사용자 결정).
- 2026-10-10 앱은 외부 공개하지 않는다. NodePort로 배포하고 서버 안 `curl`로 확인한다. 공개하면 서버 Nginx 수정이 필요해 "서버 수동 패치 0회"와 충돌한다 (사용자 결정).
- 2026-10-10 공개 저장소·공개 GHCR 이미지로 pull secret 없이 배포한다. 서버 허용 참조 설정을 바꾸지 않는다 (사용자 결정).
- 2026-10-10 실패 시나리오는 health 경로, 없는 이미지, 필수 env 누락 세 가지와 이미지·설정 복구 두 가지를 모두 포함한다 (사용자 결정). 이미지 재배포와 설정 복구의 차이를 env 누락 시나리오로 보인다.
- 2026-10-10 (구현 중) 설정 입력이 어렵다는 사용자 요청으로, 사용자가 승인한 CLI 웹 로그인(전체 권한)을 써서 에이전트가 배포 설정 저장·샘플 저장소 secret 설정·S6/S7 env 변경을 API로 실행했다. 스크립트는 `.local/tmp/`에 두고 커밋하지 않는다. (사용자 결정)
- 2026-10-10 (구현 중) `domain_url`은 비울 수 없다(400). 등록 시 만들어진 기본 도메인을 그대로 두었다. 외부에서 그 도메인으로 요청하면 K-Le-PaaS 백엔드가 404를 돌려줘 앱은 공개되지 않는다. NodePort만 쓰는 앱에도 도메인 입력이 필요한 점은 이슈 후보로 남긴다.
- 2026-10-10 (구현 중) 저장소 ID를 secret으로 두면 공개 Actions 로그에서 같은 숫자가 모두 가려져(`kjhyeon06***0`) 저장소 variable로 옮겼다. 배포 URL은 secret으로 유지한다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 샘플 앱 계약(`/version`, `/health`, env 누락 종료) | `node --test` 2개 (로컬, 매 push Actions) | 로컬, GitHub Actions | 통과 |
| S1~S7 상태·사유 | Actions 실행 기록(배포 ID), CLI `deployments wait`·`logs` | 운영 | 기대값과 일치 (결과 표) |
| 응답 버전 | 서버 `curl` (S4 뒤 2.0.0, S7 뒤 1.0.0) | 운영 | 일치 |
| IoT 영향 없음 | 서버 `curl` 30080 (S4 뒤, S7 뒤) | 운영 | HTTP 200 |
| 코드 수정·서버 패치 0회 | 이 브랜치 변경이 문서뿐, 서버 작업 기록 | 저장소·운영 | 0회 |

- 원자료: `.local/evidence/0055-second-app-demo/2026-10-10/RESULT.md` (Actions run ID, 배포 ID, 응답)

## Tasks
### T1. 샘플 저장소
- 변경: `kjhyeon0620/klepaas-sample-node` 생성, v1 코드·테스트·Dockerfile·workflow push
- 검증: Actions에서 테스트·arm64 이미지 push 성공 (배포 단계는 등록 전이라 실패 예상)
### T2. 등록 (사용자)
- GitHub App을 샘플 저장소에 설치, 콘솔에서 등록·배포 설정 입력, GHCR 패키지 공개 확인
- 샘플 저장소 secret `KLEPAAS_DEPLOY_URL`, `KLEPAAS_REPOSITORY_ID` 입력 (운영 URL은 사용자만 앎)
### T3. 시나리오 S1~S7
- 에이전트: 코드 커밋(S2·S3)과 workflow 수동 실행, Actions 기록 정리
- 사용자: 콘솔 설정 변경(S6·S7), 상태·Logs·curl 결과 전달
### T4. 결과 기록과 문서
- 이 스펙의 결과 표, `product.md`, 필요 시 `ORACLE_K3S_GHCR_DEPLOYMENT.md`(두 번째 앱 예시)

## 결과
2026-10-10 운영 실행. 샘플 저장소 [kjhyeon0620/klepaas-sample-node](https://github.com/kjhyeon0620/klepaas-sample-node), K-Le-PaaS 저장소 ID 2, NodePort 30081.

| 단계 | 배포 | 상태 | 근거 | 응답 |
|---|---|---|---|---|
| S1 v1 | #5 | `SUCCESS` | Logs `AVAILABLE`, Pod 로그 v1.0.0 | - |
| S2 v2 | #6 | `SUCCESS` | Pod 로그 v2.0.0 | - |
| S3 health `/ready` | #7 | `FAILED` | `rollout 타임아웃(120초, generation=3) ... 실행 중이나 Ready 아님`, 이벤트 `Readiness probe failed: HTTP probe failed with statuscode: 404` ×24 | - |
| S4 없는 이미지 | #8 | `FAILED` (16초) | `ImagePullBackOff`, 이미지 not found | - |
| S3·S4 뒤 | - | - | - | `{"version":"2.0.0"}`, IoT HTTP 200 |
| S5 v1 이미지 복구 | #9 | `SUCCESS` | Pod 로그 v1.0.0 | - |
| S6 env 누락 | #10 | `FAILED` (5초) | `CrashLoopBackOff ... 마지막 종료=Error, exit=1`, 직전 컨테이너 로그 "SAMPLE_GREETING 환경 변수가 없습니다. 종료합니다." | - |
| S7 설정 복구 | #11 | `SUCCESS` | 기존 v1 Pod가 그대로 Ready (S6 동안 내려가지 않음) | `{"version":"1.0.0"}`, IoT HTTP 200 |

- 응답은 서버 안 `curl http://127.0.0.1:30081/version`(사용자 실행, 읽기)으로 확인했다. 실패 단계마다 이전 버전이 계속 응답했고 IoT 앱은 영향이 없었다.
- **이미지 재배포와 설정 복구의 차이**: S5는 이미지 태그만 바꿔 복구했고, S7은 사람이 배포 설정(env)을 되돌린 뒤 같은 이미지로 재배포했다. 배포 #10(실패)과 #11(성공)은 기록된 commit·이미지가 같다. 배포 기록에 설정이 남지 않아 이력만으로는 실패 원인(env 누락)과 복구 내용을 구분할 수 없다. 이미지 롤백은 설정 문제를 고치지 못한다.
- **OIDC와 이전 이미지**: #8~#11은 모두 최신 main(v3, `c080f65`)에서 실행해 기록된 commit은 v3이고 이미지는 v1·없는 태그다. 기록의 commit이 실행 중인 코드와 다를 수 있다.
- K-Le-PaaS 코드 수정 0회(이 브랜치는 문서만 바꾼다), 서버 수동 패치 0회(서버 작업은 사용자 `curl` 2회).
- 실증 뒤 샘플 저장소 main을 v3 이전 코드로 되돌렸다(`4563dda`, revert of `c080f65`, `[skip ci]`로 배포 없음). 운영은 배포 #11(v1 이미지)로 유지된다.
- 발견한 이슈 후보: 배포 기록에 설정 스냅샷 없음(로드맵 배포 요청 상세, #56과 연결), NodePort 전용 앱도 `domain_url` 필수, CLI 요청 함수가 env 이름 같은 map 키까지 snake_case로 바꿈. [architecture §9](../../architecture.md#9-알려진-격차)에 기록했다.

## 운영 반영
- (머지 후 기록)

## 회고
- 어긋난 점: (1) 콘솔 배포 설정 입력이 사용자에게 너무 어려워 API 스크립트로 대신했다. 두 번째 앱을 "설정만으로" 올리는 경로가 실제로는 설정 화면 사용성에 막혀 있었다. (2) NodePort 전용 앱도 `domain_url`이 필수였다. (3) 공개 저장소에서 짧은 숫자 secret이 로그 전체를 가렸다.
- 원인 분류: 스펙 누락 (등록·설정 단계의 사용성과 필수 필드를 확인하지 않음)
- 고친 위치: 이번 범위에서는 코드를 고치지 않는다. 설정 입력 사용성과 `domain_url` 필수 여부, 배포 기록의 설정 스냅샷은 이슈 후보로 architecture §9에 남겼다. 샘플 workflow는 저장소 ID를 variable로 바꿨다.
