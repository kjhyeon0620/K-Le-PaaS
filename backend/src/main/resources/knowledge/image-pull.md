# 이미지를 받지 못할 때 (ImagePullBackOff, ErrImagePull, InvalidImageName)

## 증상
- Pod가 Pending이고 상태 사유가 ErrImagePull 또는 ImagePullBackOff다.
- 이벤트에 `Failed to pull image`가 반복된다.
- 상태 사유가 InvalidImageName이면 이미지 참조 문자열 자체가 잘못된 것이다.

## 원인 구분
- `failed to resolve reference`, `no such host`, `i/o timeout`: 레지스트리 주소가 틀렸거나 노드에서 닿지 않는다 (image_not_pullable).
- `not found`, `manifest unknown`: 저장소나 태그가 없다. CI가 이미지를 push하기 전에 배포했는지 확인한다 (image_not_pullable).
- `unauthorized`, `denied`: 비공개 이미지인데 pull secret이 없거나 권한이 없다. 앱 설정의 image pull secret 사용 여부를 확인한다 (image_not_pullable).
- `InvalidImageName`, `couldn't parse image reference`: 대문자, 공백, 잘못된 태그 문자 같은 참조 형식 오류다 (invalid_image_reference).

## 확인할 것
- 배포 요청의 image_uri와 직전 성공 배포의 이미지 차이
- 레지스트리에서 그 태그가 실제로 있는지
- 공개 이미지면 pull secret을 끈 설정인지, 비공개면 허용된 pull secret이 지정됐는지
