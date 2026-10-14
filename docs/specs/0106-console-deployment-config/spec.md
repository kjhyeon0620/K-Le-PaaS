---
issue: 106
title: "feat: 콘솔에서 새 앱 배포 설정 입력·검증"
status: done
size: M
type: feat
branch: feat/#106-console-deployment-config
---

# feat: 콘솔에서 새 앱 배포 설정 입력·검증

## 목적 / 성공 조건
- 목적: 새 앱을 등록한 사용자가 API·스크립트 없이 콘솔만으로 배포 설정을 입력·저장할 수 있다. #55에서 사용자가 콘솔 입력을 포기하고 API로 대신 넣은 장벽을 없앤다.
- 기술적 완료: 콘솔 설정 화면에서 #55 샘플 앱에 필요한 항목(빌드 방식, 이미지 템플릿, 포트, 서비스 타입·NodePort, 레플리카, probe, 자원, env 값, pull secret 사용 여부)을 모두 입력할 수 있다. 잘못된 값은 저장되지 않고 이유가 해당 항목 근처에 보인다. 저장 전에 무엇이 바뀌는지 확인할 수 있다.
- 운영: 저장한 설정은 다음 배포부터 적용된다는 것을 화면에서 안내한다(지금과 같다). 저장 실패 시 입력값이 지워지지 않는다.

