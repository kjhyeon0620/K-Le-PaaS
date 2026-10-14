---
issue: 109
title: "refactor: CLI env 키 변환·Fabric8 버전·미사용 쿼리·모바일 사이드바 정리"
status: done
size: M
type: refactor
branch: refactor/#109-small-gaps
---

# refactor: CLI env 키 변환·Fabric8 버전·미사용 쿼리·모바일 사이드바 정리

## 목적 / 성공 조건
- 목적: architecture §9의 작은 격차 네 가지를 정리해, 다음 기능에서 같은 오류가 나지 않게 한다.
- 기술적 완료:
  1. CLI 요청 본문의 맵 값(env 이름)이 그대로 서버에 간다.
  2. Fabric8이 실행 중인 Jackson(2.20)과 같은 기준의 버전이 되어, 서버에서 읽은 객체(`managedFields` 포함)를 그대로 복제·교체해도 직렬화가 실패하지 않는다.
  3. 쓰이지 않는 `DeploymentRepository.findBySourceRepositoryUserId`가 없다.
  4. 375px 폭에서 콘솔 사이드바가 본문을 가리지 않는다.
- 운영: 동작 변화는 없다(4는 화면 배치). Fabric8 업그레이드 뒤에도 배포·재시작·스케일·rollout 판정이 같다.

