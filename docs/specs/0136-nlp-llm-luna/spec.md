---
issue: 136
title: "feat: 자연어 명령 해석 모델을 gpt-6-luna로 교체"
status: done
size: L
type: feat
branch: feat/#136-nlp-llm-luna
---

# feat: 자연어 명령 해석 모델을 gpt-6-luna로 교체

## 목적 / 성공 조건
- 목적: 자연어 명령 해석이 Google이 신규 사용자에게 막은 `gemini-2.5-flash`에 묶여 있지 않게 한다. 운영 키를 바꾸거나 모델이 내려가도 자연어 명령이 멈추지 않도록, 공급사·모델을 설정으로 고르고 기본값을 #58에서 고른 `gpt-6-luna`로 둔다.
- 기술적 완료: `NlpCommandService`가 `GeminiClient` 대신 진단과 같은 `LlmClient` 계약으로 모델을 부른다. 프롬프트 문자열, `IntentParser`, 위험도 분류, 승인 흐름은 그대로다.
- 운영: 배포 뒤 조회 명령 1건으로 동작을 확인한다. 문제가 생기면 환경 변수로 Gemini로 되돌릴 수 있다. 모델 호출이 실패하면 지금과 같이 `AI_001`(호출 실패)·`AI_002`(응답 파싱 실패)로 드러나고 명령은 실행되지 않는다.

