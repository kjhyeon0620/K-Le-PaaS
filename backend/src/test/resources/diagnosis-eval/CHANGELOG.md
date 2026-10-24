# 진단 평가 데이터셋 변경 이력

형식·분할 규칙은 [평가 명세](../../../../../docs/eval/diagnosis-eval-spec.md). 버전이 바뀌면 이전 버전 점수와 직접 비교할 수 없는 경우가 있어 그 범위를 함께 적는다.

## 1.1.0 (2026-10-24, #168)
- `test` 분할 14건을 추가하고 모델 측정 전에 동결했다(`test-cases/`). 로컬 k3s에 신규 장애를 주입하고 회복까지 확인한 캡처다: 실행 형식 오류 2종(아키텍처 불일치·shebang 없음), exit 137 2종(OOMKilled·OOM 아님), connection refused 2종(잘못된 probe 포트·느린 기동), 로그 없는 종료, 각각 서로 다른 앱 2개.
- 갈래: SUPPORTED 2, UNSUPPORTED_OBSERVED 10, INSUFFICIENT 2. 정답은 Claude Code가 판정 가이드로 붙였고 Gemini 독립 판정과 14건 모두 일치했다. 사람 판정은 하지 않았다.
- `dev`·`robustness`는 바뀌지 않았다.

## 1.0.0 (2026-10-24, #166)
- 처음 manifest로 묶었다. 분할: `dev` 51(#99 12, #123 평가용 39), `robustness` 24(#158), `test` 0.
- 모든 사례에 `episode_id`·`split`·`slice`·`audit`(구조화), 원인을 정하는 사례에 `injection`·`evidence_sets`(최소 충분 근거 묶음)를 더했다. 정답(`category`·`root_cause`·`abstain`)은 바꾸지 않았다.
- #123 사례에는 사람이 붙인 근거가 없었다(`evidence: []`). 근거 묶음은 판정 가이드로 새로 정했다.
- 이전 정답 변경(이 버전 전, 사례 파일에 기록): 2026-10-22 `g1-mq-prb-proto`에 `probe_port_closed`를 함께 정답으로 허용(#159 감사, #161 반영). 이 사례가 포함된 #123 점수는 정정 전후를 직접 비교할 수 없다([정정 기록](../../../../../docs/eval/diagnosis-eval-corrections.md)).
