---
issue: 150
title: "feat: OpenAI 호환 로컬 모델 서버를 진단·자연어 명령 공급사로 선택"
status: done
size: M
type: feat
branch: feat/#150-local-llm-provider
---

# feat: OpenAI 호환 로컬 모델 서버를 진단·자연어 명령 공급사로 선택

## 목적 / 성공 조건
- 목적: 배포 로그와 명령을 외부 API로 보낼 수 없는 환경에서 쓸 수 있는 모델이 있는지, 이미 있는 평가 세트(#58 자연어 30입력, #99 진단 12사례)로 판단할 수 있게 한다. 공급사 목록에 OpenAI 호환 서버(Ollama·LM Studio·vLLM·`mlx_lm.server` 등)를 더한다.
- 기술적 완료: `*.llm.provider=openai-compatible`과 `*.llm.base-url`을 주면 진단·자연어 명령이 그 주소로 Chat Completions JSON 모드 요청을 보낸다. 두 평가 하네스가 `openai-compatible:<모델>`을 받는다.
- 운영: 기본값(`openai`/`gpt-6-luna`)은 그대로다. 운영 서버에는 GPU가 없어 로컬 모델을 운영에서 돌리지 않는다. 설정을 잘못 주면 기동 시 어느 설정인지 밝히고 실패하며, 서버가 응답하지 않으면 지금과 같이 `AI_001`로 드러나고 명령은 실행되지 않는다.

## 현재 동작 (2026-10-21 코드 확인)
- `LlmConfig.create()`가 `gemini`·`anthropic`·`openai`를 고르고, 그 밖의 값은 `IllegalStateException`(설정 이름 포함)으로 기동을 멈춘다. 키가 없으면 빈은 만들고 호출만 `AI_001`로 실패한다.
- `OpenAiLlmClient`는 공식 openai-java SDK(`OpenAIOkHttpClient`)로 `response_format: json_object`, `max_completion_tokens` 2,048, 설정된 경우에만 `reasoning_effort`를 보낸다. `provider()`는 `"openai"` 고정이다. 테스트용 생성자는 이미 `baseUrl`을 받는 SDK 클라이언트를 주입한다(`LlmClientContractTest`).
- `provider()`는 자연어 명령 로그 한 줄에만 쓰인다(`NlpCommandService`). 저장 컬럼은 없다.
- 평가 하네스 `NlpModelEvalTest`·`DiagnosisModelComparisonTest`는 `provider:model`의 첫 `:`로 나눈다. Ollama 모델 이름(`gemma4:26b-a4b`)의 `:`는 모델 쪽에 남는다. 가격표(`pricing.json`)에 없는 모델은 "가격표 없음"으로 기록한다.

## 범위
- 포함:
  - 공급사 값 `openai-compatible`, 설정 `diagnosis.llm.base-url`·`nlp.llm.base-url` (환경 변수 `DIAGNOSIS_LLM_BASE_URL`·`NLP_LLM_BASE_URL`, 기본 빈 값).
  - `OpenAiLlmClient`를 재사용한다(새 클래스 없음). 공급사 이름을 생성자로 받아 로그에 `openai-compatible`로 남긴다.
  - 두 평가 하네스의 `openai-compatible` 공급사 (주소는 `OPENAI_COMPATIBLE_BASE_URL`), 결과 머리의 호출 경로 설명.
  - 문서: architecture 설정 표·AI 모듈 설명, backend README 환경 변수.
- 제외:
  - 로컬 모델 측정 실행과 결과 기록 (후속. 장비 조건을 정해 따로 돌린다).
  - 임베딩(`Embeddings`) 공급자 변경 — RAG 단계에서 따로 다룬다.
  - 인증 키가 필요한 OpenAI 호환 서버. 이번에는 키를 보내지 않는다(Q3).
  - 자동 대체(fallback), 기본값 변경, 운영 설정 변경.

## 입출력·계약
- API·CLI·저장·Kubernetes: 변경 없음.
- 설정: 새 키 `diagnosis.llm.base-url`, `nlp.llm.base-url`. 공급사 허용값에 `openai-compatible` 추가.

## 권한·안전
- 호출 주체·scope·소유권·위험도·승인: 변경 없음. 모델이 바뀌어도 상태를 바꾸는 intent는 모두 승인 대상이다(#58, ADR-0002).
- 비밀값: 진단 입력은 지금과 같이 비밀값을 가린 뒤 보낸다. `base-url`은 내부 주소일 수 있으므로 로그·문서·스펙에 실제 값을 쓰지 않는다.

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| `openai-compatible`인데 `base-url`이 비어 있음 | 기동 실패 메시지에 `*.llm.base-url` | `IllegalStateException`으로 기동 중단 (알 수 없는 공급사와 같은 처리) |
| 서버가 꺼져 있거나 모델이 없음 | `AI_001` | SDK 예외를 `AiProcessingException`으로 바꾼다 (기존 경로) |
| 서버가 JSON 모드를 무시하고 문장을 돌려줌 | 자연어: `AI_002`, 진단: 판단 보류·실패 | 기존 파서 검증. 평가에서는 파싱 실패 수로 드러난다 |
| 생각(thinking) 토큰이 출력 상한 2,048을 다 씀 | 빈 응답 → 파싱 실패 | #58 Gemini `MAX_TOKENS`와 같은 현상. 측정 때 원인과 함께 기록한다 |
| 응답이 느림 (로컬 CPU·열 제한) | 요청이 오래 걸림 | SDK 기본 타임아웃을 그대로 쓴다. 측정 때 지연으로 기록한다 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-21 Q1 별도 클라이언트 클래스를 둘까? → 두지 않는다. `OpenAiLlmClient`에 base URL과 공급사 이름만 더한다. (요청·응답 형식이 같고, 사용량 파싱도 같은 SDK 경로를 쓴다)
- 2026-10-21 Q2 `reasoning_effort` → 기존 규칙 그대로. 진단은 `diagnosis.llm.reasoning-effort`가 있을 때만 보내고, 자연어 명령은 보내지 않는다. 기본값 빈 값으로 가정. (Ollama는 이 값을 생각 모드 조절에 쓰므로, 측정 때 필요하면 평가 변형 `@effort=`로 비교한다)
- 2026-10-21 Q3 API 키 → 보내지 않는다. SDK가 키를 요구하므로 고정 자리 표시 값을 넣고 서버는 무시한다고 가정. 키가 필요한 서버는 범위 밖. (Ollama·LM Studio·`mlx_lm.server` 기본 설정은 키를 요구하지 않는다)
- 2026-10-21 Q4 비용 → 가격표에 넣지 않는다. "가격표 없음"으로 남기고 결과표에 "API 과금 없음, 장비·전력 별도"로 적는다. (0으로 넣으면 외부 API와 같은 기준의 비용처럼 읽힌다)
- 2026-10-21 (구현 중) 로컬 Ollama 0.40.6(Homebrew formula)에 `gemma4:26b-a4b`로 JSON 모드 요청 1건을 보내자 `501 structured output is unavailable`. 이 태그는 MLX 엔진으로 뜨는데, Homebrew 빌드에 MLX용 문법 라이브러리(`libollama_xgrammar.dylib`)가 없어 `response_format`을 처리하지 못한다. JSON 모드를 빼면 응답은 오지만 내용이 코드 블록(```json)으로 감싸이고, 생각 내용은 별도 `reasoning` 필드로 온다 → 코드는 바꾸지 않는다. JSON 모드는 운영과 같은 계약이라 유지하고, 측정 전에 서버 환경(공식 앱 빌드 또는 llama.cpp 엔진 GGUF 태그)을 맞춘다. (서버 빌드 문제이고, 클라이언트가 JSON 모드를 끄면 #58·#100 결과와 비교 조건이 달라진다)
- 2026-10-21 (구현 중) 공식 Ollama 앱 0.40.6(같은 버전, MLX용 `libollama_xgrammar.dylib` 포함)으로 바꾸고 같은 요청 1건 → `finish_reason: stop`, 본문 `{"intent": "get_status"}`(코드 블록 없음). 측정은 이 서버로 한다. 짧은 답에도 `completion_tokens`가 255로 생각 토큰이 출력에 포함되고 `reasoning_tokens`는 따로 보고되지 않는다 → 측정 때 출력 상한 2,048 도달 여부(`finish_reason: length`)를 함께 본다.
- 2026-10-21 (구현 중) 측정 계획을 위한 참고 호출 3건(측정 아님): `gemma4:26b-a4b`, 공식 앱, 운영 지시문(입력 약 1,350토큰) + 명령 3개 → 13.1~38.2초, 출력 450~1,490토큰, intent 3/3. 출력 대부분이 생각 토큰이다(`gpt-6-luna` #58 지연 중앙 2.2초). Ollama는 요청을 하나씩 처리해 하네스의 병렬 4가 빨라지지 않는다 → 후속 측정은 생각 켬·끔(`reasoning_effort`) 비교를 소수 사례로 먼저 하고 반복 횟수·일정을 정한다.
- 2026-10-21 Q5 측정 대상 모델 → 후속 측정에서 `gemma4:26b-a4b`(MoE, 약 18GB), `qwen3.8:27b`(dense, 약 18GB), `qwen3.5:4b`(CPU 서버 하한선, 약 3GB)를 Ollama로 비교한다. 이번 범위는 연결까지.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| `openai-compatible`이 지정 주소로 JSON 모드 요청을 보내고 공급사 이름을 밝힌다 | `./gradlew test --tests 'klepaas.backend.ai.llm.*'` | 로컬 |
| 주소 없는 설정은 기동 시 실패, 키 없는 설정은 정상 생성 | `LlmConfigTest` | 로컬 |
| 로컬 서버가 같은 요청 형식(JSON 모드, `max_completion_tokens`)을 받는다 | 요청 1건 (측정 아님) | 로컬 Ollama. 2026-10-21 Homebrew 빌드는 JSON 모드 501, 공식 앱 빌드는 통과(`gemma4:26b-a4b`, 1건) |

## 운영 반영
- 운영 설정을 바꾸지 않는다. 머지 후 배포 workflow 결과만 다음 이슈 PR에서 기록한다.

## 회고
- 어긋난 점: 같은 Ollama 버전(0.40.6)이라도 Homebrew formula 빌드는 MLX 엔진의 JSON 모드를 처리하지 못했다(`501 structured output is unavailable`). "OpenAI 호환 서버면 같은 요청을 받는다"는 전제가 서버 빌드에 따라 틀릴 수 있다.
- 원인 분류: 외부 제약 미인지.
- 고친 위치: 코드는 그대로 두고(JSON 모드는 운영과 같은 계약), 측정 서버를 공식 앱 빌드로 정했다. 결정 기록과 완료 증거에 서버 빌드를 측정 조건으로 남겼다.
