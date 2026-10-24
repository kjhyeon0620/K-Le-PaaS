---
issue: 170
title: 시그니처 규칙 우선 진단 제품 반영
status: done
size: L
type: feat
branch: feat/#170-rule-first-diagnosis
---

# 시그니처 규칙 우선 진단 제품 반영

## 목적 / 성공 조건
- 목적: 지금 진단은 실패 배포마다 LLM을 부른다. 개발 세트(#161)와 동결 평가 세트(#168)에서 관측 시그니처 규칙이 지원 범위 6개 원인을 확신 오답 없이 정했고, 규칙 우선 정책은 LLM 단독보다 확신 오답이 많지 않으면서 LLM 호출을 줄였다(#168 채택 조건 충족). 규칙이 MATCH하면 LLM을 부르지 않고 규칙 결론을 내도록 제품에 반영한다.
- 기술적 완료:
  - 시그니처 규칙을 테스트 코드(`SignatureRuleBaseline`)에서 제품 코드로 옮기고, **평가 하네스가 제품 규칙을 그대로 쓰게** 한다(평가한 규칙 = 운영 규칙).
  - 진단 요청에서 규칙이 MATCH하면 LLM·지식 검색 없이 규칙 결과(범주, 구체 원인, 근거 위치와 원문 발췌, 관측 사실, 다음 확인)를 저장하고 돌려준다. 모델 호출 0회.
  - MATCH가 아니면(NO_MATCH·CONFLICT·INSUFFICIENT) 지금과 같이 LLM으로 진단한다(아래 Q1).
  - 어느 경로로 갔는지(규칙 ID·상태)를 로그와 결과에 남긴다.
- 운영: 응답 형식은 바뀌지 않는다. 규칙 결과는 `model: "rule"`, `prompt_version: "signature-v1.2"`로 보인다(근거 없음 경로 `no-evidence-v1`과 같은 방식). 콘솔·CLI·MCP는 같은 서비스를 쓰므로 함께 바뀐다.

## 근거 (측정 결과, 이 이슈에서 다시 재지 않음)
| 세트 | 규칙 단독 확신 오답 | 규칙 우선 확신 오답 (LLM 단독) | LLM 호출 (LLM 단독 → 규칙 우선) |
|---|---|---|---|
| dev 51 (#161) | 0 | `luna` 0 (1), `qwen3.8` 0 (4), `qwen3.5:4b` 0 (10) | #99 120 → 20, #123 117 → 3 |
| test 14 동결 (#168) | 0 | `luna` 3 (3), `gemma4` 2 (2) | 42 → 36 |

- test에서 규칙 MATCH 경로는 2건 × 3회 모두 정답, MATCH하면 안 되는 12건에서 잘못된 MATCH 0.
- test의 확신 오답은 모두 규칙이 MATCH하지 않은 느린 기동 사례를 LLM이 `probe_port_closed`로 단정한 것이다. 규칙 우선은 이것을 줄이지 않는다(아래 Q1).

## 범위
- 포함: 규칙 제품 코드화(v1.2 그대로, 판정 결과 동일), 진단 서비스의 규칙 우선 분기, 결과 문구(규칙별 요약·관측 사실·다음 확인), 재사용 조건, 로그, 평가 하네스의 제품 규칙 사용, 테스트, 문서.
- 제외: 규칙 확장(새 원인·분류 밖 장애), 라벨 전제 조건 서버 검사, LLM 라벨 단정 제한(Q1에서 고르지 않으면), 응답 필드 추가, 스키마 변경, RAG 동작 변경, 자연어 명령.

## 입출력·계약
- API: `POST /api/v1/deployments/{id}/diagnosis[?refresh=true]` 계약 그대로. 규칙 결과 예:
  - `model: "rule"`, `prompt_version: "signature-v1.2"`, `category`·`root_cause` 규칙 결론, `abstain: false`
  - `summary`: 규칙별 고정 문구 + 관측값(예: "readiness probe가 8081 포트에서 연결을 거부당했고 컨테이너 포트는 8080입니다.")
  - `observed_facts`: 일치한 관측(상태·이벤트·로그 줄), `hypotheses`: 비움, `next_checks`: 규칙별 고정 확인 항목
  - `cited`: 규칙의 근거 위치와 서버가 잘라 넣은 원문 발췌(`DiagnosisPrompt`의 발췌 방식 재사용)
  - `warnings`: "관측 시그니처 규칙(R-…)으로 판정했습니다. 모델을 호출하지 않았습니다"
- 재사용(#117): 저장 결과의 `prompt_version`이 지금 규칙 버전이면 재사용한다(모델 설정과 무관). `refresh=true`면 규칙부터 다시 판정한다.
- 저장: `deployment_diagnoses`에 그대로 저장(`requested_model = "rule"`, 사용량 null). 마이그레이션 없음.
- 근거 없음 경로(`no-evidence-v1`)는 규칙보다 먼저 판정한다(지금 순서 유지).
- 설정: `diagnosis.rule-first.enabled`(기본 true, 환경 변수 `DIAGNOSIS_RULE_FIRST_ENABLED`). false면 규칙을 판정하지 않는다.

## 권한·안전
- 호출 주체와 scope: 지금과 같다(READ_ONLY 이상). 소유권 검사는 상세·로그 조회가 먼저 한다. 규칙은 이미 읽은 입력만 보고 Kubernetes를 다시 부르지 않는다.
- 위험도와 승인: 진단은 읽기 전용이다. 변경 없음.
- 비밀값: 규칙은 비밀값을 가린 입력(`DiagnosisPrompt.redact`)에서 판정하고 발췌한다.

## 제약
- 참조: [평가 명세](../../eval/diagnosis-eval-spec.md), [#161](../0161-signature-rule-baseline/spec.md), [#168](../0168-frozen-diagnosis-test-set/spec.md), [architecture 진단 흐름](../../architecture.md)
- 규칙 판정 로직은 v1.2와 **같은 결과**여야 한다. 옮기면서 바꾸지 않는다(dev 51·robustness 24·test 14의 규칙 결과 회귀 테스트로 고정). 규칙을 바꾸면 버전을 올리고 평가를 다시 한다.
- 결과 문구는 관측을 넘어서는 단정을 하지 않는다(라벨 깊이: `image_not_pullable`·`probe_port_closed`는 증상에 가까움).

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 규칙 MATCH | 규칙 결과, 모델 호출 없음 경고 | LLM·지식 검색 호출 0, 저장 |
| 규칙 CONFLICT(두 원인 동시 일치) | LLM 진단 | 로그에 CONFLICT와 일치한 규칙 ID |
| 규칙 판정 중 예외(입력 형식 이상) | LLM 진단 | 규칙 실패를 warning·로그로 남기고 LLM 경로로 간다(진단을 막지 않음) |
| 저장된 LLM 결과가 있는데 지금은 규칙 MATCH | `refresh` 없으면 저장 결과(지금 모델 설정과 같을 때) | 기존 재사용 규칙 유지 |
| 권한 없음 / 다른 사용자 배포 | 404 | 규칙·모델 모두 실행 안 함 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-25 Q1 → 규칙이 MATCH하지 않으면 지금처럼 LLM이 라벨을 단정할 수 있다(#161·#168에서 측정한 정책 그대로). LLM 라벨 보류·라벨 전제 조건 검사는 새 동결 세트로 확인할 별도 이슈. 사용자 결정.
- 2026-10-25 Q2 → `diagnosis.rule-first.enabled`(기본 true)를 둔다. 끄면 지금과 같이 모든 진단이 LLM 경로. 사용자 결정.
- 2026-10-25 Q3 → 머지 후 운영 샘플 앱에 잘못된 probe 포트를 잠깐 주입해 운영 진단이 규칙 결과·모델 호출 0인지 확인하고 원복한다. 실행 직전에 사용자 승인을 다시 받는다. 사용자 결정.
- 2026-10-25 기본값: 규칙 결과의 `model`은 `"rule"`, `prompt_version`은 규칙 버전(`signature-v1.2`). 근거 없음 경로와 같은 방식이라 콘솔·CLI 표시를 바꾸지 않는다.
- 2026-10-25 (구현 중) 규칙은 `SignatureRules`(제품 코드, 테스트에서 git 이동)로 옮기고 판정 로직은 바꾸지 않았다. 규칙 결과 문구·관측 문장은 `RuleDiagnosis`에 둔다. 근거 발췌는 `DiagnosisPrompt.cite`(모델 결과와 같은 방식)를 쓴다.
- 2026-10-25 (구현 중) 규칙 결과의 `observed_facts`는 근거 위치를 사람이 읽는 문장으로 바꾼다: 이벤트 "reason: message", Pod 필드 "Pod 이름 필드: 값", 로그 줄·실패 사유는 원문. (처음에는 발췌 원문을 그대로 넣어 이벤트 JSON이 보였다. 로컬 실제 클러스터 확인 뒤 고침)
- 2026-10-25 (구현 중) 저장된 규칙 결과는 규칙 우선이 켜져 있고 지금 규칙 버전일 때만 다시 쓴다(설정 모델과 무관). 끄면 저장된 규칙 결과를 쓰지 않고 모델로 진단한다.
- 2026-10-25 (구현 중) 규칙 판정 예외 경로(경고 후 모델 경로)는 유효한 상세·로그 DTO로는 일으킬 수 없어 단위 테스트를 두지 않았다. 진단을 막지 않기 위한 방어 코드다.
- 2026-10-25 기본값: 규칙 MATCH면 지식 검색(RAG)도 하지 않는다(RAG는 기본 꺼짐이고 규칙 결과에 지식 인용이 없다).

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 규칙 판정이 v1.2와 같음 | 제품 규칙으로 dev·robustness·test 규칙 결과 회귀 테스트(#161·#158·#168 표와 같음) | 로컬 |
| MATCH면 모델 호출 0 | 서비스 테스트: MATCH 입력에서 `LlmClient` 호출 0회, 결과·저장·재사용 확인 | 로컬 |
| MATCH가 아니면 기존 경로 | 서비스 테스트: NO_MATCH·CONFLICT·규칙 예외에서 LLM 1회 | 로컬 |
| 권한 경계 | 다른 사용자 배포 404, 규칙·모델 미실행 | 로컬 |
| 실제 클러스터 | 패키징 JAR + 임시 k3s에서 잘못된 probe 포트 배포 진단: 규칙 결과, 모델 호출 0 | 로컬 실제 클러스터 |
| 운영 | 머지 후 운영 샘플 앱 잘못된 probe 포트 주입·진단·원복 (사용자 승인 후) | 운영 |
| 전체 | `./gradlew test` | 로컬 |

결과 (2026-10-25)
- 규칙 회귀(`SignatureRulesTest`, 로컬): 제품 규칙으로 dev 51 = MATCH 정답 48·NO_MATCH 1·보류 사례 INSUFFICIENT 2, robustness 23 = MATCH 정답 20·NO_MATCH 3, test 14 = MATCH 정답 2·NO_MATCH 12. #161·#158·#168 표와 같다. 잘못된 MATCH 0.
- 서비스(`DeploymentDiagnosisServiceTest` 12개, `RuleDiagnosisTest`, 로컬): #168 캡처 입력으로 MATCH면 모델·지식 검색 호출 0·`model=rule`·사용량 null 저장, NO_MATCH면 모델 1회, 설정 끔이면 모델, 저장된 규칙 결과 재사용 조건. 평가 사례에서 MATCH하는 6개 규칙 모두 요약·근거 발췌·관측 문장·다음 확인을 만든다. 기존 권한·상태·근거 없음·RAG·응답 파싱 테스트 그대로 통과.
- 로컬 실제 클러스터(패키징 JAR + PostgreSQL 16 + Docker 임시 k3s v1.30.6, 진단 모델 주소를 닫힌 포트로 둬 모델을 부르면 실패하게 함): 잘못된 probe 포트 배포 진단 → HTTP 200, `model=rule`, `signature-v1.2`, `probe_port_closed`, 요약 "readiness probe가 8081 포트에서 연결을 거부당했습니다. 컨테이너 포트는 8080입니다.", 이력 `requested_model=rule`·토큰 없음. 느린 기동 배포 진단 → 모델 경로로 가서 `AI_001`(닫힌 포트). 관측 문장 형식 수정 전 JAR로 확인했다. 원자료 `.local/evidence/0170-rule-first-diagnosis/`.
- 운영 확인(Q3)은 머지 후 사용자 승인을 받고 한다.

## Tasks
### T1. 규칙 제품 코드화
- 먼저 읽을 것: `diagnosis/eval/SignatureRuleBaseline.java`, `SignatureRuleBaselineTest.java`, `DiagnosisPrompt`의 발췌·가림
- 변경: `ai/diagnosis`에 규칙 판정 클래스(제품 JSON 라이브러리 사용). 테스트의 `SignatureRuleBaseline`은 지우고 하네스·회귀 테스트가 제품 클래스를 쓰게 한다.
- 금지: 판정 조건 변경.
- 검증: 규칙 회귀 테스트(dev 48/49 MATCH·잘못된 MATCH 0, robustness 미끼 10/10, test MATCH 2·잘못된 MATCH 0), 반례 테스트.

### T2. 진단 서비스 규칙 우선 분기
- 변경: `DeploymentDiagnosisService.diagnose` — 근거 없음 판정 뒤, 지식 검색·LLM 전에 규칙 판정. MATCH면 규칙 결과 저장·반환. 재사용 조건에 규칙 버전 추가. 로그에 경로·규칙 ID.
- 금지: 응답 필드·에러 코드 변경, LLM 경로 동작 변경(Q1 ①일 때).
- 검증: 서비스 테스트(위 완료 증거).

### T3. 결과 문구
- 변경: 규칙 6개의 요약·다음 확인 문구. 관측값(포트·이유·로그 줄)을 넣는다.
- 금지: 관측보다 깊은 단정(예: "레지스트리가 다운됐다").

### T4. 문서
- `docs/architecture.md` 진단 흐름·모듈 책임, `README.md`·`backend/README.md` AI 진단 상태, `docs/eval/diagnosis-eval-spec.md`(규칙이 제품 코드에 있다는 것), 스펙 0166·0168 운영 반영(#167·#169 머지 후 배포 결과), #165(#164, S) 배포 결과는 이 PR 본문에 기록.

## 운영 반영
- 머지 후 배포 workflow 결과와 운영 확인(Q3) 결과를 다음 PR에서 기록한다.
- 2026-10-25 머지(#171) 후 `main` 배포 workflow 성공(build·deploy). 운영 설정 변경 없음(`DIAGNOSIS_RULE_FIRST_ENABLED` 미설정 = 기본 켬).
- 2026-10-25 운영 확인(사용자 승인, #105와 같은 CLI 토큰 방식): 운영 샘플 앱 readiness probe 포트를 비움 → 9090으로 바꿔 같은 이미지로 배포 → #26 FAILED·READINESS_TIMEOUT(120초, 기존 Pod `available=1` 유지) → 진단 `model=rule`·`signature-v1.2`·`READINESS/probe_port_closed`, 근거 `/logs/events/0`, 요약 "readiness probe가 9090 포트에서 연결을 거부당했습니다. 컨테이너 포트는 8080입니다.", 경고 "모델을 호출하지 않았습니다", 응답 0.31초 → probe를 원래 값으로 되돌려 재배포 #27 SUCCESS, 설정이 원래와 같음을 비교 확인. 원자료 `.local/evidence/0170-rule-first-diagnosis/ops/`.

## 회고
- 어긋난 점: 규칙 결과의 관측 사실에 이벤트 JSON 원문이 그대로 들어갔다. 실제 응답을 보고서야 알았다.
- 원인 분류: 완료 기준 느슨(문구 품질을 "비어 있지 않음"으로만 검사).
- 고친 위치: `RuleDiagnosis.fact`, `DeploymentDiagnosisServiceTest`·`RuleDiagnosisTest`의 형식 검사.
- 남은 것: 운영 확인(Q3), 규칙 미MATCH 시 LLM 라벨 보류·라벨 전제 조건 검사(새 동결 세트로 확인할 별도 이슈).
