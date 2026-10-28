# 콘솔 기능 지도

[`README.md`](README.md) 절차로 띄우고 로그인한 뒤 화면별로 무엇을 확인할 수 있는지 정리한다.
화면은 `http://localhost:3000/console?view=<view>`로 바로 연다.

| 구분 | 뜻 |
|---|---|
| 로컬 | 로컬 DB와 백엔드만으로 실제 API 결과를 확인할 수 있다 |
| 클러스터 | Kubernetes·레지스트리·GitHub App이 필요하다. 로컬에서는 오류나 빈 값이 정상이다 |
| LLM | 모델 키나 `openai-compatible` 로컬 모델(#150)이 필요하다 |
| stub | 백엔드 API가 없다 (`frontend/lib/api.ts`의 stub, `architecture.md` §9). 화면 값으로 동작을 판단하지 않는다 |

## Deployments (`view=deployments`)

저장소마다 카드 하나. 버튼: `History`, `Config`, `Scale`, `Rollback`, `Restart`, `Logs`.

| 기능 | 조작 | 기대 결과 | 구분 |
|---|---|---|---|
| 저장소 목록 | 화면 열기 | 넣은 저장소가 `owner/repo` 카드로 보이고, 배포가 없으면 `No deployments yet` | 로컬 |
| 배포 설정 저장 | `Config` → `Configuration` 탭에서 값 변경 → `Review N change(s)` → 확인 대화상자의 `이전 → 새 값` 확인 → `Save` | 버튼이 `No changes`로 바뀌고 `GET /api/v1/repositories/{id}/config`와 DB에 새 값 | 로컬 |
| 설정 유지 | 새로 고침 → `Config` | 저장한 값이 그대로이고 버튼이 `No changes` | 로컬 |
| 잘못된 설정 | 형식이 틀린 값 입력 후 `Review` | 항목 아래에 이유가 보이고 확인 대화상자가 열리지 않음 | 로컬 |
| 배포 이력 | `History` | 배포 기록 목록. 배포가 없으면 빈 목록 | 로컬 (기록 생성은 클러스터) |
| 배포·스케일·재시작·롤백·로그 | 각 버튼 | 배포가 없으면 비활성. 실행 결과는 클러스터에서만 확인 | 클러스터 |

## GitHub (`view=github`)

탭: `Repositories`, `Pull Requests (준비 중)`, `CI/CD Pipelines`.

| 기능 | 조작 | 기대 결과 | 구분 |
|---|---|---|---|
| 연결된 저장소 | `Repositories` 탭 | `Connected Repositories`에 DB의 저장소 | 로컬 |
| 새 저장소 연결 | `Connect New Repository`의 `Connect` | GitHub App 설치 확인이 필요하다. 로컬 데이터는 DB에 직접 넣는다 | 클러스터 |
| PR, 파이프라인, 자동 배포 스위치 | 각 탭 | 빈 값이나 `준비 중` | stub |

## Commands (`view=commands`)

| 기능 | 조작 | 기대 결과 | 구분 |
|---|---|---|---|
| 자연어 명령 | 입력창에 명령 입력 | 모델 없이는 오류. 해석 결과의 실행은 클러스터 | LLM, 클러스터 |
| 명령 이력 | 화면 열기 | 이전 명령 목록. 처음에는 비어 있다 | 로컬 |

## Dashboard (`view=dashboard`)

| 기능 | 기대 결과 | 구분 |
|---|---|---|
| `Connected Repositories`, `Active Deployments`, `Recent Commands` | DB의 저장소·배포·명령 | 로컬 |
| `Alerts`, `Pull Requests`, `System Utilization` | `준비 중`, `미수집` | stub |

## Monitoring (`view=monitoring`), Settings (`view=settings`)

| 화면 | 상태 | 구분 |
|---|---|---|
| Monitoring | 지표·알림 API가 없다. 상단에 `준비 중` 안내가 있다 | stub |
| Settings | 조직·환경·Slack·에이전트 탭은 API가 없고 브라우저 저장소에만 남는다 | stub |

## 화면을 추가하거나 바꿀 때

이 표의 행을 같은 PR에서 고친다. 버튼 이름과 경로는 실제 화면 텍스트와 같게 쓴다.
