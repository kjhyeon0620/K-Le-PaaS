---
issue: 144
title: "feat: 자연어 재시작·스케일·롤백을 앱 이름으로 지정"
status: done
size: M
type: feat
branch: feat/#144-nl-target-by-name
---

# feat: 자연어 재시작·스케일·롤백을 앱 이름으로 지정

## 목적 / 성공 조건
- 목적: 사용자가 배포 ID를 몰라도 "sample-node 재시작해줘", "sample-node 3개로 늘려줘", "sample-node 롤백해줘"처럼 앱 이름으로 변경 명령을 낼 수 있다. 롤백(이미 이름으로 동작)과 재시작·스케일의 대상 지정 방식을 맞춘다.
- 기술적 완료: 서버가 이름을 요청 사용자 소유 저장소 하나로 해석하고, 해석한 대상을 인자에 고정해 승인 대기 명령을 만든다. 승인 화면(`approval_target`)에 실제 저장소·배포가 보이고, 승인 후에는 다시 찾지 않고 고정한 대상에 실행한다.
- 운영: 이름이 하나로 정해지지 않으면 승인 요청 없이 후보를 보여 주며 되묻는다(#135와 같은 `FAILED` 기록). 사람은 승인 화면에서 어느 앱이 바뀌는지 보고 승인한다.

## 현재 동작 (2026-10-14 코드 확인)
- SCALE·RESTART: `ActionDispatcher`가 `deployment_id`만 읽는다. `DeploymentService.scaleDeployment`·`restartDeployment`는 그 배포의 저장소를 찾아 Kubernetes Deployment `owner-repoName`에 적용한다. 같은 저장소면 어느 배포 ID든 결과가 같다. #135 이후 `deployment_id`가 없으면 승인 전에 되묻는다.
- ROLLBACK: `owner`·`repo`가 모두 있어야 한다. `findByOwnerAndRepoName`으로 저장소를 찾은 뒤 `requireRepository`로 소유권을 본다. 승인 대상은 `ApprovalTargetResolver`가 복구 계획 지문으로 고정한다(#56·#101).
- 승인 대상 표시(`approval_target`)는 DEPLOY·ROLLBACK에만 있다. SCALE·RESTART 승인 화면에는 대상이 보이지 않는다.
- 사용자가 부르는 이름과 저장소 이름이 다를 수 있다(운영 예: 저장소 `klepaas-sample-node`, 부르는 이름 `sample-node`).

## 범위
- 포함:
  - 이름 해석: `app_name`(또는 `repo`, 선택 `owner`)을 요청 사용자 소유 저장소(`findAllByUserId`) 안에서 찾는다. 규칙은 아래 "이름 해석 규칙".
  - SCALE·RESTART: 해석한 저장소의 가장 최근 성공 배포 ID를 `deployment_id`로 인자에 넣고 승인 대기 명령을 만든다. 성공 배포가 없으면 되묻지 않고 이유를 알려 준다(실행할 대상이 없음).
  - ROLLBACK: `owner`가 없으면 같은 규칙으로 저장소를 찾아 `owner`·`repo`를 채운다. 그 뒤 승인 대상·복구 계획 고정은 지금과 같다.
  - LIST_ROLLBACK(롤백 후보 조회, LOW): 프롬프트에서 `owner`가 선택이 되므로 같은 규칙으로 저장소를 찾는다. 정하지 못하면 조회하지 않고 되묻는다. (2026-10-14 구현 중 추가: 프롬프트 변경의 영향)
  - SCALE·RESTART 승인 화면에 대상 표시: `approval_target`에 저장소(ID·이름), 대상 배포의 브랜치·commit·이미지, 설정의 최소·최대 레플리카를 넣는다. 설정 지문(`config_fingerprint`)은 null이다(재시작·스케일은 설정을 적용하지 않는다).
  - 프롬프트: SCALE·RESTART는 `deployment_id` 또는 `app_name`, ROLLBACK·LIST_ROLLBACK은 `repo`(필수)·`owner`(선택). 이름도 ID도 없으면 묻는다.
  - #58 평가 재측정. 평가 사례는 바꾸지 않는다(이름 해석은 서버 몫이고, 평가는 모델 해석을 채점한다).
- 제외:
  - DEPLOY의 이름 지정(저장소 ID 필수 유지).
  - 위험도 규칙, 승인 확인(`confirm`) 로직, SCALE·RESTART 설정 지문 고정.
  - 오타 교정·유사도 검색.

## 이름 해석 규칙
요청 사용자 소유 저장소만 대상이다. 비교는 대소문자를 무시한다.
1. `owner/repo` 형식이거나 `owner`가 주어지면 `owner`·`repo_name`이 모두 같은 저장소.
2. 아니면 `repo_name`이 이름과 같은 저장소.
3. 1·2에서 정확히 하나면 그 저장소로 정한다. 둘 이상이면 후보 목록과 함께 되묻는다.
4. 정확히 같은 것이 없으면 `repo_name`에 이름이 들어 있는 저장소를 후보로 본다. 하나면 그 저장소로 정하고, 둘 이상이면 후보와 함께 되묻는다.
5. 후보가 없으면 "그 이름의 저장소가 없습니다"와 사용자 저장소 이름 목록을 함께 되묻는다.

## 입출력·계약
- API·CLI 필드, 에러 코드, exit code, 스키마 변경 없음.
- `approval_target`이 SCALE·RESTART에도 채워진다(지금은 null). 필드는 기존 그대로이고 `config_fingerprint`는 null이다.
- 명령 기록 `intent_args`에는 해석한 `deployment_id`(SCALE·RESTART) 또는 `owner`·`repo`(ROLLBACK)가 들어간다. 승인 실행은 이 값으로 한다.
- 되묻는 응답은 #135와 같다: `requires_confirmation=false`, `FAILED` 기록, `message`에 이유와 후보.

## 권한·안전
- 이름은 요청 사용자 소유 저장소 안에서만 찾는다. 다른 사용자 저장소는 이름이 같아도 후보에 나오지 않고 존재도 드러내지 않는다.
- 실행 시점의 소유권 검사(`ResourceAccessService`)는 그대로다.
- 해석한 대상을 인자에 고정하므로 승인과 실행 사이에 이름 해석이 바뀌지 않는다.
- 해석은 DB 조회만 한다. Kubernetes를 호출하지 않는다.
- 위험도는 그대로다(SCALE·RESTART MEDIUM, ROLLBACK HIGH).

## 제약
- 참조: [architecture](../../architecture.md) §3.1, [스펙 0135](../0135-scale-restart-target/spec.md), [스펙 0056](../0056-pin-approved-deployment-config/spec.md), [스펙 0101](../0101-recover-previous-deployment/spec.md)
- 이름 해석은 승인 대기 저장 전에 한 번만 하고, `ChangeTargets` 판정은 해석 뒤의 인자로 한다.
- 에이전트 복구 제안(`proposeRecovery`)은 대상 배포 ID를 지정하므로 바뀌지 않는다.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| "sample-node 재시작해줘", 저장소 `klepaas-sample-node` 하나 | 승인 요청, `approval_target`에 `kjhyeon0620/klepaas-sample-node` | 부분 일치 후보 하나라 그 저장소로 정함 |
| "klepaas-sample-node 재시작해줘" | 승인 요청, `approval_target`에 `kjhyeon0620/klepaas-sample-node` | 최근 성공 배포 ID 고정 |
| 같은 `repo_name`의 저장소가 둘 (owner 다름) | 후보 2개와 함께 되물음 | `FAILED` 기록, 승인 대기 없음 |
| 그 이름의 저장소 없음 | 사용자 저장소 목록과 함께 되물음 | `FAILED` 기록 |
| 저장소는 있지만 성공 배포 없음 | "실행 중인 배포가 없습니다" | `FAILED` 기록 |
| 다른 사용자만 가진 이름 | "그 이름의 저장소가 없습니다" (본인 목록만) | 존재를 드러내지 않음 |
| `deployment_id`도 주어짐 | 지금과 같다 (ID 우선) | 이름 해석 안 함 |
| 승인 대기 중 같은 저장소에 새 배포 | 영향 없음 | 재시작·스케일은 저장소 단위라 결과가 같다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-14 Q1 부분 일치 후보가 하나뿐이면 → 그 저장소로 정한다. 승인 화면(`approval_target`)에 실제 `owner/repo`가 보이고 사람이 승인 전에 확인한다. 부분 일치 후보가 둘 이상이면 정하지 않고 후보를 보이며 되묻는다. (사용자 결정)
- 2026-10-14 Q2 롤백 → 같은 규칙으로 맞춘다. `owner` 없이 저장소 이름만으로 지정할 수 있다. 중복은 요청 사용자 저장소 안에서만 세고, 둘 이상이면 되묻는다(다른 사용자 저장소는 후보에 나오지 않는다). (사용자 결정)
- 2026-10-14 (구현 중) 사실 확인: `repositories` 테이블에 `owner`·`repo_name` 유일 제약이 없다. 기존 롤백의 `findByOwnerAndRepoName`(Optional)은 같은 조합이 둘이면 오류가 날 수 있다. 새 해석은 사용자 저장소 목록에서 후보로 세므로 이 경로를 쓰지 않는다. 중복 등록이 실제로 가능한지는 범위 밖으로 확인만 한다.
- 2026-10-14 등급 M. 승인 대기 생성 조건, 승인 대상 표시, 프롬프트가 바뀐다. 스키마·API 필드 변경은 없다. (docs/specs/README.md §3)
- 2026-10-14 SCALE·RESTART 대상 배포는 해석한 저장소의 가장 최근 성공 배포다. 재시작·스케일은 저장소 단위 Kubernetes Deployment에 적용되므로 결과가 같고, 이력(스케일 기록)이 실제로 돌고 있는 배포에 붙는다. (구현 재량, 기본값)
- 2026-10-14 이름이 있어도 `deployment_id`가 주어지면 ID를 우선한다. 지금 동작과 #135 경로를 그대로 둔다. (기본값)

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 이름 해석 규칙(정확 일치, owner 포함, 중복, 부분 일치, 없음, 대소문자) | 해석기 단위 테스트 | 로컬 |
| 해석 성공 시 `deployment_id` 고정·`approval_target` 표시, 실패 시 `FAILED`·되물음, 승인 시 고정한 대상에 실행 | `NlpCommandServiceTest`, `ActionDispatcherTest` | 로컬 |
| 다른 사용자 저장소는 같은 이름이어도 찾지 않음, 실제 HTTP에서 이름 재시작 → 승인 → 재시작 호출 | `Stage2HttpAccessTest` | 로컬 (Testcontainers) |
| 새 프롬프트로 #58 수준 유지 | `NlpModelEvalTest` `openai:gpt-6-luna` 30 × 5 | 로컬 + 외부 모델 |
| 회귀 | PR 전 `./gradlew test` 1회 | 로컬 |
| 운영 | 사용자 `ask "sample-node 재시작해줘"` → 승인 화면 확인 → 승인 → 재시작 | 운영 |

### 실행 결과 (2026-10-14)
- 로컬: `NameTargetsTest` 7개(부분 일치 하나 → 최근 성공 배포 고정, 정확 일치 우선, 같은 이름 둘 → 후보와 되물음, `owner/repo` 지정, 부분 일치 여럿·owner 지정 시 부분 일치 미사용, 롤백·롤백 목록 owner·repo 채움, 없는 이름은 본인 목록만, 성공 배포 없음, ID 우선·이름 없음·다른 intent는 DB 조회 없음), `NlpCommandServiceTest`(이름 해석 결과 `deployment_id`가 기록에 고정되고 `approval_target`에 실제 저장소, 정하지 못하면 `FAILED`·되물음·승인 대상 조회 없음) 통과.
- 로컬 (Testcontainers PostgreSQL, 실제 HTTP): `Stage2HttpAccessTest`에서 A가 "service"(본인 저장소 `a-stage2/service`)로 재시작 → 승인 대기·`approval_target.repository`=`a-stage2/service` → 승인 → `generator.restart("a-stage2-service", repo)` 호출. B가 같은 이름으로 요청하면 승인 대기 없이 되묻고 응답에 A 저장소가 나오지 않는다.
- 로컬: `./gradlew test` 312개 통과(건너뜀 2: 실제 모델 평가), CLI 테스트 10개, `npx tsc --noEmit` 통과. CLI·콘솔 승인 대상의 설정 지문이 null이면 `-`로 보인다.
- 로컬 + 외부 모델 (`NlpModelEvalTest`, `openai:gpt-6-luna`, 30 × 5):

| 프롬프트 | 파싱 | intent | 인자 | 날조 | 과소 | 과대 | 확인 없이 변경 | 사례 다수결 | 금액 USD |
|---|---|---|---|---|---|---|---|---|---|
| #135 `1599f83e7208` | 150/150 | 140/150 | 140/140 | 0 | 0 | 10 | 0 | 28/30 | 0.0275 |
| 1차 `27d6f83a60e3` | 150/150 | 141/150 | 137/141 | 0 | 0 | 9 | 0 | 28/30 | 0.0289 |
| 최종 `f65d4a37e6d3` | 150/150 | 141/150 | 141/141 | 0 | 0 | 9 | 0 | 28/30 | 0.0294 |

  - 1차에서 인자 오답 4회는 모두 롤백 계열에서 `acme/sample-node`를 나누지 않고 `repo`에 통째로 넣은 것이다(`owner`를 선택으로 바꾸면서 나누는 규칙이 사라짐). 서버 해석은 슬래시를 나눠 처리하므로 동작은 같지만, 프롬프트 계약을 분명히 하려고 ROLLBACK·LIST_ROLLBACK 표에 "owner/repo 형식이면 나눠 넣음"을 적고 다시 쟀다.
  - 이름만 준 요청: `sample-node 좀 늘려줘` → `SCALE {app_name: "sample-node"}` 5/5(이름은 서버가 해석, 레플리카 수가 없어 #135 판정으로 되물음), `옆 팀 billing-api 재시작…` → `SCALE {app_name: "billing-api", replicas: 1}` 5/5(본인 저장소에 없으면 되물음, 있어도 실행 시 소유권 검사).
- 비용: 측정 2회 약 $0.06.
- 증거: `.local/evidence/0144-nl-target-by-name/` (평가 결과 JSON·MD 2개).

## 문서 동기화
- `docs/architecture.md` §3.1 (이름 해석, SCALE·RESTART 승인 대상 표시)
- `docs/CLI_REFERENCE.md` `ask` 예시 (이름 지정)
- `docs/product.md` #144 행

## 운영 반영
- (머지 후 배포 workflow 결과와 사용자 운영 확인)

## 회고
- 어긋난 점: (1) #135를 머지한 뒤에 "이름만으로 동작해야 한다"로 방향이 바뀌었다. #135 스펙 질문에서 이름 검색을 "범위가 크다"로만 설명하고, 재시작·스케일에서 배포 ID가 저장소를 찾는 데만 쓰인다는 사실(이름 해석의 비용이 작다)을 보여 주지 않았다. (2) 프롬프트에서 롤백의 `owner`를 선택으로 바꾸자 모델이 `owner/repo`를 나누지 않았다. (3) LIST_ROLLBACK도 같은 프롬프트 변경의 영향을 받아 범위에 넣었다. (4) 재사용하려던 조회(`findTop20…ImageUriIsNotNull`)는 복구 후보용이라 이미지가 없는 성공 배포를 빠뜨렸다.
- 원인 분류: 스펙 누락(#135 질문 설계에서 선택지의 실제 비용을 코드로 확인하지 않음), 테스트 부재(프롬프트 계약 변경의 영향은 평가를 다시 돌려야 드러남).
- 고친 위치: #135 대신 이 스펙에서 이름 해석·승인 대상 표시. 프롬프트 표의 owner 분리 규칙. 사용자 질문의 선택지마다 비용을 코드로 확인해 적는 것은 [docs/specs/README.md 질문 규칙](../README.md#질문-규칙)의 "코드나 기존 문서를 읽으면 답이 나오는 것은 직접 확인한다"에 이미 있다. 규칙을 더하지 않고 이 회고로 남긴다.
