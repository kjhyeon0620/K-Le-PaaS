# 기계 변형 입력의 회귀·보류 검사 (#158)

#99 사례(`../cases/`)를 `HardCases`(테스트 코드)가 기계적으로 바꾼 사례다. 손으로 고치지 않는다.
규칙을 바꾸면 `HARD_CASES_WRITE=1 ./gradlew test --tests 'klepaas.backend.diagnosis.eval.HardCasesTest'`로 다시 만들고, `HardCasesTest`가 저장본과 생성기 결과가 같은지 확인한다.

잡음·추가 입력에 결론이 흔들리지 않는지, 증거가 없을 때 보류하는지를 보는 회귀 검사다. 어려운 평가 세트가 아니다. `AUDIT-ONLY` 사례는 자동 채점(`loadCaseSet("hard")`)에서 빠진다.

갈래·기대 답·제외한 사례의 이유는 [스펙 0158](../../../../../../docs/specs/0158-hard-diagnosis-cases/spec.md)에 있다.
