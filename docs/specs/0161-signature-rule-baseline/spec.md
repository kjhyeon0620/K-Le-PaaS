---
issue: 161
title: "test: 시그니처 규칙과 LLM의 추가 진단 가치 비교"
status: done
size: M
type: test
branch: test/#161-signature-rule-baseline
---

# test: 시그니처 규칙과 LLM의 추가 진단 가치 비교

## 목적 / 성공 조건
- 목적: "왜 LLM인가"를 처음으로 검증한다. 같은 입력에서 시그니처 규칙이 무엇을 해결하고, LLM이 규칙 대비 **추가로** 해결하는 몫과 그 대가(확신 오답·호출 비용)를 잰다. 결과로 규칙 우선 경로를 제품에 넣을지 판단한다.
- 기술적 완료: 테스트 지원 코드의 시그니처 규칙 진단기와 반례 테스트, 저장된 LLM 응답으로 정책 비교(모델 호출 없음), 정답 복수 허용 반영.
- 운영: 변경 없음 (테스트·문서만). 채택하면 제품 반영은 별도 이슈.

## 현재 동작 (2026-10-22 확인)
- `DiagnosisEval.ruleBaseline()`은 `failure_kind`로 범주만 내고 구체 원인은 항상 null, 보류는 항상 true다.
- 정답(`Expected`)은 구체 원인 하나만 받는다. #159 1차 감사에서 `g1-mq-prb-proto`는 두 라벨을 허용하기로 했다(사용자 결정 2026-10-22).
- 비교 대상 51사례(#99 12 + #123 평가용 39)는 이미 여러 번 본 **개발 세트**다. 규칙도 이 사례들을 본 뒤 작성되므로 미지 사례 성능을 주장하지 않는다.

## 시그니처 규칙 (판정 가이드, 측정 전에 고정)
규칙은 사례 ID·정답·`notes`·`provenance`·`failure_kind`·규칙 기반 `explanation`을 보지 않는다. 관측(Pod 상태·이벤트·로그)과 배포 설정만 본다.

| 규칙 | 구체 원인 | MATCH 조건 | 거부(반례) |
|---|---|---|---|
| R-IMG-REF | invalid_image_reference | Pod `state_reason`가 `InvalidImageName`이거나, 이벤트에 `couldn't parse image name`·`invalid reference format` | - |
| R-IMG-PULL | image_not_pullable | Pod `state_reason`가 `ImagePullBackOff`·`ErrImagePull`이거나, 이벤트에 `Failed to pull image` | 이미지 참조 형식 오류(R-IMG-REF)와 함께면 R-IMG-REF만 인정 |
| R-SCHED-CPU | insufficient_cpu | `FailedScheduling` 이벤트에 `Insufficient cpu`, **그리고** 그 Pod에 컨테이너 상태가 없음(배치되지 않음) | Pod가 이미 실행·종료 중이면 오래된 이벤트로 보고 무시 |
| R-ENV | required_env_missing | 컨테이너가 반복 종료(`CrashLoopBackOff` 또는 직전 종료 `Error`), **그리고** 같은 Pod 로그에 설정 누락 문장(`missing required environment variable`, `<ENV> is missing`, `environment variable is missing`, `password (option) is not specified`, `must specify`, `need to specify`) | 종료하지 않는 Pod의 같은 문장(경고)은 무시 |
| R-PROBE-PORT | probe_port_closed | readiness 실패 이벤트가 `connect: connection refused`이고, 거부된 포트 = probe 포트 ≠ 컨테이너 포트 (probe 포트가 비어 있으면 컨테이너 포트, v1.1) | 거부된 포트가 컨테이너 포트와 같으면 결정하지 않음(바인딩 주소·느린 기동 등) |
| R-PROBE-PROTO | probe_protocol_mismatch | readiness HTTP probe 실패가 `EOF`·`malformed HTTP response`·`transport connection broken`이고, probe 포트 = 컨테이너 포트 | - |

- 결과 상태: 관측이 없으면(`observation`이 `AVAILABLE`이 아니거나 Pod·이벤트가 모두 없음) `INSUFFICIENT`. 서로 다른 구체 원인이 둘 이상 MATCH면 `CONFLICT`. 하나면 `MATCH`. 없으면 `NO_MATCH`.
- 근거 위치: 일치한 Pod 상태·이벤트·로그 줄의 JSON Pointer.

## 비교 정책 (저장된 LLM 응답, 모델 호출 없음)
| 정책 | 결론 |
|---|---|
| 규칙 단독 | MATCH면 규칙 결론, 나머지는 보류 |
| LLM 단독 | 저장된 진단(서버 보정 후 라벨·보류) |
| 규칙 우선 | MATCH면 규칙 결론(LLM 호출 0), 아니면 LLM 진단 |

- 정답 판정은 저장된 `score`가 아니라 **저장된 진단 라벨과 지금 정답 파일**로 다시 한다(복수 허용 반영).
- 지표(호출 단위, 규칙 단독은 반복 수만큼 같은 결론): 지원 사례 정답 / 지원 사례 보류 / **확신 오답**(단정했는데 틀림, 보류 사례에서 단정) / 보류 사례 정답 보류 / LLM 호출 비율.
- 사례 단위 표: 규칙 미해결(MATCH 아님) 사례 중 LLM이 반복 모두 맞힌 사례·한 번이라도 틀린 사례.

## 범위
- 포함: `Expected`에 허용 구체 원인 목록(`accepted_root_causes`) 추가와 채점·원시 재채점 반영, `g1-mq-prb-proto` 정답 정정, `SignatureRuleBaseline`과 반례 테스트, 51사례 규칙 결과 회귀 테스트, 정책 비교 모드(`DIAGNOSIS_EVAL_POLICY=<compare json>`).
- 제외: 제품 서버의 규칙 우선 라우팅, 규칙 확장(분류 밖 장애), 새 사례·새 모델 호출, #158 변형 사례.

## 결정 기록 (Decisions)
- 2026-10-22 `g1-mq-prb-proto` → `probe_port_closed`도 정답 허용. 사용자 결정(#159 권장안 수용).
- 2026-10-22 규칙은 테스트 지원 코드에만 둔다(토론 합의). 기본값으로 가정.
- 2026-10-23 (측정 후 정정) 규칙 v1은 probe 포트가 비어 있으면 판정하지 않아 `readiness-http-probe-on-tcp-service`를 NO_MATCH로 냈다. 플랫폼은 probe 포트가 없으면 컨테이너 포트를 쓴다(`KubernetesManifestGenerator` 558행) → v1.1에서 그 의미를 반영했다. 사례에 맞춘 조정이 아니라 플랫폼 의미의 누락이지만, 개발 세트를 본 뒤의 수정이므로 v1·v1.1 결과를 함께 남긴다.
- 2026-10-22 규칙 우선의 채택 조건: 규칙 MATCH 경로의 확신 오답 0, 그리고 LLM 단독보다 확신 오답이 늘지 않음. LLM이 규칙 미해결 사례를 추가로 해결하지 못하면 LLM 경로 자체를 기본에서 빼는 것도 검토한다. 기본값으로 가정.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 규칙·반례 | `SignatureRuleBaselineTest` | 로컬 |
| 51사례 규칙 결과 고정 | 회귀 테스트 | 로컬 |
| 정책 비교 | `DIAGNOSIS_EVAL_POLICY`로 #115·#123·#152·#154 저장 결과 | 로컬, 모델 호출 없음 |
| 기존 테스트 | `./gradlew test` | 로컬 |

## 실행 결과

### 규칙 단독, 개발 세트 51사례 (`SignatureRuleBaselineTest.developmentSetResult`, 로컬)
| 규칙 버전 | 지원 사례 정답 MATCH | 잘못된 MATCH | 보류 사례 INSUFFICIENT | NO_MATCH | CONFLICT |
|---|---|---|---|---|---|
| v1 | 47/49 | 0 | 2/2 | 2 (`readiness-http-probe-on-tcp-service`, `g1-mq-prb-proto`) | 0 |
| **v1.1** | **48/49** | **0** | **2/2** | 1 (`g1-mq-prb-proto`) | 0 |

- 남은 NO_MATCH는 정답 감사로 두 라벨을 허용한 사례다. 연결 거부가 컨테이너 포트에서 일어나 규칙이 원인을 정하지 않는다(설계대로).
- 반례 테스트 5개: 컨테이너 포트의 연결 거부 → NO_MATCH, 이미 실행·종료한 Pod의 `FailedScheduling` → 무시, 종료하지 않는 Pod의 설정 누락 경고 → 무시, 관측 없음 → INSUFFICIENT, 서로 다른 원인 동시 일치 → CONFLICT. 그리고 `failure_kind`·설명을 바꿔도 결과가 같음.

### 정책 비교 (`DIAGNOSIS_EVAL_POLICY`, 로컬, 모델 호출 없음, 규칙 v1.1, 정답은 복수 허용 반영)
호출 단위. 규칙 단독은 반복 수만큼 같은 결론을 센다.

| 결과 | 모델 | 정책 | 호출 | 지원 사례 정답 | 지원 사례 보류 | **확신 오답** | 보류 사례 정답 보류 | LLM 호출 |
|---|---|---|---|---|---|---|---|---|
| #115 기준선 2차 (#99, 12×10) | `gpt-6-luna` | 규칙 단독 | 120 | 100 | 0 | **0** | 20 | 0 |
| | | LLM 단독 | 120 | 99 | 0 | **1** | 20 | 120 |
| | | 규칙 우선 | 120 | 100 | 0 | **0** | 20 | 20 |
| #152 (#99) | `gemma4:26b-a4b` | 규칙 단독 / LLM 단독 / 규칙 우선 | 120 | 100 / 100 / 100 | 0 | 0 / 0 / 0 | 20 | 0 / 120 / 20 |
| #152 (#99) | `qwen3.8:27b` | 규칙 단독 / LLM 단독 / 규칙 우선 | 120 | 100 / 95 / 100 | 0 / 1 / 0 | **0 / 4 / 0** | 20 | 0 / 120 / 20 |
| #152 (#99) | `qwen3.5:4b` | 규칙 단독 / LLM 단독 / 규칙 우선 | 120 | 100 / 50 / 100 | 0 / 40 / 0 | **0 / 10 / 0** | 20 | 0 / 120 / 20 |
| #123 평가용 (39×3) | `gpt-6-luna` | 규칙 단독 | 117 | 114 | 3 | 0 | - | 0 |
| | | LLM 단독 | 117 | 116 | 1 | 0 | - | 117 |
| | | 규칙 우선 | 117 | 116 | 1 | 0 | - | 3 |
| #123 평가용 | `gpt-6-luna@knowledge=on` | 규칙 단독 / LLM 단독 / 규칙 우선 | 117 | 114 / 116 / 116 | 3 / 1 / 1 | 0 | - | 0 / 117 / 3 |
| #154 (39×3) | `gemma4:26b-a4b` | 규칙 단독 / LLM 단독 / 규칙 우선 | 117 | 114 / 117 / 117 | 3 / 0 / 0 | 0 | - | 0 / 117 / 3 |

**규칙이 정하지 못한 사례에서 LLM이 더한 것**
| 사례 | 규칙 상태 | `gpt-6-luna` (정답·보류·오답) | `luna@knowledge=on` | `gemma4` |
|---|---|---|---|---|
| `g1-mq-prb-proto` (정답 논쟁 사례) | NO_MATCH | 2 · 1 · 0 | 2 · 1 · 0 | 3 · 0 · 0 |
| `insufficient-*` 2개 (#99) | INSUFFICIENT | 보류 정답 20/20 (모든 모델) | - | 보류 정답 20/20 |

### 해석
- **개발 세트에서 LLM이 규칙 대비 추가로 해결한 사례는 1개(`g1-mq-prb-proto`)뿐이고, 그것도 정답을 감사로 넓힌 사례다.** 나머지는 규칙이 같은 입력의 시그니처로 모두 맞혔다.
- **규칙 우선은 모든 모델에서 LLM 단독과 같거나 낫다.** 확신 오답이 `gpt-6-luna` 1 → 0, `qwen3.8` 4 → 0, `qwen3.5:4b` 10 → 0으로 줄었다. LLM 호출은 #99에서 120 → 20(규칙이 INSUFFICIENT로 판단한 보류 사례), #123에서 117 → 3으로 줄었다. INSUFFICIENT도 바로 보류로 처리하면 #99의 LLM 호출은 0이다.
- 정답 복수 허용 반영으로 #123의 RAG 없음·있음은 모두 116/117(보류 1)로 같아지고, #154 `gemma4`는 117/117 그대로다(이전 보고: RAG 없음 116, 있음 114).
- 한계: 51사례는 규칙을 만들 때 본 개발 세트이고, 모든 사례가 6개 라벨 안의 한 줄 시그니처 장애다. 규칙이 처음 보는 장애에서 잘못 MATCH하지 않는지, LLM이 분류 밖·충돌 장애에서 가치가 있는지는 **이 세트로 알 수 없다.**

### 채택 판단
- **평가 결론**: 지원 범위 6개 원인에 대해서는 규칙 우선이 LLM 단독보다 확신 오답이 적고 호출이 훨씬 적다. 이 범위에서 LLM의 추가 가치는 입증되지 않았다.
- **제품 반영은 아직 하지 않는다.** 규칙 MATCH 경로를 자동 결론으로 내려면, 모델 점수를 보기 전에 동결한 새 평가 세트(설계안 2단계: 분류 밖·증거 부족·충돌·비슷한 증상 쌍 포함)에서 **규칙 MATCH 경로의 확신 오답 0**을 먼저 확인한다. 그 세트에서 LLM이 규칙 미해결 사례를 추가로 해결하는지도 함께 본다.

## 운영 반영
- 없음 (테스트·문서만 변경).
- 2026-10-23 머지(#162) 후 `main` 배포 workflow 성공. 운영 설정 변경 없음.

## 회고
- 어긋난 점: 규칙 v1이 probe 포트 미지정의 플랫폼 의미를 빠뜨려 한 사례를 놓쳤다. 측정 후 고쳐 v1·v1.1 결과를 함께 남겼다.
- 원인 분류: 스펙 누락(판정 가이드에 "probe 포트 = 지정값 또는 컨테이너 포트"를 적지 않음).
- 고친 위치: `SignatureRuleBaseline.effectiveProbePort`, 판정 가이드는 이 스펙의 결정 기록.
