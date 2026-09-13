# Jarvis Android Client

Meta Ray-Ban 글라스와 Android 휴대폰을 연결하는 Jarvis OS 클라이언트입니다.
음성 대화와 명령 판단은 Gemini Live가 담당하고, 기억 저장 및 검색은 별도
`JarvisMemoryServer`에 연결합니다.

## 현재 기능

- 휴대폰 또는 지원되는 Meta 웨어러블에서 카메라 프레임 수신
- 음성 기반 Gemini Live 대화
- 현재 시야가 필요한 경우에만 최신 이미지 1장 캡처 후 Gemini에 전달
- 일상 기억 저장: 이미지, 사용자 메모, AI 장면 해석, 날짜/시간
- 기억/사람/회의 벡터 검색 및 검색 결과 기반 답변
- 회의 녹음, 전사, 요약 및 회의 목록 확인
- 연락처 후보 검색 후 전화 또는 문자 실행
- 선택적 글라스 POV 브라우저 공유

비용과 지연을 줄이기 위해 비디오를 Gemini에 계속 전송하지 않습니다. 앱의
실시간 스트림은 글라스 연결과 화면 표시용이며, AI 시각 질의는 명령이 감지된
뒤 최신 프레임을 캡처하는 방식입니다.

## 프로젝트 구조

```text
samples/CameraAccessAndroid/   Android Studio 프로젝트
samples/CameraAccess/          기존 iOS 샘플
assets/                        README 이미지
```

## Android 실행

1. `samples/CameraAccessAndroid/`를 Android Studio에서 엽니다.
2. DAT SDK 다운로드를 위해 `samples/CameraAccessAndroid/local.properties`에
   GitHub Packages 토큰을 추가합니다.

```properties
github_username=YOUR_GITHUB_USERNAME
github_token=YOUR_TOKEN_WITH_READ_PACKAGES
```

3. `Secrets.kt.example`을 같은 패키지의 `Secrets.kt`로 복사하고 Gemini 및
   Jarvis 서버 값을 입력합니다.
4. Android Studio에서 연결된 휴대폰을 선택하고 `Run`을 누릅니다.

필요한 값과 상세 설정은
[`samples/CameraAccessAndroid/README.md`](samples/CameraAccessAndroid/README.md)를
참고하세요.

## 백엔드 연결

Android 앱은 `JarvisMemoryServer`의 FastAPI 엔드포인트를 호출합니다.

```text
Android Client -> Gemini Live function calling -> Jarvis Memory API
                                                   -> PostgreSQL + pgvector
```

백엔드 실행 방법은
[`JarvisMemoryServer/jarvis-server/README.md`](https://github.com/blockchainMania/JarvisMemoryServer/blob/main/jarvis-server/README.md)를
참고하세요.

## 비밀값 주의

- `Secrets.kt`, `Secrets.swift`, `local.properties`, `.env`는 커밋하지 않습니다.
- GitHub 토큰과 Gemini/OpenAI 키는 README, 소스, 로그, APK에 직접 넣지 않습니다.
- Firebase `google-services.json`은 공개 저장소에 넣지 않고 각 개발자가 Firebase
  콘솔에서 내려받아 로컬에 배치하는 것을 권장합니다.
- 릴리스 배포에는 저장소에 포함된 debug keystore를 사용하지 말고 별도 release
  keystore와 서명 비밀을 CI 또는 안전한 로컬 환경에서 관리해야 합니다.

## 라이선스

원본 샘플 및 SDK 관련 라이선스는 [`NOTICE`](NOTICE)와 [`LICENSE`](LICENSE)를
확인하세요.
