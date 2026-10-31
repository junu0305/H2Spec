# 로드맵

H2Spec의 현재 상태와 앞으로의 계획입니다. 일정은 확정이 아니며 이슈와 PR 논의에 따라 바뀔 수 있습니다.
의견이나 참여는 [CONTRIBUTING.md](CONTRIBUTING.md)를 참고해 이슈로 남겨 주세요.

## 지금 (v0.2.x)

- HWP, HWPX, DOCX 명세서를 IR JSON으로 변환
- 규칙 기반 파서와 LLM 파서(Claude, OpenAI, Gemini) 지원
- IR에서 OpenAPI 문서, Spring `RestTemplate` 클라이언트, DTO 생성
- `RestTemplate`, `WebClient`용 오류 응답 감지 (HTTP 200으로 위장한 오류 포함)
- 에러코드 사전 ([`docs/error-codes.md`](docs/error-codes.md))
- JitPack 배포와 릴리스 파일 제공

## 다음

- `--engine auto`: 규칙 기반 파서를 먼저 쓰고 필요할 때 LLM 파서로 보완
- `web` 모듈: 파일을 올려 변환하는 웹 UI (현재 **예정**, 아직 구현되지 않음)
- 에러코드 사전 확장

## 나중에

- GET 이외의 HTTP 메서드(POST 등) 지원
- `WebClient` 기반 클라이언트 생성
- 기관별 명세서 양식과 회귀 테스트 코퍼스 확대

## 알려진 한계

- 생성기 v1은 GET 요청의 path/query 파라미터와 XML/JSON 응답에 집중되어 있습니다.
  처리할 수 없는 오퍼레이션은 건너뛰고, 건너뛴 이유를 CLI가 표준 오류로 출력합니다.
- 파서가 문서에서 추론해 채운 값(예: 요청 예시에서 가져온 URL, 응답 예시에서 찾은 필드 경로)은
  IR의 `metadata.reviewNotes`에 기록됩니다. 명세서 양식이 표준과 크게 다르면 이 항목을 확인하세요.

## 참여하기

`good first issue` 라벨이 붙은 이슈에서 시작하세요. 새 양식 샘플, 에러코드, 문서 개선 모두 좋은 첫 기여가 됩니다.
