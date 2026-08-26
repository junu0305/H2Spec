# 에러코드 사전

공공데이터 API는 실패해도 HTTP 200을 돌려주는 일이 잦고, 응답 바디의 `resultCode`만 봐서는
개발자가 무엇을 고쳐야 하는지 알 수 없다. 기술문서는 코드의 뜻까지만 적는다.

이 사전은 코드마다 **뜻**과 **조치**를 함께 담아, `PublicDataApiException` 메시지에 붙인다.

```
[H2Spec] 공공데이터 API 응답 오류 감지 (HTTP 403 이지만 실제로는 실패) - uri=...&serviceKey=****,
resultCode=30, resultMsg=SERVICE_KEY_IS_NOT_REGISTERED_ERROR | 등록되지 않은 서비스키
| 조치: 인증키를 확인한다. Encoding/Decoding 키를 바꿔 넣었거나, 신청 직후라 아직 반영되지 않은 경우가 많다.
```

응답의 `resultMsg`가 이미 뜻을 담고 있으면 같은 말을 두 번 적지 않는다.

## 파일

`interceptor-core/src/main/resources/kr/go/h2spec/client/interceptor/error-codes.json`

```json
{
  "code": "30",
  "name": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
  "meaning": "등록되지 않은 서비스키",
  "action": "인증키를 확인한다. Encoding/Decoding 키를 바꿔 넣었거나, 신청 직후라 아직 반영되지 않은 경우가 많다.",
  "source": "한국환경공단_에어코리아_대기오염정보_기술문서_v1.4.docx"
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `code` | O | 결과코드. 앞자리 0은 정규화되므로 `1`과 `01`은 같은 항목이다 |
| `name` | O | 문서에 적힌 영문 에러명 |
| `meaning` | O | 문서에 적힌 뜻. 문서 표기를 그대로 옮긴다 |
| `action` | O | 개발자가 취할 조치. 이 사전이 문서보다 나은 유일한 지점이다 |
| `source` | O | 근거 문서 파일명 또는 URL |
| `nameAliases` | X | 같은 에러를 다르게 적는 기관이 있을 때의 대체 표기 |

## 추가하는 법

1. 근거를 먼저 확보한다. 기관 기술문서의 에러코드 표, 또는 실제 호출로 받은 응답 원문.
2. `error-codes.json`에 항목을 추가한다. `source`에 근거를 적는다.
3. `./gradlew :interceptor-core:test` 를 돌린다. 필수 필드 누락, 코드 중복은 테스트가 잡는다.
4. PR을 연다. 실제 호출로 확인했다면 응답 원문(인증키는 가린 채)을 본문에 넣는다.

사전에 항목을 추가하면 그 에러명이 키워드 탐지에도 함께 쓰인다. 결과코드 없이 에러명만
돌려주는 응답도 그때부터 잡힌다.

## 판정 순서

1. 응답의 `resultCode`로 찾는다.
2. 코드가 없으면 `resultMsg`에 담긴 에러명으로 찾는다. 결과코드를 비우고 메시지에만 에러명을 넣는 기관이 있다.
3. 둘 다 없으면 예외 메시지는 코드와 원문만 담는다. 사전이 추측을 지어내지 않는다.

## 등재 기준

- 기관 문서나 실제 응답으로 확인한 코드만 넣는다. 추측한 코드는 넣지 않는다.
- `meaning`은 문서 표기를 옮기고, 해석은 `action`에 적는다.
- 특정 기관에서만 쓰는 코드는 `source`에 그 기관 문서를 명시한다. 표준 코드와 충돌하면 PR에서 논의한다.
