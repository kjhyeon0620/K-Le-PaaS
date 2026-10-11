---
issue: 108
title: rollout 타임아웃을 앱의 startup probe 허용 시간에 맞춤
status: done
size: M
type: fix
branch: fix/#108-rollout-timeout-from-probe
---

# rollout 타임아웃을 앱의 startup probe 허용 시간에 맞춤

## 목적 / 성공 조건
- 목적: 기동이 긴 앱이 정상인데도 고정 타임아웃(120초)으로 FAILED가 되지 않게, 앱이 startup probe로 허용한 기동 시간만큼 rollout 판정을 기다린다.
- 완료: startup 허용 시간 안에 기동하는 앱은 SUCCESS, 끝내 기동하지 못하는 앱은 늘어난 시간 뒤 FAILED. 진행 중 판정 창과 일관된다.

## 현재 동작
- `waitForRollout`은 `kubernetes.rollout.timeout-ms`(120초) 동안만 기다린다(#53). 앱의 probe 설정을 보지 않는다.
- 진행 중 배포 판정(`findInProgress`, #52)은 `빌드 타임아웃 + rollout 타임아웃 + 10분` 안에 갱신된 미완료 배포를 진행 중으로 본다.

## 설계
- 앱별 대기 시간 = 기본값(`kubernetes.rollout.timeout-ms`, 120초) + startup 허용 시간, 상한 `kubernetes.rollout.max-timeout-ms`(기본 900초).
  - startup 허용 시간 = `initial_delay_seconds`(없으면 0) + `period_seconds`(없으면 Kubernetes 기본 10) × `startup_failure_threshold`. startup probe가 없으면 0이라 지금과 같다.
  - 기본값은 이미지 pull·스케줄·readiness 몫으로 그대로 두고, 기동 허용만큼 더한다(사용자 결정).
- 대기 시간은 apply 단계에서 적용한 설정으로 계산해 대기 단계로 넘긴다. 타임아웃 사유 문구의 초는 실제 대기 시간이다.
- 진행 중 판정 창 = 빌드 타임아웃 + rollout 상한 + 10분. 어떤 앱이든 대기 중에 멈춘 배포로 보지 않는다.
- 즉시 실패 사유(이미지 pull 실패, CrashLoopBackOff 등)는 지금처럼 기다리지 않고 판정한다.

## 입출력·계약
- 새 설정 `kubernetes.rollout.max-timeout-ms`(기본 900000). API·응답·마이그레이션 변화 없음.

## 결정 기록 (Decisions)
- 2026-10-11 Q1 → 사용자 결정(권장안): 기본값 + startup 허용 시간. max(기본값, 허용 시간)은 기동 허용이 길 때 pull·readiness 여유가 없다.
- 2026-10-11 Q2 → 사용자 결정(권장안): 15분 상한, 설정 키. 잘못된 probe 설정이 배포를 무한히 잡지 않게 하고, 진행 중 판정 창을 상한으로 계산한다.
- 2026-10-11 Q3 → 사용자 결정(권장안): 로컬 k3s로 검증한다.

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| 대기 시간 계산(probe 없음·startup 있음·기본값 생략·상한) | 단위 테스트 | 로컬 |
| 대기 단계가 계산한 시간을 쓰고 사유에 그 초를 적음 | 단위 테스트 | 로컬 |
| 진행 중 판정 창이 상한 기준 | 단위 테스트 | 로컬 |
| 느린 앱 SUCCESS, 기동 못 하는 앱은 늘어난 시간 뒤 FAILED | 패키징 JAR + 임시 k3s (기본값을 줄여 실행) | 로컬 실제 클러스터 |
| 회귀 | PR 전 `./gradlew test` 1회 | 로컬 |

### 실행 결과 (2026-10-12)
- 로컬: 대기 시간 계산(probe 없음·startup 없는 probe → 기본값, 30 + 10×30 → +330초, period 생략 → 10초, threshold 1000 → 상한 900초), 대기 단계가 넘겨받은 1초를 쓰고 사유에 "1초", 진행 중 판정 창 = 30분 + 15분 + 10분, apply 단계가 설정으로 계산한 시간을 넘김.
- 로컬 실제 클러스터 (패키징 JAR + PostgreSQL 16 + 임시 k3s v1.30.6, 기본값을 20초로 줄임, probe `/health` period 5, startup threshold 12 → 60초 허용):

| 경우 | 결과 | 걸린 시간 |
|---|---|---|
| 기동 50초 앱 + startup probe | SUCCESS (기본값만이면 20초에 실패) | 62초 |
| 같은 앱, startup probe 없음 | FAILED READINESS_TIMEOUT, "rollout 타임아웃(20초)" | 21초 |
| 기동하지 못하는 앱 + startup probe | FAILED READINESS_TIMEOUT, "rollout 타임아웃(80초)" | 82초 |

- 기동하지 못하는 앱은 startup probe 실패로 재시작되기 전에 80초 타임아웃으로 판정됐다(restarts=0). 실패 종류는 기존 규칙대로 READINESS_TIMEOUT이다.
- 증거: `.local/evidence/0108-rollout-timeout/` (테스트 앱 이미지, 확인 스크립트, k3s-result.json).

## 운영 반영
- 설정 변경 없음(기본값). 배포 후 startup probe가 없는 앱은 동작이 같다.

## 회고
- 어긋난 점: 없음. 대기 시간을 apply 단계에서 계산해 넘기는 구조라 판정 로직은 바꾸지 않았다.
- 참고: 진행 중 판정 창이 빌드 타임아웃 + 2분 + 10분에서 + 15분 + 10분으로 길어져, 프로세스가 멈춘 배포가 새 배포를 막는 시간이 13분 늘었다. 재시작 대조(#57)가 기동 시 정리하므로 운영 영향은 작다.
