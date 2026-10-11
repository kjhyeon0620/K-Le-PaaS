---
issue: 117
title: AI 진단 결과 재사용과 근거 없는 입력의 모델 호출 생략
status: done
size: M
type: feat
branch: feat/#117-diagnosis-result-reuse
---

# AI 진단 결과 재사용과 근거 없는 입력의 모델 호출 생략

## 목적 / 성공 조건
- 목적: 진단 요청 하나의 토큰을 줄이는 대신(#115) 모델 호출 자체를 줄인다. 같은 배포를 다시 진단하면 저장한 결과를 돌려주고, 근거가 없는 입력은 모델 없이 판단 보류로 답한다.
- 기술적 완료: 진단 결과를 이력으로 저장하고, 재사용·다시 진단·호출 생략이 API·CLI·콘솔에서 동작한다. 호출 횟수를 테스트로 확인한다.
- 운영: 저장한 결과의 토큰 사용량으로 운영 진단 비용을 집계할 수 있다.

## 현재 동작 (#100, #115)
- `POST /api/v1/deployments/{id}/diagnosis`는 요청마다 상세·로그를 조회(Kubernetes 호출)하고 모델을 호출한다. 결과는 저장하지 않는다.
- 진단 대상은 FAILED·UNKNOWN 배포다. FAILED는 끝난 상태라 원인이 바뀌지 않지만, CrashLoop Pod의 재시작 횟수·이벤트 수는 계속 바뀌어 입력이 완전히 같기는 어렵다.
- #99 증거 부족 사례 2개는 Pod·이벤트·설정 변경이 없고 실패 종류가 ROLLOUT_TIMEOUT이며, 정답은 판단 보류다.

## 범위
- 포함: 진단 이력 테이블, 배포별 최근 결과 재사용, `refresh`로 다시 진단, 근거 없는 입력의 호출 생략, CLI `--refresh`, 콘솔 표시, 문서.
- 제외: 의미 기반 캐시, 사용자별 진단 한도, 이력 조회 API, 보관 기간·정리 작업, 프롬프트·모델 변경.

## 동작
1. 상세 조회로 소유권을 확인하고 FAILED·UNKNOWN만 받는다(지금과 같음). 권한 밖이면 저장 결과도 돌려주지 않는다.
2. `refresh`가 아니면 그 배포의 최근 저장 결과 중 재사용할 수 있는 것을 돌려준다. 이때 로그 조회와 모델 호출을 하지 않는다.
   - 재사용 조건: 호출 생략 결과(`no-evidence-v1`)이거나, 현재 프롬프트 버전과 현재 설정 모델(`diagnosis.llm.model`)로 만든 결과.
3. 재사용할 결과가 없거나 `refresh=true`면 로그를 조회한다.
4. 근거 없는 입력이면 모델을 부르지 않고 판단 보류로 응답한다. 조건(모두 만족):
   - 로그 응답의 Pod·이벤트가 비어 있다.
   - 상세의 설정 변경(`changes`)이 비어 있다.
   - 실패 종류가 없거나 원인을 가리키지 않는 값(ROLLOUT_TIMEOUT·PROGRESS_DEADLINE·DEPLOYMENT_MISSING)이다. BUILD_FAILED·APPLY_FAILED·OTHER 등은 실패 사유 문구에 원인이 있을 수 있어 모델을 부른다.
   - 응답: `model` = `rule`, `prompt_version` = `no-evidence-v1`, 판단 보류, 근거 없음, warnings에 "관측 근거가 없어 모델을 호출하지 않았습니다".
5. 그 밖에는 지금처럼 모델을 호출한다.
6. 4·5의 결과를 이력으로 저장(행 추가)하고 돌려준다. 모델 호출이 실패하거나 응답을 읽을 수 없으면 저장하지 않는다.

## 입출력·계약
- API: `POST /api/v1/deployments/{id}/diagnosis?refresh=true` (선택, 기본 false). 권한은 진단과 같다(READ_ONLY 이상, 사용자 결정).
- 응답 필드 추가: `reused`(저장 결과를 돌려줬으면 true), `diagnosed_at`(결과를 만든 시각). 기존 필드는 그대로다.
- CLI: `klepaas deployments diagnose <id> [--refresh]`. 사람용 출력에 저장 결과 여부와 시각을 표시한다.
- 콘솔: 저장 결과면 "저장된 결과 · 시각"을 표시하고, "다시 진단"은 `refresh=true`로 호출한다.
- 저장: 새 테이블 `deployment_diagnoses` (Flyway V8, 새 테이블이라 이전 릴리스는 읽지 않는다).
  - `deployment_id`(FK), `requested_model`(설정 모델 또는 `rule`), `prompt_version`, `result`(응답 JSON 텍스트), `input_tokens`·`cached_input_tokens`·`output_tokens`·`reasoning_tokens`·`latency_ms`(호출 생략이면 null), `created_at`·`updated_at`.
  - 저장 결과에는 비밀값 가림을 거친 입력에서 자른 근거 원문만 들어간다(지금 응답과 같다).

## 결정 기록 (Decisions)
- 2026-10-11 Q1 → 사용자 결정(권장안): 입력 해시 비교가 아니라 배포별 최근 결과를 재사용한다. FAILED는 끝난 상태이고, 입력 해시는 재시작 횟수·이벤트 수 때문에 거의 맞지 않는다. 모델·프롬프트 버전이 바뀌면 새로 진단한다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 다시 진단은 진단과 같은 READ_ONLY 이상. 호출당 약 $0.0004라 별도 제한의 실익이 작다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 호출 생략 응답은 `model=rule`, `prompt_version=no-evidence-v1`로 모델을 부르지 않았음을 드러낸다.
- 2026-10-11 Q4 → 사용자 결정(권장안): 결과를 이력으로 쌓고 최근 것을 쓴다. 토큰 사용량을 함께 저장해 운영 비용 집계와 #104 장애 이력에 쓸 수 있다.
- 2026-10-11 (구현 중 정정, 구현 재량) 호출 생략 조건을 "원인 종류 5개가 아님"에서 "원인을 가리키지 않는 종류 3개 또는 없음"으로 좁혔다. 빌드·적용 실패는 사유 문구가 근거가 될 수 있어서다. #99 사례 판정은 같다.
- 2026-10-11 기본값: 호출 생략 조건은 #99 사례에서 증거 부족 2개만 해당하고 나머지 10개는 해당하지 않아야 한다(테스트로 고정).

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 재사용 시 로그 조회·모델 호출 없음, refresh·모델 변경 시 새로 호출, 이력 행 추가 | 서비스 테스트 (호출 횟수) | 로컬 |
| 권한 밖 배포는 저장 결과도 404 | 서비스 테스트 | 로컬 |
| 호출 생략 조건이 #99 증거 부족 2개에만 해당 | 사례 기반 테스트 | 로컬 |
| V8 적용과 저장·조회 | Testcontainers PostgreSQL 통합 테스트 | 로컬 |
| CLI `--refresh`·표시 | CLI 테스트 | 로컬 |
| 콘솔 타입 | `npx tsc --noEmit` | 로컬 |
| 회귀 | PR 전 `./gradlew test`, CLI 테스트 1회 | 로컬 |
| V7 → V8, 이전 JAR 호환 | 패키징 JAR + PostgreSQL | 로컬 |

### 실행 결과 (2026-10-11)
- 로컬: `DeploymentDiagnosisServiceTest` — 재사용 시 로그 조회·모델 호출·저장 없음, refresh와 설정 모델 변경 시 새로 호출·저장, 권한 밖·대상 아님은 저장소 조회 없음, 호출 생략 결과 저장(사용량 null), 응답을 읽을 수 없으면 저장하지 않음, #99 사례 중 증거 부족 2개만 호출 생략 조건에 해당.
- 로컬: `DeploymentDiagnosisRepositoryTest`(Testcontainers PostgreSQL 16, V8) 배포별 최근 행 조회, `CommandLogStatusSchemaValidationTest` 매핑 검증, `DeploymentControllerTest` refresh 파라미터.
- 로컬: CLI 테스트 7개(저장 결과 표시·`--refresh` 요청 경로 포함), `npx tsc --noEmit` 통과.
- 로컬 패키징 JAR + PostgreSQL 16 (Kubernetes 연결 없음, 모델 키 없음): 이전 릴리스(main `8b88556`) JAR로 V7 생성 → 새 JAR V8 migrate·validate·readiness UP, 기존 행 유지 → READ_ONLY 토큰으로 ROLLOUT_TIMEOUT 실패 배포 진단: 모델 없이 `rule` 판단 보류 저장, 두 번째 요청 `reused=true`(같은 진단 시각), `refresh=true`로 행 추가(2행, 사용량 null) → 이전 JAR가 V8 DB에서 기동·조회 → 새 JAR가 다시 저장 결과 재사용.
- 로컬: `./gradlew test` 261개 통과(건너뜀 1: 외부 모델 비교).
- 하지 않은 검증: 운영 DB 사본 V8 적용(새 테이블만 추가해 기존 행을 바꾸지 않음), 실제 모델 호출 결과 저장(서비스 테스트로 대체).
- 증거: `.local/evidence/0117-diagnosis-result-reuse/` (기동 로그, migration-result.json).

## 운영 반영
- 미착수. 배포 시 V8 적용 확인, 같은 배포 두 번 진단해 두 번째가 `reused=true`인지 확인(운영 진단 호출은 사용자 승인 후).

## 회고
- 어긋난 점: 호출 생략 조건을 처음에 "원인 종류가 아님"으로 넓게 잡아 빌드·적용 실패까지 모델 없이 보류할 뻔했다. FailureKind 전체를 보고 "원인을 가리키지 않는 종류"로 좁혔다.
- 원인 분류: 스펙 작성 시 FailureKind 전체 목록을 확인하지 않음.
- 고친 위치: 이 스펙 동작 4번과 결정 기록. 호출 생략 조건은 #99 사례 기반 테스트로 고정했다.
