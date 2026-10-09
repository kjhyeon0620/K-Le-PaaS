---
issue: 74
title: 공개 이미지 배포에도 pull secret을 강제해 만료된 자격 증명으로 배포가 실패하는 문제
status: done
size: M
type: fix
branch: fix/#74-optional-image-pull-secret
---

# 공개 이미지 배포에도 pull secret을 강제해 만료된 자격 증명으로 배포가 실패하는 문제

## 목적 / 성공 조건
- 목적: 공개 이미지를 쓰는 저장소는 pull secret 없이 배포한다. 쓰지 않는 자격 증명이 만료돼 배포가 실패하는 일이 없어진다.
- 기술적 완료: 배포 설정에서 pull secret을 끄면 Deployment에 `imagePullSecrets`가 없다. 설정을 바꾸지 않은 저장소는 지금과 같다.
- 운영: IoT 저장소의 pull secret을 끈 뒤 재배포가 성공한다.

## 범위
- 포함: 배포 설정 `image_pull_secret_enabled`(Flyway V2), manifest 반영, 허용 참조 검사 조정, 콘솔 토글, 문서
- 제외: 이미지가 공개인지 자동 판단, 비공개 이미지 자격 증명의 만료 감지·교체, 실패 원인 기록(#54), Kaniko 빌드의 push secret

## 입출력·계약
- API `GET/PUT /api/v1/repositories/{id}/config`
  - 응답에 `image_pull_secret_enabled`(boolean) 추가
  - 요청 `image_pull_secret_enabled`: 생략(null)하면 기존 값 유지, `false`면 pull secret을 쓰지 않음, `true`면 기존처럼 `image_pull_secret_name`(없으면 기본값) 사용
  - `image_pull_secret_name` 동작은 바뀌지 않는다. 꺼져 있어도 이름은 저장해 둔다(다시 켤 때 사용).
- 저장: `V2__deployment_image_pull_secret_enabled.sql`, `deployment_configs.image_pull_secret_enabled boolean default true not null`. 기존 행은 true (동작 변경 없음). 이전 릴리스는 이 컬럼을 무시한다.
- Kubernetes: 꺼져 있으면 Pod spec에 `imagePullSecrets`를 넣지 않는다.

## 권한·안전
- 설정 변경은 기존 저장소 설정 API(FULL, 소유권 검사)를 그대로 쓴다.
- 운영자 허용 참조 검사(`RuntimeResourcePolicy`)는 pull secret을 쓸 때만 pull secret 이름을 검사한다. 끄는 것은 참조를 줄이는 방향이라 허용이 필요 없다. ConfigMap·Secret 검사는 그대로다.
- 비밀값 없음.

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| 설정을 바꾸지 않은 기존 저장소 | 기존과 같음 | `imagePullSecrets`에 설정 이름 또는 기본값 |
| pull secret을 끈 저장소 | 배포 성공 (공개 이미지) | `imagePullSecrets` 없음, 허용 목록에 pull secret이 없어도 통과 |
| 끈 상태로 비공개 이미지 배포 | 배포 실패 (rollout 타임아웃) | 이미지 pull 실패. 원인 기록은 #54 |
| 끈 상태에서 이름만 수정 | 이름 저장, 계속 꺼짐 | 다시 켜면 그 이름 사용 |
| 다시 켬 | 기존과 같음 | 허용 참조 검사 다시 적용 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 2026-10-05 표현 방식 → 별도 설정 값 `image_pull_secret_enabled`(기본 true, V6). 빈 문자열을 "없음"으로 쓰지 않는다. (null과 ""의 차이가 API에서 드러나지 않고, 기존 행의 의미가 바뀔 수 있어서)
- 2026-10-09 (구현 중) #75(PostgreSQL 전환, PR #76)가 먼저 머지되어 H2 V1~V5가 PostgreSQL 기준선 V1로 바뀌었다. 이 브랜치를 rebase하고 마이그레이션을 V6에서 `V2__deployment_image_pull_secret_enabled.sql`로 바꿨다. SQL은 그대로다. H2에서 한 검증은 PostgreSQL에서 다시 한다. (사실 정정. V6는 머지·적용된 적이 없어 번호를 바꿀 수 있다)
- 기본값 가정: 새 저장소도 기본은 켜짐(true). 등록 시 기본값을 바꾸면 기존 NCP 경로의 동작이 바뀐다.
- 기본값 가정: 콘솔 배포 상세에 pull secret 사용 토글을 둔다.

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 끄면 manifest에 `imagePullSecrets` 없음, 기존 저장소는 그대로 | `KubernetesManifestGeneratorTest` | 로컬 | 통과 |
| 수정 요청: 생략 시 유지, false/true 반영, 끈 상태에서는 허용 검사에 pull secret을 넘기지 않음 | `RepositoryServiceTest` | 로컬 | 통과 |
| 끈 설정은 허용 목록에 pull secret이 없어도 통과 | `RuntimeResourcePolicyTest` | 로컬 | 통과 |
| 잘못된 pull secret을 참조하면 공개 이미지도 실패(재현), 끄고 재배포하면 성공 | 임시 k3s + `KubernetesManifestGenerator`, 공개 GHCR 이미지 | 실제 클러스터 (로컬) | 재현·통과 (GHCR 403 → 끈 뒤 약 8초에 Available) |
| 백엔드 전체, 프론트 타입 | `./gradlew test bootJar` (192개, H2), `npx tsc --noEmit -p .` | 로컬 | 통과 (#75 이전) |
| 백엔드 전체 (rebase 후) | `./gradlew test bootJar` 1회 | 로컬 (Testcontainers PostgreSQL 16) | 190개 통과, 실패 0 (#75에서 H2 전용 테스트 2개 삭제) |
| ~~V5 DB에 V6 적용, validate 기동, 기존 행 true~~ | 패키징 JAR | 로컬 H2 | 통과 (#75 이전. 아래 PostgreSQL 검증으로 대체) |
| 운영 DB 사본(PostgreSQL V1)에 V2 적용 후 validate 기동, 기존 행 true | 운영 DB를 `pg_dump`로 로컬 임시 PostgreSQL 16 컨테이너에 복원(파일로 저장하지 않음)하고 패키징 JAR 기동 | 운영 DB 사본 | V2 적용, readiness `UP`, IoT 행 `image_pull_secret_enabled = true`. 컨테이너 삭제 |
| IoT를 끈 뒤 재배포 성공 | IoT 워크플로 재실행 | 운영 | 머지 후 (운영 반영에 기록) |

## 운영 반영
- 2026-10-09 PR #77 머지(`c1a8d8f`). Build and deploy의 build·deploy 성공, 운영 readiness `UP`, revision 일치, 운영 PostgreSQL Flyway `2 deployment image pull secret enabled` 성공. 기존 IoT 행은 `image_pull_secret_enabled = true`.
- 2026-10-09 IoT 저장소의 pull secret을 API(`PUT /repositories/{id}/config`)로 끔. 콘솔 토글은 Deployments 화면이 백엔드 응답 필드와 맞지 않아 동작하지 않았다(#78).
- 2026-10-09 IoT 배포 워크플로 재실행: 배포 `SUCCESS`(커밋 84b385f), Deployment 1/1, `imagePullSecrets` 없음, 앱 HTTP 200. 2026-10-03부터 `ImagePullBackOff`였던 Pod가 교체됐다.
- 정정: 완료 증거의 "콘솔 토글"은 타입 검사만 했고 실제 API에 붙여 확인하지 않았다. 화면 수정은 #78.

## 회고
- 어긋난 점: 공개 이미지에 필요 없는 자격 증명을 배포마다 강제로 참조해, 그 자격 증명이 만료되자 배포가 실패했다. #50·#65의 운영 검증 대상이 IoT 한 곳뿐이라 이전 배포(sha-0c3265f)도 같은 이유로 실패하고 있었던 것을 #65 확인 중에야 알았다. K-Le-PaaS에는 "rollout 타임아웃"만 남아 원인을 찾는 데 서버 접속이 필요했다.
- 원인 분류: 외부 제약 미인지 (레지스트리는 잘못된 자격 증명을 공개 이미지에도 거부한다)
- 고친 위치: 배포 설정에서 pull secret을 끌 수 있게 하고 문서에 공개·비공개 이미지별 기준을 적었다. 실패 원인 기록은 #54에서 다룬다.
