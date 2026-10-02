---
issue: TBD            # GitHub 이슈 번호
title:
status: draft         # draft | ready | in-progress | done | dropped
size: M               # S | M | L
type: feat            # feat | fix | docs | test | ci | refactor
branch:               # <type>/#<issue>-<slug>
---

# <title>

## 목적 / 성공 조건
- 목적: (누가 무엇을 할 수 있게 되는가, 어떤 거짓 동작이 사라지는가)
- 기술적 완료: (정상 입력에 시스템이 무엇을 하는가)
- 운영: (실패했을 때 사용자·운영자·agent가 무엇을 보고, 어떻게 이어서 진행하는가)

## 범위
- 포함:
- 제외: (이번에 만들지 않는 것. 명시하지 않으면 구현자가 범위를 넓힐 수 있음)

## 입출력·계약
- API: `METHOD /api/v1/...` 요청·응답 필드(snake_case), 에러 코드
- CLI: 명령, `--json` 출력, exit code
- 저장: 엔티티·컬럼, Flyway 마이그레이션 `V<n>__...`, 기존 행 기본값
- Kubernetes: 생성·변경하는 리소스와 필드
- 상태 전이:

## 권한·안전
- 호출 주체와 토큰 scope:
- 소유권 검사 위치:
- 위험도와 승인:
- 비밀값 처리:

## 제약
- 참조: [architecture](../../architecture.md), [ADR](../../adr/README.md)
- (이 이슈에만 적용되는 제약)

## 예외·경계 상황
| 상황 | 사용자·agent가 보는 것 | 시스템 동작 |
|---|---|---|
| 잘못된 입력 | | |
| 권한 없음 / 다른 사용자 리소스 | | |
| 중복·동시 요청 | | |
| 외부 시스템(GitHub, Kubernetes, Gemini, NCP) 실패·지연 | | |
| 백엔드 재시작 중 진행 상태 | | |

## 미결 질문 (Open)
- [ ] Q1.

## 결정 기록 (Decisions)
- YYYY-MM-DD Q1 → 답. (이유) [승격: ADR-NNNN / architecture]

## 완료 증거
| 증명할 것 | 방법 | 환경 (로컬 / 운영 DB 사본 / 실제 클러스터 / 운영) |
|---|---|---|
| | `./gradlew test --tests '...'` | 로컬 |

## Tasks (L 등급만)
### T1.
- 먼저 읽을 것:
- 변경:
- 금지:
- 검증:

## 운영 반영
- (머지 후 배포 workflow 결과, 운영 DB 마이그레이션 버전, 수동 절차 여부. 원자료는 `.local/evidence/`)

## 회고
- 어긋난 점:
- 원인 분류: 스펙 누락 / 규칙 미전달 / 테스트 부재 / 외부 제약 미인지 / 완료 기준 느슨
- 고친 위치:
