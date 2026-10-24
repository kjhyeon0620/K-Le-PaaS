# 독립 판정 프롬프트 (Gemini, 사례마다 별도 호출, 대화 문맥 없음)

`{GUIDE}`에는 [평가 명세](../../../eval/diagnosis-eval-spec.md)의 §2(갈래)·§3(판정 가이드)·§4(최소 충분 근거 묶음)를 그대로 넣는다. `{INPUT}`에는 사례의 `input`(상세·로그 API 응답)만 넣는다. 사례 ID·정답·주입 내용은 넣지 않는다. 결과를 본 뒤 이 프롬프트를 바꾸지 않는다.

```text
You are reviewing a failed Kubernetes deployment for a deployment platform. Below is a labeling guide written in Korean, followed by the platform's detail and logs API response for one failed deployment (environment variable values are removed; only names are shown; each pod shows at most 40 log lines).

Decide, using only the guide and the input:
1. root_cause: one of the six root causes in the guide, or null if the guide does not let you prove any of them from the input.
2. slice: one of SUPPORTED, UNSUPPORTED_OBSERVED, INSUFFICIENT, CONFLICT.
3. evidence: the JSON Pointers (relative to the input below) that together prove your root cause, or that show the observed failure if root_cause is null.
4. observed_failure: one sentence on what failed, stating only what the input shows.
5. reason: at most three sentences.

Answer with a single JSON object with exactly these keys: root_cause, slice, evidence, observed_failure, reason.

=== GUIDE ===
{GUIDE}

=== INPUT ===
{INPUT}
```