## 현재 동작 (2026-10-14 코드 확인)
- 진입: GitHub 화면 저장소 카드의 Configure → 배포 화면 대화상자의 Configuration 탭(`deployment-status-monitoring.tsx`). 배포가 없는 새 저장소도 열린다.
- Configuration 탭은 4개 구역이 각자 저장 버튼을 가진다: Build & service(빌드 방식, 서비스 타입, 이미지 템플릿, pull secret 이름, NodePort, 컨테이너 포트, 도메인), Runtime envFrom(ConfigMap·Secret 이름), Image pull secret 사용 여부, Health probe & resources.
- **없는 항목: env 값(`env_vars`, 예 `SAMPLE_GREETING=hello`), 레플리카(min·max).** 레플리카는 배포 화면의 Scale로만 바꾸고, env 값은 콘솔에서 넣을 방법이 없다.
- 각 저장은 `getDeploymentConfig` 후 해당 구역 값만 덮어 `PUT /api/v1/repositories/{id}/config`(전체 필드)를 보낸다(`putDeploymentConfig`, #66 기본값 덮어쓰기 방지).
- 오류: 서버는 `ErrorResponse`(`code`, `message` 한 줄)만 돌려준다. 필드 정보가 없다. 콘솔은 실패를 토스트(`Save failed` + message)로만 보인다.
- 서버 검증(`UpdateDeploymentConfigRequest`·`RepositoryService`): `min_replicas` ≥ 0, `max_replicas` ≥ 1, `container_port` ≥ 1, `node_port` 30000–32767, NODE_PORT면 node_port 필수, CLUSTER_IP면 node_port 금지, `domain_url`은 DNS host 형식이며 NODE_PORT 앱만 비울 수 있음, envFrom 이름은 Kubernetes 이름 형식, 자원 request ≤ limit·양수, probe 값 범위(`@Valid HealthProbe`). 허용 참조(ConfigMap·Secret·pull secret)는 저장이 아니라 배포 때 검사한다.
- 화면 문구는 영어, 레이아웃은 [frontend/DESIGN.md](../../../frontend/DESIGN.md)(카드·대화상자 탭, 라벨 항상 표시, 중첩 카드 금지)를 따른다. 사이드바 모바일 미접힘은 #109.

## 범위
- 포함:
  - Configuration 탭에 env 값 편집(이름·값 행 추가·삭제)과 레플리카(min·max) 입력을 추가한다.
  - 4개 구역의 저장 버튼을 하나로 합치고(한 번의 PUT), 저장 전 변경 확인 대화상자를 둔다(Q2).
  - 클라이언트에서 서버와 같은 규칙으로 미리 검사해 해당 항목에 이유를 보인다(서버 검사를 대신하지 않는다).
  - env 이름 형식 검사: 클라이언트에서 `[A-Za-z_][A-Za-z0-9_]*`, 중복·빈 이름 금지. 서버는 바꾸지 않는다(Q3).
  - 데스크톱·375px 폭 확인.
- 제외:
  - 새 백엔드 설정 항목(이슈 본문).
  - 저장소 등록 흐름 변경, 배포 실행 버튼.
  - 허용 참조(운영자 소유 ConfigMap·Secret 목록) 편집.
  - 사이드바 모바일 접힘(#109).

## 입출력·계약
- API: `PUT /api/v1/repositories/{id}/config` 요청·응답 그대로. 백엔드 변경 없음.
- env 값: 응답의 `env_vars`를 그대로 쓴다. 화면에서는 값을 기본으로 가리고 보기 버튼으로 보인다. 저장하면 보낸 맵 전체로 교체된다(지금 서버 동작).
- 스키마 변경 없음.

## 권한·안전
- 저장은 지금처럼 Web JWT(FULL)와 소유권 검사를 거친다. 새 endpoint 없음.
- env 값은 비밀번호일 수 있다. 화면에서 기본으로 가리고, 콘솔 로그·토스트·변경 확인 목록에 값을 내지 않는다(이름만). 비밀값은 Kubernetes Secret(envFrom)을 쓰라는 안내를 둔다.
- 저장 전 변경 확인은 화면의 입력값과 마지막으로 불러온 설정을 비교한 것이다. 다른 탭·사용자가 그 사이에 바꾼 값은 덮어쓴다(지금과 같은 마지막 저장 우선).

## 제약
- 참조: [frontend/DESIGN.md](../../../frontend/DESIGN.md), [스펙 0078](../0078-console-deployments-api-contract/spec.md)(#66 덮어쓰기 방지), [스펙 0051](../0051-app-probes-resources/spec.md)(probe·자원)
- 조회 실패를 기본값으로 바꾸지 않는다(#66). 설정을 불러오지 못하면 저장을 막는다.
- 프론트엔드에서 백엔드에 없는 기능을 있는 것처럼 보이지 않는다.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| NodePort 서비스인데 NodePort 비움 | NodePort 항목 아래 "NODE_PORT 서비스는 NodePort가 필요합니다" | 저장 요청 안 보냄 |
| env 이름 중복·형식 오류·빈 이름 | 해당 행 아래 이유 | 저장 요청 안 보냄 |
| 서버가 400으로 거절 (클라이언트가 못 잡은 규칙) | 저장 버튼 근처에 서버 message, 입력값 유지 | 다시 불러오지 않음 |
| 설정 조회 실패 | 오류와 다시 시도 버튼, 저장 버튼 비활성 | 기본값으로 채우지 않음 |
| 바뀐 것이 없음 | 저장 버튼 비활성 또는 "변경 없음" | 요청 안 보냄 |
| 375px 폭 | 입력·표가 가로로 넘치지 않음 | - |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-14 Q1 오류 표시 → 클라이언트가 서버와 같은 규칙으로 미리 검사해 항목별로 이유를 보이고, 그래도 서버가 거절하면 서버 message를 저장 버튼 근처에 보인다(입력값 유지). 백엔드·API 계약은 바꾸지 않는다. 서버 규칙이 바뀌면 클라이언트 검사도 맞춰야 한다(서버가 최종 판정이므로 어긋나도 잘못 저장되지는 않는다). (사용자 결정)
- 2026-10-14 Q2 저장 방식 → 한 화면, 저장 버튼 하나로 모든 항목을 한 번의 PUT으로 저장한다. 저장 전에 바뀌는 항목 목록(이전 → 새 값, env는 이름만 추가·삭제·변경 표시)을 확인 대화상자로 보이고 확인해야 저장한다. 바뀐 것이 없으면 저장 버튼을 비활성화한다. (사용자 결정)
- 2026-10-14 Q3 env 이름 검사 → 클라이언트만 한다. 서버·API·CLI 경로는 지금 그대로다. (사용자 결정)
- 2026-10-14 등급 M. 백엔드 변경 없음, 화면 하나. (docs/specs/README.md §3) (docs/specs/README.md §3)
- 2026-10-14 화면 문구는 지금 Configuration 탭처럼 영어로 둔다. 같은 화면 안에서 언어를 섞지 않는다. (기본값)
- 2026-10-14 env 값은 기본으로 가리고 보기 버튼을 둔다. 값이 비밀번호일 수 있고, 같은 화면을 다른 사람이 볼 수 있다. (기본값)
- 2026-10-14 검증 환경: 로컬 mock API + 브라우저(데스크톱·375px)로 입력·오류·저장 흐름을 확인하고, 로컬 백엔드(패키징 JAR + PostgreSQL)로 #55 샘플 앱 설정 저장·재조회를 확인한다. 운영 콘솔 확인은 사용자가 한다. (기본값)

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| #55 샘플 앱 설정 전체를 콘솔만으로 입력·저장, 다시 열면 같은 값 | 브라우저 + 로컬 백엔드 | 로컬 (패키징 JAR + PostgreSQL) |
| 잘못된 값은 저장되지 않고 항목에 이유 표시, 서버 거절도 표시·입력 유지 | 브라우저 + mock API | 로컬 |
| 저장 전 변경 확인(env 값 미노출) | 브라우저 | 로컬 |
| 데스크톱·375px 넘침 없음 | 브라우저 DOM 측정·스크린샷 | 로컬 |
| 타입·빌드 | `npx tsc --noEmit`, `npm run build` | 로컬 |
| 운영 | 사용자가 운영 콘솔에서 샘플 앱 env를 바꿔 저장·재배포 | 운영 |

### 실행 결과 (2026-10-14)
- 로컬: `node --experimental-strip-types --test lib/deployment-config-form.test.ts` 5개(#55 샘플 설정 검사 통과·PUT 본문 왕복, NodePort 필수·범위, 도메인 필수·형식·대소문자, 포트·레플리카, env 이름 형식·중복·빈 이름, envFrom 대문자 거절, probe path, 자원 양수·request ≤ limit 단위 환산, 변경 목록에 env 값 미노출) 통과. `npx tsc --noEmit` 통과.
- 로컬 mock API + 브라우저(데스크톱 1024px):
  - 새로 등록한 저장소(배포 없음, KANIKO·CLUSTER_IP·기본 도메인)에서 Config 버튼으로 설정 탭을 열고, #55 샘플 앱 설정 전체를 입력했다.
  - NodePort 없이 확인 → NodePort 아래 "A NodePort service needs a NodePort.", 확인 대화상자 열리지 않음. env 이름 `sample-greeting` → 행 아래 형식 오류.
  - 확인 대화상자: 바뀐 10개 항목(이전 → 새 값), env는 `Env SAMPLE_GREETING (none) → added`만 보이고 값은 보이지 않음. 저장 → PUT 1회, 본문이 #55 설정과 같음(`SAMPLE_GREETING=hello`, NodePort 30081, `/health` period 5·failure 3, pull secret off, 도메인 빈 값). 저장 후 버튼 "No changes".
  - 입력 중 30초 목록 갱신(설정 GET 재조회)이 지나도 입력값 유지.
  - 서버 거절(다른 앱이 쓰는 도메인, 409): 저장 버튼 근처에 "The server rejected the settings: 이미 존재하는 리소스입니다 (HTTP 409)", 입력값 유지.
- 로컬 mock API + 브라우저(375px): 대화상자·페이지 가로 넘침 없음(DOM 측정). env 이름이 잘려 보여 모바일에서는 이름을 한 줄, 값·버튼을 다음 줄에 두도록 바꿨다(이름 폭 90px → 275px). 데스크톱은 한 줄 그대로.
- 로컬 패키징 JAR + PostgreSQL 16 + 브라우저(데스크톱): 새 저장소의 기본 설정에서 콘솔만으로 #55 샘플 앱 설정을 입력·저장 → API 조회 결과가 입력과 같음(`domain_url` null, env 1개, probe, pull secret false) → 페이지를 새로 고쳐 설정 탭을 다시 열면 같은 값, "No changes". 인증은 로컬 DB에 넣은 테스트 사용자·CLI 토큰(로컬 전용)을 썼다.
- 하지 않은 검증: 운영 콘솔 확인(사용자), `npm run build`(아래 회귀에서 실행).
- 증거: mock API `.local/tmp/mock106.mjs`, 실행 설정 `.claude/launch.json`(둘 다 git 제외).

## 문서 동기화
- `docs/architecture.md` §9의 "콘솔 배포 설정 입력이 어렵다" 항목 삭제
- `docs/product.md` #106 행, README 현재 상태 표(필요 시)

## 운영 반영
- (머지 후 배포 workflow 결과와 사용자 운영 확인)

## 회고
- 어긋난 점: (1) #55의 "입력이 어렵다"는 화면 구성 문제만이 아니었다. env 값·레플리카 입력 칸이 없었고, 30초 목록 갱신이 입력 중인 값을 서버 값으로 덮어써 사실상 입력을 지웠다. (2) 목록의 Config 버튼이 설정 탭이 아니라 Latest Deployment 탭을 열었다. (3) 클라이언트 검사를 서버 규칙에 맞추면서 envFrom 이름(소문자 강제 없음)과 domain(소문자로 바꾼 뒤 검사)의 차이를 처음에 놓쳤다.
- 원인 분류: 스펙 누락(#55 회고가 "사용성"으로만 남기고 무엇이 막았는지 화면에서 확인하지 않음), 테스트 부재(화면 상태 초기화 효과에 대한 확인 없음).
- 고친 위치: 설정 폼을 열 때 한 번만 초기화하는 별도 컴포넌트(`key`로 저장소별 마운트), Config 버튼이 설정 탭을 열도록 수정, 검사 규칙을 서버 함수별로 나누고(`isKubernetesName`·`isDnsHost`) `deployment-config-form.test.ts`에 대문자 사례를 넣었다. 실증 회고(#55)에서 막힌 입력은 화면에서 재현해 원인을 적는다 — 이 스펙의 현재 동작 절에 남겼다.
