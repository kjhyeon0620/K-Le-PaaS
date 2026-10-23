# 진단 평가 데이터셋 변경 이력

형식·분할 규칙은 [평가 명세](../../../../../docs/eval/diagnosis-eval-spec.md). 버전이 바뀌면 이전 버전 점수와 직접 비교할 수 없는 경우가 있어 그 범위를 함께 적는다.

## 1.0.0 (2026-10-24, #166)
- 처음 manifest로 묶었다. 분할: `dev` 51(#99 12, #123 평가용 39), `robustness` 24(#158), `test` 0.
- 모든 사례에 `episode_id`·`split`·`slice`·`audit`(구조화), 원인을 정하는 사례에 `injection`·`evidence_sets`(최소 충분 근거 묶음)를 더했다. 정답(`category`·`root_cause`·`abstain`)은 바꾸지 않았다.
- #123 사례에는 사람이 붙인 근거가 없었다(`evidence: []`). 근거 묶음은 판정 가이드로 새로 정했다.
- 이전 정답 변경(이 버전 전, 사례 파일에 기록): 2026-10-22 `g1-mq-prb-proto`에 `probe_port_closed`를 함께 정답으로 허용(#159 감사, #161 반영). 이 사례가 포함된 #123 점수는 정정 전후를 직접 비교할 수 없다([정정 기록](../../../../../docs/eval/diagnosis-eval-corrections.md)).
