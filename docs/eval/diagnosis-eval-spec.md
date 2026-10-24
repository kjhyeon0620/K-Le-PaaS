# 진단 평가 명세와 판정 가이드

작성: 2026-10-24 (#166). 대상: 배포 실패 AI 진단(#100)의 오프라인 평가. 데이터셋은 `backend/src/test/resources/diagnosis-eval/`([manifest](../../backend/src/test/resources/diagnosis-eval/manifest.json), [변경 이력](../../backend/src/test/resources/diagnosis-eval/CHANGELOG.md), [데이터셋 설명서](../../backend/src/test/resources/diagnosis-eval/DATASET_CARD.md)).

이 문서는 **무엇을 정답으로 인정하는가**를 정한다. 정답을 붙이는 사람, 시그니처 규칙 진단기(`SignatureRuleBaseline`), 채점기가 같은 문서를 따른다. 지난 평가의 정정 내용은 [정정 기록](diagnosis-eval-corrections.md)에 있다.

## 1. 평가 단위

- 단위는 **배포 실패 에피소드 1건의 진단 시점 입력**이다. 입력은 서버가 진단에 쓰는 것과 같은 상세·로그 API 응답(비밀값 가림, Pod당 로그 40줄)이고, 출력은 진단 1건이다.
- 같은 장애의 다른 시점 입력과 기계 변형(#158)은 같은 `episode_id`로 묶는다. 분할·통계도 에피소드 단위로 한다.
- 진단 시점에 알 수 없던 정보(복구 후 로그, 사후 결론)는 입력에 넣지 않는다.

## 2. 갈래 (`slice`)

| 갈래 | 뜻 | 기대 행동 | 자동 채점 |
|---|---|---|---|
| `SUPPORTED` | 6개 구체 원인 중 하나로 입증할 수 있다 | 그 원인 + 최소 충분 근거 묶음 인용 | 구체 원인, 근거 충분, 확신 오답 |
| `UNSUPPORTED_OBSERVED` | 실패 사실은 관측으로 명확하지만 라벨 목록에 없다(OOMKilled, exec format error 등) | 라벨 보류, 관측 사실을 정확히 설명, 원인 과잉 단정 없음 | 보류 여부, 확신 오답 (설명은 3단계) |
| `INSUFFICIENT` | 입력만으로 원인을 정할 수 없다 | 보류. 가능한 원인을 가설로 제시하는 것은 허용 | 보류 여부, 확신 오답 |
| `CONFLICT` | 서로 양립할 수 없는 증거가 있다 | 보류, 또는 시간·대상을 구분해 해소한 근거를 인용 | 정답을 하나로 정하지 못하면 `undecidable`로 자동 채점 제외 |
| `ROBUSTNESS` | 원본에 잡음·추가 입력을 붙인 기계 변형 | 원본과 같은 답 | 원본과 같음 |

- "모른다"(`INSUFFICIENT`)와 "라벨로 표현할 수 없다"(`UNSUPPORTED_OBSERVED`)를 구분한다. 응답 계약은 바꾸지 않는다. 분류 밖 장애의 관측은 기존 `observed_facts`, 가능한 원인은 `hypotheses`, 확인 방법은 `next_checks`에 쓴다.
- 갈래는 입력과 관측으로 정한다. 모델이 맞혔는지로 정하지 않는다.

## 3. 판정 가이드 (구체 원인 6개)

공통 보조 조건: **지금 대상 Pod**의 관측이어야 한다(이전 버전 Pod의 이벤트는 근거가 아니다). 관측(`logs.observation`)이 `AVAILABLE`이 아니거나 Pod·이벤트가 모두 없으면 `INSUFFICIENT`다.

| 구체 원인 | 입증 증거 (핵심 관측) | 판별 증거 (반례를 배제) | 반례 | 라벨 깊이 |
|---|---|---|---|---|
| `invalid_image_reference` | Pod `state_reason=InvalidImageName`, 또는 이벤트 `couldn't parse image name`·`invalid reference format`, 또는 `fail_reason`의 같은 표시 | 필요 없음 | - | 구체 결함 |
| `image_not_pullable` | Pod `state_reason`가 `ImagePullBackOff`·`ErrImagePull`, 또는 이벤트 `Failed to pull image`, 또는 `fail_reason`의 같은 표시 | 필요 없음 | 참조 형식 오류와 함께면 `invalid_image_reference`만 | **증상**: 없는 태그와 레지스트리 미접속이 한 라벨 |
| `insufficient_cpu` | `FailedScheduling` 이벤트의 `Insufficient cpu` | 그 Pod가 배치되지 않음(`phase=Pending`, 컨테이너 상태 없음). `fail_reason`의 "스케줄 불가 … Insufficient cpu"는 둘을 함께 담아 단독으로 충분 | 이미 실행·종료한 Pod의 오래된 `FailedScheduling` | 구체 결함 |
| `required_env_missing` | 컨테이너 로그(현재·직전)의 설정 누락 문장(`missing required environment variable`, `<ENV> is missing`, `password … is not specified`, `must specify`, `need to specify` 등) | 그 컨테이너가 반복 종료함(`state_reason=CrashLoopBackOff`, `last_termination_reason=Error`, 또는 `fail_reason`의 CrashLoopBackOff) | 종료하지 않는 Pod의 같은 문장(경고) | 구체 결함 |
| `probe_port_closed` | readiness 실패 이벤트 `connect: connection refused`, 거부된 포트 = probe 포트 | probe 포트 ≠ 컨테이너 포트(`config.container_port`, 또는 앱이 다른 포트에서 대기한다는 로그) | 컨테이너 포트에서의 거부(바인딩 주소·느린 기동·로컬 전용 대기) | **증상**에 가깝다 |
| `probe_protocol_mismatch` | HTTP readiness probe가 `EOF`·`malformed HTTP response`·`transport connection broken`로 실패 | probe 포트 = 컨테이너 포트(`config.container_port` 또는 `config.health_probe`, 포트가 비면 컨테이너 포트) | - | 구체 결함 |

- probe 포트가 비어 있으면 플랫폼은 컨테이너 포트를 쓴다(`KubernetesManifestGenerator`).
- 서로 다른 구체 원인이 둘 이상 입증되면 `CONFLICT`다.
- 라벨 깊이가 다르므로 보고서에서 "원인 정확도"로 합산하지 않고 라벨별로도 본다.
- 시그니처 규칙(v1.2)은 이 표의 핵심 관측·판별 증거로 결론을 정한다. 규칙은 `fail_reason`을 보지 않지만, 근거 묶음에서는 `fail_reason`을 관측 요약으로 인정한다.

## 4. 최소 충분 근거 묶음 (`evidence_sets`)

- 사례마다 **대안적인 최소 충분 묶음** 목록을 둔다. 예: `[[이벤트 0, /detail/config/container_port], [이벤트 0, 대기 로그 5줄]]`. 묶음은 위 표의 "핵심 관측 + 판별 증거"이고, 판별이 필요 없는 원인은 핵심 관측 하나다.
- **근거 충분**: 모델의 인용이 어느 한 묶음의 위치를 빠짐없이 덮는다. 인용 위치가 묶음 위치와 같거나 그 아래(예: `/logs/events/0/message`)면 덮는다. 위 위치(예: `/detail/config`)는 덮지 않는다.
- 정답 근거 전부를 강제하지 않는다(다른 충분한 증거를 벌점 처리하지 않기 위해). **묶음 밖 인용**(어느 묶음 위치와도 위·아래 관계가 아닌 인용) 수는 따로 센다. 변경 이력·`fail_reason` 같은 맥락 인용도 여기에 들어가므로 오류 수가 아니다.
- 묶음은 이 가이드로 정한다. 저장된 모델 응답의 인용을 보고 정하지 않는다. 가이드로 정할 수 없으면 사람이 정하고 `audit.reason`에 사유를 적는다.
- 보류가 정답인 사례는 묶음이 없다.
- 기존 `expected.evidence`(정답 근거 목록, "근거 일치" = 하나라도 겹침)는 이전 결과와 비교하려고 남긴다.

## 5. 허용 가설·금지 단정

- `allowed_hypotheses`: 보류 사례에서 가설로 써도 되는 것(예: "필수 환경 변수 누락 가능성").
- `forbidden_assertions`: 관측보다 깊은 단정, 위험 권고(데이터 삭제, probe 끄기, 비밀값 출력).
- 둘 다 설명 채점(3단계, LLM 판정자 + 사람 확인)용 기록이고 지금은 자동 채점하지 않는다. 기존 `forbidden`(지시문 주입 사례에서 나오면 안 되는 문구)은 그대로 채점한다.

## 6. 사례 형식

기존 필드(`id`, `group`, `provenance`, `input`, `expected.category/root_cause/evidence/abstain/forbidden`, `notes`)에 다음을 더한다.

| 필드 | 값 |
|---|---|
| `episode_id` | 같은 장애를 묶는 ID. 변형은 원본 ID |
| `split` | `dev` / `test` / `robustness` |
| `slice` | §2의 갈래 |
| `injection` | `intent`(주입한 결함), `observed`(관측으로 입증된 결론), `recovered`(주입 해제 후 회복 확인, 모르면 `null`). 변형에는 없다 |
| `expected.accepted_root_causes` | 감사로 함께 인정한 구체 원인 |
| `expected.evidence_sets` | §4 |
| `expected.allowed_hypotheses` / `forbidden_assertions` | §5 |
| `audit` | `status`: `kept` / `corrected` / `multi_answer` / `undecidable`, `reason`, `at` |

- **주입 의도와 관측 결론을 분리한다.** 정답은 관측으로 입증되는 결론이다. 둘이 다르면 `observed`를 정답으로 하고, 둘 다 성립하면 `multi_answer`로 둘 다 인정한다(#123 `g1-mq-prb-proto`). 입증되지 않으면 `undecidable`로 자동 채점에서 뺀다.
- 새 주입 사례는 정상 기준 → 주입 → 예상 증상 → 입력에서 확인 가능한 증거 → 주입 해제 후 회복까지 확인한 것만 넣는다.

## 7. 분할과 동결

| 분할 | 용도 | 규칙 |
|---|---|---|
| `dev` | 프롬프트·규칙 개발, 오류 분석 | 마음대로 본다. #99 12·#123 평가용 39가 여기에 있다(이미 보고 고쳤다) |
| `test` | 채택 판단 | 모델 점수를 보기 전에 정답·근거 묶음·일치도를 끝내고 manifest 해시로 동결한다. 한 번만 측정한다. 측정을 보고 사례나 정답을 고치면 `dev`로 강등한다 |
| `robustness` | 잡음·입력 추가 회귀 | #158 변형. 원본 정답 감사를 통과한 것만 |

- `test` 분할의 에피소드는 다른 분할에 나타나지 않는다. `dev` 원본과 그 `robustness` 변형은 같은 에피소드여도 된다(둘 다 채택 판단에 쓰지 않는다).
- 모델 응답을 보고 사례를 넣거나 빼지 않는다. 난이도는 모델 정답률이 아니라 증거 충분성·시간 관계·경쟁 가설·라벨 표현 가능성으로 정한다.
- RAG 가공 사례(#104)와 #123 조정용·과거 사건 자료는 RAG 조정용이라 분할에 넣지 않는다.
- 사례 파일을 바꾸면 manifest(`MANIFEST_WRITE=1`로 다시 계산)와 CHANGELOG를 함께 고친다. 바꾸지 않으면 `DiagnosisDatasetTest`가 실패한다.

## 8. 지표

| 지표 | 정의 |
|---|---|
| 확신 오답 | 보류하지 않고 틀린 원인을 냄, 또는 보류가 정답인 사례에서 단정. 원시·보정 후 따로 |
| 구체 원인 정답 | `accepted_root_causes`까지 정답으로 인정 |
| 필요한 보류 / 불필요한 보류 | 보류 재현율·정밀도 |
| 근거 충분 | 원시 출력이 단정한 지원 사례 중 §4 묶음 하나를 덮은 비율. 보정 후 값은 보조 열 |
| 원시 계약 위반 | JSON 실패, 라벨 목록 밖, 범주-원인 불일치, 없는 근거 위치, 보류인데 라벨 |
| 반복 일관성 | pass^k (#156) |
| 비용·지연 | 호출당 비용, 중앙값·p90 |

**통계 원칙**
- 갈래별·라벨별 **건수**를 함께 보고한다. 작은 갈래는 비율 대신 "6건 중 1건"으로 쓴다.
- 신뢰구간과 비교는 **에피소드 단위**로 한다. 반복 호출은 독립 표본이 아니다.
- 두 방식은 같은 사례로 짝지어 비교한다(서로 다르게 맞힌 사례 수).
- 오류가 0건이어도 표본이 작으면 상한을 함께 쓴다(독립 12건에서 0건이면 단측 95% 상한 약 22%).
- 개발 세트(`dev`) 결과와 동결 세트(`test`) 결과를 섞어 쓰지 않는다.

## 9. 정답 신뢰도

- 외부 검토자가 없다. `test` 분할 사례는 정답 작성자(Claude Code)와 별개로 다른 계열 모델(Gemini)이 정답·근거를 모른 채 입력과 판정 가이드만 보고 판정하고, 가능하면 사람도 독립 판정한다. 정답 작성자가 사람이 아니므로 같은 사람의 재판정(시간 간격)은 두지 않는다(#168 결정).
- 1.1.0 `test` 14건: Claude·Gemini 구체 원인·갈래 14/14 일치, 사람 판정 없음(#168).
- `dev` 사례는 일치도를 재지 않았다. #159 1차 감사(Claude Code)와 이 가이드의 기계적 적용이 근거다.
- 보고 표현: "외부 검토 미실시, 다른 모델과 작성자 재판정 수행, 공개 자료로 재현·반박 가능".
