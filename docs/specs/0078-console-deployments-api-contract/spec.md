---
issue: 78
title: 콘솔 배포 화면이 백엔드 응답 필드와 맞지 않아 배포와 설정이 표시·변경되지 않는 문제
status: done
size: M
type: fix
branch: fix/#78-console-deployments-api-contract
---

# 콘솔 배포 화면이 백엔드 응답 필드와 맞지 않아 배포와 설정이 표시·변경되지 않는 문제

## 목적 / 성공 조건
- 목적: 콘솔의 Deployments 화면, 대시보드, GitHub 화면의 Configure가 실제 백엔드 응답으로 동작한다. 동작하지 않는 화면이 정상처럼 보이거나, 백엔드가 주지 않는 값을 고정값으로 보여주는 일이 없어진다.
- 기술적 완료: 등록된 저장소마다 이름과 최신 배포(상태, 브랜치, 커밋, 이미지, 시작·종료 시각, 실패 사유)를 표시한다. 배포 설정(빌드 방식, 이미지 템플릿, pull secret 이름·사용 여부, 서비스 타입, NodePort, 컨테이너 포트, 도메인, replica)을 콘솔에서 저장하면 `GET /api/v1/repositories/{id}/config`에 반영된다.
- 운영: API 호출이 실패하면 화면에 오류를 표시하고 기본값으로 저장하지 않는다(#66과 같은 원칙).

## 배경 (현재 코드)
- `frontend/lib/api.ts` `getRepositoriesLatestDeployments()`는 `GET /api/v1/repositories` 응답을 그대로 넘긴다. 응답(`RepositoryResponse`)은 `id`, `owner`, `repo_name`, `git_url`, `cloud_vendor`, `user_id`, `created_at`, `updated_at`뿐이다.
- `frontend/components/deployment-status-monitoring.tsx`(46곳), `dashboard-overview.tsx`(14곳)는 `repo`, `full_name`, `branch`, `latest_deployment`(이미지, 커밋 메시지·작성자, replica ready 수, CPU·메모리, 소요 시간, service URL), `auto_deploy_enabled`를 읽는다.
- `getDeploymentConfig(owner, repo)`는 `repo_name === repo`로 저장소를 찾는데 화면이 넘기는 `repo`가 `undefined`라 찾지 못하고, 화면은 실패 시 기본값 설정을 만들어 쓴다.
- `github-integration-panel.tsx` `handleConfigure`는 `alert`만 띄운다.
- 백엔드에 있는 조회: `GET /api/v1/deployments?repository_id=&page=&size=`(최신순), `GET /deployments/{id}`, `GET /deployments/{id}/status`(상태·실패 사유), `GET /repositories/{id}/config`.

## 범위
- 포함: Deployments 화면·대시보드의 저장소·최신 배포 표시를 실제 응답에 맞춤, 설정 조회 실패 시 기본값 사용 제거, Configure로 배포 설정 편집(Q1), 백엔드가 주지 않는 값의 처리(Q2), 문서(architecture.md §9 해당 행 삭제, `frontend/README.md`)
- 제외: Kubernetes 실제 상태(ready replica, 자원 사용량) 조회 API, 배포 로그(#54), 모니터링·alerts 등 다른 stub 화면(#59), 백엔드 API 변경(Q2·Q3 답에 따라 달라짐)

## 입출력·계약
- API: 변경 없음 (Q2·Q3에서 백엔드 확장을 고르면 범위 변경)
- 화면이 쓰는 응답 필드: 저장소 `id`, `owner`, `repo_name`. 배포 `id`, `status`(`PENDING`, `UPLOADING_SOURCE`, `BUILDING`, `DEPLOYING`, `SUCCESS`, `FAILED`, `CANCELED`), `branch_name`, `commit_hash`, `image_uri`, `fail_reason`, `started_at`, `finished_at`, `created_at`. 설정은 `DeploymentConfigResponse` 전체
- 저장: 없음

## 권한·안전
- 기존 API를 그대로 쓴다. 설정 변경은 FULL과 저장소 소유권 검사(`ResourceAccessService`)를 거친다.
- 허용되지 않은 ConfigMap·Secret·pull secret 이름은 기존처럼 서버가 거절하고, 화면은 그 오류 메시지를 보여준다.
- 비밀값 없음 (Secret은 이름만 다룬다).

## 제약
- 참조: [architecture](../../architecture.md) §6, §9, [frontend/DESIGN.md](../../../frontend/DESIGN.md), AGENTS.md §5 (백엔드 API가 없는 기능을 실제 기능처럼 보이게 하지 않는다)
- 상태를 추정하거나 고정값으로 채우지 않는다. 관측할 수 없으면 표시하지 않거나 "알 수 없음"으로 드러낸다.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| 배포가 없는 저장소 | 저장소 이름과 "배포 없음" | 설정은 정상 조회 |
| 최신 배포가 `FAILED` | 상태와 실패 사유 | - |
| 설정 조회 실패 | 오류 표시, 설정 편집 비활성 | 기본값으로 저장하지 않음 |
| 허용되지 않은 참조 이름으로 저장 | 서버 오류 메시지 | 설정 변경 없음 |
| 저장소 여러 개 | 저장소마다 최신 배포 | 저장소마다 배포 1건 조회 (Q3) |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-09 Q1 Configure 버튼 → Deployments 화면의 해당 저장소 상세로 이동하고, 빠진 설정(빌드 방식, 이미지 템플릿, pull secret 이름, 서비스 타입, NodePort, 컨테이너 포트, 도메인) 편집을 그 상세에 추가한다. (설정 편집을 한 곳에 모은다)
- 2026-10-09 Q2 백엔드가 주지 않는 값 → 화면에서 제거하고 있는 값만 표시한다. 소요 시간은 `started_at`·`finished_at`으로 계산하고, URL은 설정의 `domain_url`을 쓴다. 백엔드 API는 바꾸지 않는다. (추정값 없음, 프론트만 수정)
- 2026-10-09 Q3 최신 배포 조회 → 저장소마다 `GET /deployments?repository_id=&size=1`을 호출한다. (저장소 수가 적고 API 변경이 없다)
- 2026-10-09 (구현 중) 배포 목록 API의 쿼리 파라미터는 `repository_id`가 아니라 `repositoryId`다(Jackson snake_case는 쿼리 파라미터에 적용되지 않음). 기존 프론트·CLI도 `repositoryId`를 쓴다. (사실 정정)
- 2026-10-09 (구현 중) 설정 조회 실패 시 기본값을 만들던 `getDeploymentConfig`를 오류를 던지도록 바꿨다. 저장 경로(`putDeploymentConfig`)는 저장 직전에 조회한 설정을 기준으로만 PUT 본문을 만들므로, 조회가 실패하면 PUT을 보내지 않는다. 이것은 #66의 내용과 같아 이 PR에서 함께 해결하고 #66을 닫는다. (스펙 범위의 "설정 조회 실패 시 기본값 사용 제거"와 같은 코드)
- 2026-10-09 (구현 중) 백엔드 `LocalDateTime`은 시간대 없이 서버 시각으로 직렬화된다. 운영 서버와 DB가 UTC이므로 화면은 시간대가 없는 값을 UTC로 해석한다. 서버 시간대가 바뀌면 표시가 어긋난다. (구현 재량, 기록)
- 2026-10-09 (구현 중) 배포 상태 배지는 배포 기록 상태다. `SUCCESS`를 이전처럼 "Running"으로 표시하지 않고 "Succeeded"로 표시한다. Pod 실제 상태는 관측하지 않는다. (상태 추정 금지 규칙)
- 2026-10-09 (구현 중) 배포가 없는 저장소도 설정 대화상자를 열 수 있게 했다(이전에는 최신 배포가 없으면 설정 탭이 보이지 않음). 배포가 없으면 Scale·Rollback·Restart·Logs 버튼은 비활성이다. (구현 재량)
- 범위 밖 발견: 대시보드 상단 통계 카드(`getDashboardData`)가 고정값 stub이라 저장소가 있어도 "No repositories connected"를 표시한다. architecture.md §9 #59 행에 추가했다.
- 기본값 가정: 화면 검증은 로컬 백엔드(PostgreSQL)와 프론트 dev 서버를 실제로 붙여 브라우저로 확인한다. 타입 검사만으로 완료로 보지 않는다. (#74에서 토글을 타입 검사만으로 완료 처리한 것이 이 이슈의 원인 중 하나)

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 저장소 이름과 최신 배포 표시 | 패키징 JAR + PostgreSQL 16 컨테이너 + 프론트 dev 서버, 로컬 테스트 사용자·저장소 2개(최신 배포 SUCCESS·이전 FAILED / 배포 없음), 브라우저 확인 | 로컬 | 이름·브랜치·상태 `Succeeded`·커밋·이미지·시작 시각("30m ago")·소요 시간(3m 0s)·도메인 표시. 배포 없는 저장소는 "No Deployments", 버튼 비활성 |
| 콘솔에서 바꾼 설정이 API에 반영 | 배포 없는 저장소에서 pull secret 끔, 서비스 타입 NODE_PORT·NodePort 30090 저장 후 `GET /repositories/2/config` | 로컬 | `image_pull_secret_enabled=false`, `service_type=NODE_PORT`, `node_port=30090`, 나머지 값 유지 |
| 허용되지 않은 참조는 서버가 거절하고 화면이 메시지 표시 | 허용 목록이 없는 로컬 서버에서 envFrom이 있는 저장소 저장 | 로컬 | 400 "허용되지 않은 Kubernetes 리소스 참조입니다" 토스트, 설정 변경 없음 |
| Configure 버튼 | GitHub 화면 Configure 클릭 | 로컬 | Deployments 화면으로 이동해 해당 저장소 Configuration 탭이 열림 |
| 대시보드 최신 배포 | 대시보드 Active Deployments | 로컬 | 저장소별 상태·커밋·브랜치 표시 |
| 조회 실패 시 기본값으로 저장하지 않음 | 목록을 띄운 뒤 백엔드 중지, Refresh와 저장 시도 | 로컬 | "Failed to load repositories" 배너, 저장은 "Save failed", PUT 요청 없음. GET 실패·PUT 성공이 섞이는 경우는 코드로만 확인(프론트 테스트 도구 없음) |
| 타입·빌드 | `npx tsc --noEmit -p .`, `npm run build` | 로컬 | 통과 |
| 운영 콘솔에서 IoT 저장소 표시·설정 확인 | 머지 후 확인 | 운영 | 머지 후 `운영 반영`에 기록 |

## 운영 반영
- 2026-10-09 PR #82 머지(`c83c13c`). Build and deploy의 build·deploy 성공, 백엔드 revision과 프론트 `release.txt`가 머지 커밋과 일치. #66 함께 닫힘. 운영 콘솔 화면 확인은 사용자 로그인이 필요해 사용자 확인 결과를 받아 기록한다.

## 회고
- 어긋난 점: 화면이 백엔드에 없는 필드를 읽어 저장소 이름과 배포가 표시되지 않았고, #74의 토글은 타입 검사만으로 완료 처리됐다. 실패 시 기본값을 돌려주는 함수가 빈 화면과 잘못된 저장을 가렸다.
- 원인 분류: 완료 기준 느슨 (화면을 실제 API에 붙여 확인하지 않음)
- 고친 위치: 화면 변경의 완료 증거를 실제 백엔드에 붙인 브라우저 확인으로 두었고(이 스펙), 조회 실패를 기본값 대신 오류로 전달하게 했다.