## 현재 동작 (2026-10-15 코드 확인)
- `frontend/cli/api.mjs` `toSnakeCase`: 본문의 모든 객체 키를 재귀로 snake_case로 바꾼다. `{"env_vars": {"SAMPLE_GREETING": "x"}}`의 `SAMPLE_GREETING`도 바뀐다. 지금 env를 보내는 CLI 명령은 없다.
- `build.gradle`: `io.fabric8:kubernetes-client:7.2.0`(Jackson 2.18.3 기준). Spring Boot 의존성 관리로 Jackson 2.20.2가 실행된다. #86에서 `replaceWithObservedVersion`이 `managedFields`를 빼고 보내 우회했다. Maven Central 기준 Fabric8 7.6.1은 Jackson 2.20.0, 7.7.0 이상은 2.21 기준이다.
- `findBySourceRepositoryUserId`: 호출하는 코드가 없다(#79에서 확인).
- `components/sidebar.tsx`: 기본 펼침(w-64), 수동 접기만 있다. 375px에서도 256px 사이드바가 펼쳐져 본문이 좁아진다.

## 범위
- 포함:
  - CLI: `env_vars` 값(맵)은 키를 바꾸지 않는다. 계약 테스트.
  - Fabric8 7.2.0 → 7.6.1(Jackson 2.20 기준, 같은 major). `managedFields` 우회는 유지한다(서버가 관리하는 필드를 보내지 않는 것은 그대로 맞다). 서버 객체를 그대로 복제하는 회귀 테스트를 추가한다.
  - 미사용 쿼리 삭제.
  - 사이드바: 768px 미만에서는 접힌 아이콘 막대(w-16)로 시작하고, 그 폭으로 줄어들면 접는다. 펼침 버튼은 그대로 둔다.
- 제외: Jackson 3 전환, Fabric8 7.7 이상(Jackson 2.21 기준), 사이드바 오버레이 drawer, 다른 CLI 명령 추가.

## 입출력·계약
- API·CLI 출력·exit code·스키마 변경 없음. 의존성 버전만 바뀐다.

## 권한·안전
- 변경 없음. Fabric8 업그레이드는 Kubernetes 호출 경로 전체에 걸치므로 실제 클러스터에서 확인한다.

## 결정 기록 (Decisions)
- 2026-10-15 등급 M. Kubernetes 클라이언트 의존성 업그레이드가 배포·재시작·스케일·rollout 판정 경로 전체에 걸친다. 나머지 세 항목은 S다. (docs/specs/README.md §3)
- 2026-10-15 Fabric8 대상 버전은 7.6.1. 실행 중인 Jackson(2.20.2)과 같은 2.20 기준인 마지막 버전이다. 7.7 이상은 Jackson 2.21을 요구해 Spring Boot 관리 버전과 다시 어긋난다. (조사 결과, Maven Central `kubernetes-client-project` pom)
- 2026-10-15 사이드바는 drawer 대신 좁은 폭에서 접힌 막대로 시작한다. 완료 조건(본문을 가리지 않음)을 가장 작은 변경으로 만족한다. (기본값)
- 2026-10-15 검증: Fabric8은 단위 테스트 + 패키징 JAR + 임시 k3s에서 apply·rollout 판정·restart·scale을 실제로 확인한다(#57·#101과 같은 방식). 사이드바는 브라우저 375px·데스크톱. (기본값)

## 완료 증거
| 증명할 것 | 방법 | 환경 |
|---|---|---|
| env 이름이 그대로 전송 | CLI 계약 테스트 | 로컬 |
| 서버 객체(`managedFields`)를 그대로 복제 가능, restart·scale·replace 기존 테스트 | `KubernetesManifestGeneratorTest` | 로컬 |
| 실제 클러스터에서 apply·rollout SUCCESS·restart·scale | 패키징 JAR + 임시 k3s | 로컬 실제 클러스터 |
| 375px 사이드바가 본문을 가리지 않음, 데스크톱 그대로 | 브라우저 DOM 측정 | 로컬 |
| 회귀 | `./gradlew test`, CLI 테스트, `npx tsc --noEmit`, `npm run build` | 로컬 |

### 실행 결과 (2026-10-15)
- 로컬: CLI `cli/api.test.mjs` — 본문 필드는 snake_case, `env_vars` 맵 키(`SAMPLE_GREETING`, `fooBar`)는 그대로 전송. CLI 테스트 전체 13개 통과.
- 로컬: `KubernetesManifestGeneratorTest`에 서버 객체(`managedFields` 포함)를 그대로 복제하는 테스트를 추가했다. **Fabric8 7.2.0에서 먼저 실패를 재현**했다(`JsonMappingException ... keySerializer` null, #86과 같은 오류). 7.6.1로 올린 뒤 통과. Kubernetes 패키지 테스트 50개(기존 restart·scale·replace 포함) 통과. 런타임 classpath의 `kubernetes-client-api`가 7.6.1임을 `dependencyInsight`로 확인.
- 로컬 패키징 JAR(Fabric8 7.6.1) + PostgreSQL 16 + 임시 k3s v1.30.6(Docker, `redis:7-alpine` import): 배포 SUCCESS(이미지·env 이름 `SAMPLE_GREETING`·NodePort 30109 적용, ready 1) → restart(generation 1→2, `restartedAt` annotation, 서버의 managedFields 유지) → scale 2(ready 2). 결과 `PASS`.
- 로컬 브라우저(mock API): 375px에서 사이드바 64px(접힌 막대)·본문 311px(이전 119px)·가로 스크롤 없음. 데스크톱 1024px는 펼친 256px·라벨 표시로 그대로.
- 로컬: `./gradlew test` 313개 통과(건너뜀 2: 실제 모델 평가), `npx tsc --noEmit`, `npm run build` 통과.
- 범위 밖 발견: 375px에서 콘솔 상단 헤더(로고 글자 줄바꿈, 검색창 잘림)가 좁다. 사이드바와 별개인 앱 공통 헤더 배치다.
- 증거: `.local/evidence/0109-small-gaps/` (`verify-109-k3s.py`, `k3s-result.json`, `jar.log`).

## 문서 동기화
- `docs/architecture.md` §9에서 해소한 항목 3개(Fabric8·CLI env 키·미사용 쿼리)와 사이드바 항목 삭제
- `docs/product.md` #109 행

## 운영 반영
- (머지 후 배포 workflow 결과)

## 회고
- 어긋난 점: (1) #86에서는 증상(`managedFields` 직렬화)만 우회하고 원인(Fabric8과 실행 Jackson의 기준 버전 차이)은 격차로 남겼다. 이번에 같은 오류를 테스트로 재현하고 버전을 맞췄다. (2) k3s 확인 스크립트가 처음에 노드 준비 전 이미지를 import했고, 로컬 Docker의 multi-platform 이미지 저장소 때문에 `docker save` 결과를 그대로 import하지 못했다(`--platform linux/arm64`로 해결). (3) `kubectl get -o json`은 기본으로 managedFields를 숨겨 첫 판정이 틀렸다.
- 원인 분류: 외부 제약 미인지(의존성 관리가 정한 Jackson 버전과 라이브러리 기준 버전, 로컬 검증 도구 동작).
- 고친 위치: `build.gradle` Fabric8 7.6.1, 복제 회귀 테스트(버전이 다시 어긋나면 실패), 검증 스크립트. 의존성을 올릴 때는 그 라이브러리의 기준 Jackson 버전과 Spring Boot 관리 버전을 함께 본다 — 이 스펙 결정 기록에 남겼다.
