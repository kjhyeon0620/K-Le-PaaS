# 컨테이너가 반복 종료할 때 (CrashLoopBackOff, CreateContainerConfigError)

## 증상
- Pod 상태 사유가 CrashLoopBackOff이고 재시작 횟수가 늘어난다. 직전 컨테이너 로그(previous_logs)에 종료 직전 메시지가 있다.
- CreateContainerConfigError는 컨테이너가 시작조차 하지 못한 경우로, 참조한 Secret·ConfigMap이나 키가 없을 때 생긴다.

## 원인 구분
- 직전 로그에 필수 환경 변수, 비밀번호, 설정 값이 없다는 메시지가 있으면 환경 변수 누락이다 (required_env_missing). 직전 성공 배포 대비 env 이름이 삭제됐는지 함께 본다.
- 로그 없이 exit code 137, 마지막 종료 사유 OOMKilled면 메모리 한도 부족이다.
- 앱 고유의 오류 코드만 남는 경우가 있다. 앱 운영 문서나 과거 사건에서 그 코드의 의미를 확인한다.

## 확인할 것
- 배포 설정의 env 이름 목록과 앱이 요구하는 환경 변수
- envFrom으로 참조한 ConfigMap·Secret이 namespace에 있고 허용된 참조인지
- 직전 성공 배포와의 설정 차이
