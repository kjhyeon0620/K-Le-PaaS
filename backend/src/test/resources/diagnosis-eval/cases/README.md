# 배포 실패 진단 평가 사례 (#99)

사례 하나가 JSON 파일 하나다. 형식·채점 기준은 [스펙 0099](../../../../../../docs/specs/0099-diagnosis-eval-cases/spec.md)에 있다.

- `input.detail` / `input.logs`: 실패한 배포의 `GET /api/v1/deployments/{id}/detail`·`/logs` 응답. env 값은 없다(이름만).
- `expected`: 정답 범주(`category`)와 구체 원인(`root_cause`), 근거 위치(`evidence`, input 기준 JSON Pointer), 판단 보류 여부(`abstain`), 결과에 나오면 안 되는 문구(`forbidden`).
- `provenance`: `captured`는 로컬 임시 k3s에서 실제로 실패시킨 응답, `derived`는 캡처를 가공한 사례(가공 내용 기재).

12개는 작은 표본이다. 새 실패 유형을 만나면 같은 형식으로 추가하고 `DiagnosisEvalTest`의 기준선 수치와 스펙을 함께 갱신한다.