## 현재 동작 (2026-10-13 코드 확인)
- `NlpCommandService.processCommand`: `GeminiRequest.of(systemPrompt, command)` → `GeminiClient.generate(request)`(모델 `gemini.api.model` = `${GEMINI_MODEL:gemini-2.5-flash}`, temperature 0.1, 출력 1,024) → `extractText()` → `IntentParser.parse`.
- 진단은 `LlmConfig.diagnosisLlm` 빈(`diagnosis.llm.provider`·`model`, 기본 `openai`/`gpt-6-luna`)을 `DeploymentDiagnosisService`가 타입으로 주입받는다. `LlmClient` 빈은 지금 하나뿐이다.
- 공급사 키가 없으면 `LlmConfig`는 앱을 띄우고 호출만 `AI_001`로 실패시킨다(`missingKey`).
- 운영에는 `OPENAI_API_KEY`가 이미 있다. 진단(#100·#105 운영 실증)이 이 키로 `gpt-6-luna`를 쓰고 있다.
- `GeminiLlmClient.completeJson`은 `GeminiRequest.json`(JSON 응답, 출력 8,192)을 쓴다. 자연어 명령의 `GeminiRequest.of`(출력 1,024)와 다르다. #58에서 1,024 상한이 `gemini-3.8-flash` 응답을 잘랐다.
- 테스트: `NlpCommandServiceTest`가 `GeminiClient.generate`를 mock한다. `@MockitoBean GeminiClient`를 둔 테스트는 9개이고, 그중 `CliTokenScopeHttpTest`·`Stage2HttpAccessTest`는 자연어 요청을 보내 `generate`를 stub하며, `ApprovalPinningTest`·`CommandConfirmationConcurrencyTest`는 `NlpCommandService`를 직접 `@Import`하는 슬라이스 테스트다. (2026-10-13 구현 중 정정)

## 범위
- 포함:
  - 설정 `nlp.llm.provider`·`nlp.llm.model` (환경 변수 `NLP_LLM_PROVIDER`·`NLP_LLM_MODEL`, 기본 `openai`·`gpt-6-luna`)과 `LlmConfig`의 자연어 명령용 `LlmClient` 빈. 진단 빈과 이름으로 구분한다.
  - 프롬프트 문자열 생성을 운영 코드 한곳(`NlpPrompt.build(systemPrompt, command)` = 지시문 + `"\n\n사용자 입력: "` + 입력)으로 모으고, `NlpCommandService`와 #58 평가가 같은 함수를 쓴다.
  - `NlpCommandService`가 자연어 명령용 `LlmClient`를 쓰도록 변경.
  - 어떤 공급사·모델로 해석했는지 로그 한 줄(사용자 입력·키 제외).
  - #58 평가(`NlpModelEvalTest`)가 운영과 같은 `LlmClient` 구현으로 호출하도록 맞추고, 새 경로로 다시 측정.
  - 문서: architecture §1·§2·§3.1·§7·§9, README 현재 상태 표와 환경 변수 예시, backend README 환경 변수, 운영 확인·되돌리기 절차.
- 제외:
  - 프롬프트(`system-prompt.txt`), `IntentParser`, 위험도·승인 규칙 변경. 인자 검증은 #135.
  - 명령 기록(`CommandLog`)에 모델·토큰 저장. 스키마 변경이 필요하다.
  - 공급사 장애 시 자동 대체 (Q2 결정).
  - 대화 문맥(멀티턴), reasoning effort 설정.
  - `GeminiClient`·`gemini.api.*` 설정 제거. `gemini` 공급사와 진단이 계속 쓴다.

## 입출력·계약
- API·CLI 응답, 에러 코드, exit code 변경 없음.
- 설정 키 (새로 추가):

  | 키 | 기본값 | 의미 |
  |---|---|---|
  | `nlp.llm.provider` | `${NLP_LLM_PROVIDER:openai}` | `gemini`·`anthropic`·`openai` (진단과 같은 목록) |
  | `nlp.llm.model` | `${NLP_LLM_MODEL:gpt-6-luna}` | 공급사 모델 이름 |

- `gemini.api.model`(`GEMINI_MODEL`)은 자연어 명령에서 더 쓰지 않는다. `gemini` 공급사를 고를 때는 `NLP_LLM_MODEL`로 모델을 준다.
- 공급사를 `gemini`로 고르면 `GeminiLlmClient` 경로(`GeminiRequest.json`, 출력 8,192)를 쓴다. 지금의 출력 1,024 경로보다 잘림이 적다.
- 키가 없거나 공급사 호출이 실패하면 지금처럼 `AI_001`. 응답 형식이 맞지 않으면 `AI_002`. 명령 기록은 만들지 않는다(지금과 같다).
- 스키마 변경 없음.

## 권한·안전
- 토큰 scope·소유권·승인 흐름 변경 없음. 위험도는 지금처럼 서버가 intent로 정한다.
- 비밀값: 키는 기존 환경 변수만 쓴다. 로그에 공급사·모델 이름만 남기고 사용자 입력·키는 남기지 않는다.
- 사용자 입력이 OpenAI로 간다. 진단은 이미 배포 근거(로그·이벤트, 비밀값 가림)를 OpenAI로 보내고 있다. 자연어 명령 입력은 사용자가 친 문장이며, 지금도 Google로 가고 있다(공급사만 바뀐다).

## 제약
- 참조: [architecture](../../architecture.md) §3.1, [스펙 0100](../0100-evidence-based-diagnosis/spec.md)(공급사 계약), [스펙 0058](../0058-nlp-model-eval-baseline/spec.md)(비교 근거)
- `LlmClient` 계약(`completeJson(prompt)`)을 바꾸지 않는다. 자연어 명령 응답도 JSON 하나다.
- 진단 동작이 바뀌면 안 된다. 진단 빈의 공급사·모델·reasoning effort는 그대로다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| `OPENAI_API_KEY` 없음 | `AI_001` "openai API 키가 설정되지 않았습니다" | 앱은 뜬다. 명령 기록 없음, 실행 없음 |
| OpenAI 429·5xx·시간 초과 | `AI_001` | SDK 기본 재시도 후 실패. 실행 없음 |
| JSON이 아닌 응답 | `AI_002` | 지금과 같음 |
| 알 수 없는 `nlp.llm.provider` | 기동 실패 | 설정 오류를 기동 시점에 드러낸다(진단과 같다) |
| 배포 직후 문제 | 운영자가 확인 명령에서 오류를 본다 | `NLP_LLM_PROVIDER=gemini`·`NLP_LLM_MODEL=<동작하는 모델>`로 되돌리거나 이전 릴리스로 롤백 |
| 승인 대기 중 공급사가 바뀜 | 영향 없음 | 승인 실행은 저장된 intent·인자로 하며 모델을 다시 부르지 않는다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-13 Q1 전환 방식 → 기본값을 `openai`/`gpt-6-luna`로 두고 머지·배포 때 바로 전환한다. 운영에 `OPENAI_API_KEY`가 이미 있어 서버 작업이 없고, 신규 사용자에게 막힌 `gemini-2.5-flash`에 머무는 기간이 없다. 되돌리기는 서버 `EnvironmentFile`의 `NLP_LLM_PROVIDER`·`NLP_LLM_MODEL` 또는 이전 릴리스 롤백. (사용자 결정)
- 2026-10-13 Q2 자동 대체 → 하지 않는다. 호출 실패는 `AI_001`로 드러내고 명령을 실행하지 않는다. 해석 모델이 하나로 고정되어 #58 평가가 그대로 적용되고, 대체 모델(Gemini)의 출력 잘림 문제를 끌어들이지 않는다. (사용자 결정)
- 2026-10-13 등급 L. 운영 호출 경로와 설정 키가 바뀌고 운영 반영 확인이 필요하다. 스키마·API 계약 변경은 없다. (이슈 본문)
- 2026-10-13 설정 키는 진단과 같은 모양(`nlp.llm.provider`·`model`)으로 따로 둔다. 진단과 자연어 명령은 품질 기준과 측정이 달라(#100, #58) 같은 모델을 강제하지 않는다. reasoning effort 설정은 #58이 기본값으로 측정했으므로 두지 않는다. (기본값)
- 2026-10-13 프롬프트 문자열은 지금 운영 문자열(`GeminiRequest.of`와 같은 이어 붙이기)을 그대로 쓴다. #58이 이 문자열로 측정했다. (기본값)
- 2026-10-13 운영 확인(배포 뒤 조회 명령 1건)은 사용자가 CLI로 하고 결과를 받아 기록한다. 운영 토큰을 에이전트가 다루지 않는다(#103과 같은 방식). (기본값)

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 설정한 공급사·모델로 자연어 명령 빈이 만들어지고, 진단 빈은 그대로 | `LlmConfig` 단위 테스트 (공급사별, 키 없음, 알 수 없는 공급사) | 로컬 |
| `NlpCommandService`가 `NlpPrompt.build` 문자열로 `LlmClient`를 부르고 기존 흐름(위험도·승인·기록) 유지, 호출 실패 시 기록·실행 없음 | `NlpCommandServiceTest` (LlmClient mock) | 로컬 |
| 승인·동시성·권한 회귀 | `CommandConfirmationConcurrencyTest`, `ApprovalPinningTest`, HTTP 권한 테스트 | 로컬 (Testcontainers) |
| 새 경로로 #58 수준 유지 (파싱 실패 0, 확인 없이 변경 실행 0, intent 정확도 #58 ±2%p) | `NlpModelEvalTest` `openai:gpt-6-luna` 30 × 5 | 로컬 + 외부 모델 |
| 패키징 JAR가 키 없이도 기동(두 빈 주입), 자연어 요청은 `AI_001` | 패키징 JAR + PostgreSQL 기동, `AI_001`은 단위 테스트 | 로컬 |
| 운영 전환 | 배포 workflow 결과 + 사용자 CLI `ask "저장소 목록 보여줘"` | 운영 |
| 회귀 | PR 전 `./gradlew test` 1회 | 로컬 |

### 실행 결과 (2026-10-13)
- 로컬: `LlmConfigTest` 4개(자연어 명령·진단 빈 분리, gemini 되돌림 시 `nlp.llm.model` 사용, 키 없음은 호출만 `AI_001`, 알 수 없는 공급사는 설정 이름과 함께 기동 실패), `NlpCommandServiceTest`(모델에 `NlpPrompt.build` 문자열 전달, 호출 실패 시 명령 기록·intent 해석·실행 없음 포함) 통과.
- 로컬 (Testcontainers PostgreSQL): `ApprovalPinningTest`, `CommandConfirmationConcurrencyTest`, `CliTokenScopeHttpTest`, `Stage2HttpAccessTest`, `McpServerHttpTest` 통과. 자연어 경로를 쓰는 HTTP 테스트는 `nlpLlm` 빈을 mock한다.
- 로컬: `./gradlew test` 298개 통과, 건너뜀 2(실제 모델 평가 테스트).
- 로컬 패키징 JAR + PostgreSQL 16 (`OPENAI_API_KEY`·`GEMINI_API_KEY` 없음, 임시 컨테이너): 기동 4.7초, readiness `UP`. 진단·자연어 명령 빈이 이름으로 주입되고 키가 없어도 앱이 뜬다. 자연어 요청의 `AI_001`은 단위 테스트로 확인했고 JAR에서 인증 요청까지 보내지는 않았다.
- 로컬 + 외부 모델 (`NlpModelEvalTest`, 새 경로 `OpenAiLlmClient` + `NlpPrompt.build`, `gpt-6-luna`, 30개 × 5회, 프롬프트 SHA-256 `9e088861d761…`):

| 경로 | 파싱 | intent | 인자 | 날조 | 과소 | 과대 | 확인 없이 변경 | 사례 다수결 | 금액 USD | 지연 중앙·p90 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| #58 (평가 전용 호출) | 150/150 | 143/150 (95.3%) | 143/143 | 0 | 0 | 7 | 0 | 29/30 | 0.0265 | 2,246 · 3,519 |
| #136 (운영 경로) | 150/150 | 141/150 (94.0%) | 141/141 | 0 | 0 | 9 | 0 | 28/30 | 0.0263 | 2,245 · 3,561 |

  - 완료 기준(파싱 실패 0, 확인 없이 변경 0, intent ±2%p)을 만족한다. 차이 2회는 #58과 같은 두 사례("전체 앱 0으로" 4/5, 영어 지시문 주입 5/5를 대상 없는 `SCALE {replicas: 0}`으로 해석)의 반복 흔들림이다. 프롬프트 문자열이 같으므로(`NlpPrompt.build`) 모델 입력은 #58과 같다.
- 증거: `.local/evidence/0136-nlp-llm-luna/` (평가 결과 JSON·MD).

## Tasks
### T1. 설정과 빈
- 먼저 읽을 것: `LlmConfig`, `DeploymentDiagnosisService` 생성자, `application.yaml`의 `diagnosis.llm`
- 변경: `application.yaml`에 `nlp.llm.*`. `LlmConfig`에 `nlpLlm` 빈을 추가하고 공급사 분기는 진단과 같은 함수를 쓴다. 두 빈을 이름으로 주입하게 한다(`@Qualifier` 또는 파라미터 이름 일치).
- 금지: 진단 빈의 기본값·동작 변경.
- 검증: `LlmConfig` 단위 테스트.

### T2. 자연어 명령 경로
- 먼저 읽을 것: `NlpCommandService.processCommand`, `NlpCommandServiceTest`
- 변경: `NlpPrompt.build` 추가, `NlpCommandService`가 `nlpLlm.completeJson(NlpPrompt.build(...))`를 쓴다. 공급사·모델 로그 한 줄. `NlpCommandServiceTest`를 `LlmClient` mock으로 바꾼다.
- 금지: `IntentParser`·위험도·승인·기록 로직 변경.
- 검증: `NlpCommandServiceTest`, 승인·권한 회귀 테스트.

### T3. 평가 재측정
- 변경: `NlpEval.prompt`를 `NlpPrompt.build`로 바꾸고, `NlpModelEvalTest`의 호출을 `LlmClient` 구현(`OpenAiLlmClient`, `GeminiLlmClient`)으로 맞춘다.
- 검증: `openai:gpt-6-luna` 30 × 5 측정, 결과를 이 스펙에 요약.

### T4. 문서와 운영 반영
- 변경: 위 범위의 문서. 운영 확인·되돌리기 절차는 [CICD](../../CICD.md) 또는 architecture §7에 둔다.
- 검증: 머지 후 배포 결과와 사용자 운영 확인을 `운영 반영`에 기록.

## 운영 반영
- 2026-10-13 PR #139 머지(`e3cda2f`), main 배포 run 38417850658 build·deploy success.
- 사실 확인: 배포가 끝나기 전 이전 릴리스에 보낸 `ask "저장소 목록 보여줘"`가 `Gemini API 호출 실패: 404 NOT_FOUND`였다. 운영 키로도 `gemini-2.5-flash`가 막혀 있어 **전환 전부터 운영 자연어 명령이 실패하고 있었다**. 언제부터인지는 확인하지 않았다.
- 2026-10-14 운영 확인 (사용자, CLI): 같은 명령이 intent `LIST_REPOSITORIES`, 위험도 LOW, 승인 없음으로 해석됐다. 모델 전환은 동작한다.
- 범위 밖 발견: `LIST_REPOSITORIES` 실행이 실제 목록 대신 고정 안내 문구를 돌려준다(#140). #58 평가는 해석까지만 채점해 드러나지 않았다.

## 회고
- 어긋난 점: (1) 같은 타입의 두 번째 `LlmClient` 빈이 생기자 `NlpCommandService`를 직접 `@Import`하는 슬라이스 테스트 2개가 컨텍스트를 만들지 못했다(자연어 빈 없음). 스펙의 "테스트" 현재 동작에 HTTP 테스트만 적고 슬라이스 테스트를 빠뜨렸다. (2) 자연어 경로를 실제로 타는 HTTP 테스트 2개가 `GeminiClient.generate`를 stub하고 있어 mock 대상을 바꿨다. (3) `NlpCommandService`·`NlpCommandServiceTest`가 CRLF·LF 혼합 파일이라 diff가 파일 전체로 보인다(#138).
- 원인 분류: 스펙 누락(테스트 영향 범위를 `@MockitoBean GeminiClient` 검색으로만 셌다).
- 고친 위치: 해당 테스트 4개에 `@MockitoBean(name = "nlpLlm")`. 영향 범위는 이 스펙 실행 결과에 남겼다. 줄바꿈은 #138에서 한 번에 정리한다.
