---
issue: 51
title: 앱별 readiness/startup probe와 CPU·메모리 자원 설정
status: done
size: L
type: feat
branch: feat/#51-app-probes-resources
---

# 앱별 readiness/startup probe와 CPU·메모리 자원 설정

> SDD를 도입하기 전에 착수한 이슈를 GitHub 이슈 #51 본문과 로컬 검증 기록(2026-10-01)으로 역작성한 스펙이다.

## 목적 / 성공 조건
- 목적: HTTP 앱이 실제로 요청을 처리할 수 있을 때만 rollout이 성공으로 판정된다. 기동 시간과 자원 요구가 다른 앱을 각자의 설정으로 실행한다.
- 기술적 완료: 설정한 probe와 requests/limits가 Deployment 컨테이너 spec에 반영된다.
- 운영: 잘못된 health 경로를 설정한 배포는 성공으로 기록되지 않고, 기존 Pod는 계속 서비스한다.

## 범위
- 포함: `DeploymentConfig`의 readiness probe(HTTP 경로, 포트, 초기 지연, 주기, 실패 임계값), 선택적 startup probe, CPU·메모리 requests/limits, Flyway V4, DTO 매핑과 검증, manifest 반영, 저장소 설정 UI 입력
- 제외: liveness probe, HPA 자동 확장, 기존 앱의 기본값 강제 적용

## 입출력·계약
- API: `GET/PUT /api/v1/repositories/{id}/config`에 probe·자원 필드 추가 (snake_case)
- 저장: Flyway `V4`, 새 컬럼은 nullable. 기존 행은 probe와 자원 설정 없음 = 기존 동작
- Kubernetes: 컨테이너의 `readinessProbe`, `startupProbe`, `resources.requests/limits`

## 권한·안전
- 설정 변경은 기존 저장소 설정 API의 소유권 검사를 그대로 쓴다 (FULL).
- 비밀값 없음

## 예외·경계 상황
| 상황 | 사용자가 보는 것 | 시스템 동작 |
|---|---|---|
| 경로 형식 오류, 0 이하 값, requests > limits | 400 | 저장하지 않음 |
| 잘못된 health 경로로 배포 | 배포 실패 (rollout 타임아웃) | 기존 Pod 유지 |
| probe·자원 미설정 앱 | 기존과 같음 | manifest에 probe·resources 없음 |
| 설정에서 probe 제거 후 재배포 | 제거됨 | Deployment 전체 교체로 필드 삭제 반영 |
| 부분 수정 | 다른 필드 유지 | 요청에 없는 필드는 보존 |

## 미결 질문 (Open)
- 없음

## 결정 기록 (Decisions)
- 이슈 기준: 기존 행은 probe 없음 + 기존 동작과 같은 기본값으로 이관하고, 부분 수정 시 다른 필드를 보존한다.
- 2026-10-01 (구현 중 발견) 같은 앱 재배포가 `409 FieldManagerConflict`로 실패하는 기존 결함이 있었다. 최초 생성은 `create()`, 재배포는 server-side apply로 field manager가 달라서 값이 바뀐 필드마다 충돌했다.
  → 기존 Deployment는 관측한 resourceVersion으로 전체 교체(PUT, scale·restart와 같은 방식)한다. Service·Ingress는 `forceConflicts()` apply. 소유권 라벨과 resourceVersion 확인은 유지한다.
  → 강제 apply만으로는 공동 소유로 남은 필드(제거한 probe)가 지워지지 않아 전체 교체를 택했다.
- 2026-10-01 (구현 중) 수정 요청의 계약 → `health_probe`·`resources`를 생략하면 기존 값 유지, 보내면 그룹 전체 교체. `path`가 비어 있으면 probe 해제, 자원 값이 모두 비어 있으면 requests/limits 해제. 시간·임계값을 비우면 Kubernetes 기본값. startup probe는 readiness와 같은 경로·주기를 쓰고 `startup_failure_threshold`가 있을 때만 만든다. (기존 콘솔·CLI의 전체 PUT이 새 필드를 몰라도 설정이 지워지지 않게 하기 위해)
- 2026-10-01 (구현 중 발견) 콘솔이 설정 조회에 실패하면 기본값으로 저장해 기존 설정을 덮어쓸 수 있다 → 범위 밖, #66으로 분리

## 완료 증거
| 증명할 것 | 방법 | 환경 | 결과 |
|---|---|---|---|
| 정상 health 경로 앱 배포 성공, probe·자원 반영 | `KubernetesManifestGenerator` deploy + Available 대기 | 실제 k3s (임시 컨테이너) | 통과 |
| 잘못된 경로 재배포는 성공으로 판정하지 않고 기존 Pod 유지 | 같은 절차 | 실제 k3s | 통과 |
| 새 앱을 잘못된 경로로 배포하면 실패 | 같은 절차 | 실제 k3s | 통과 |
| 설정 없는 앱은 기존과 같은 manifest, probe 제거 반영 | 같은 절차 | 실제 k3s | 통과 |
| 재배포 시 이미지·Service·Ingress 변경이 409 없이 반영 | 같은 절차 | 실제 k3s | 통과 |
| 매핑·검증·manifest·JPA 왕복 | `RepositoryServiceTest`, `RepositoryControllerTest`, `KubernetesManifestGeneratorTest`, `CommandLogStatusSchemaValidationTest` | 로컬 | 통과 |
| 백엔드 전체 | `./gradlew test bootJar` (181개) | 로컬 | 통과 |
| 프론트 타입 | `npx tsc --noEmit -p .` | 로컬 | 통과 |
| 이력 없는 V1 DB에 V2~V4 적용, validate 기동, 기존 행 유지 | 패키징 JAR | 로컬 H2 | 통과 |
| 운영 DB 사본(V3)에 V4 적용 후 validate 기동 | 패키징 JAR | 운영 DB 사본 | 통과 (2026-10-02, readiness `UP`, V4 1건 적용, 사본 삭제) |

## 운영 반영
- 머지 후 배포 workflow와 운영 DB V4 적용 결과를 여기에 요약한다.

## 회고
- 어긋난 점: 이슈에 없던 재배포 409 결함을 실제 k3s 검증에서 처음 발견했다. 단위 테스트(mock client)로는 field manager 충돌이 드러나지 않았다.
- 원인 분류: 테스트 부재 (실제 API 서버의 server-side apply 동작을 검증하지 않음)
- 고친 위치: 완료 증거에 "실제 클러스터" 환경 열을 두어, Kubernetes 적용 방식이 바뀌는 이슈는 실제 클러스터 검증을 요구한다 ([specs/README.md](../README.md), 템플릿).
