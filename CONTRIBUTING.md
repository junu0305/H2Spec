# 기여 가이드

H2Spec에 관심을 가져 주셔서 감사합니다. 버그 제보, 문서 수정, 새 기능 제안과 구현 모두 환영합니다.

## 시작하기

1. 저장소를 포크하고 클론합니다.
2. JDK 17 이상을 준비합니다. 컴파일에는 Gradle toolchain으로 Java 17을 사용합니다.
3. 빌드와 테스트가 통과하는지 확인합니다.

```bash
./gradlew build
```

모듈 구성과 사용법은 [README](README.md)를 참고하세요.

## 기여 절차

1. 이슈를 먼저 등록해 주세요 ([이슈 템플릿](.github/ISSUE_TEMPLATE/issue-template.md) 사용).
   처음 오신 분은 `good first issue` 라벨이 붙은 이슈부터 시작하는 것을 권합니다.
2. `main`에서 작업 브랜치를 만듭니다. 브랜치와 커밋 메시지는 아래 규칙을 따릅니다.
3. 변경 사항에 맞는 테스트를 추가하거나 수정하고 `./gradlew test`가 통과하는지 확인합니다.
4. PR을 엽니다. [PR 템플릿](.github/pull_request_template.md)이 자동으로 적용되니 항목을 채워 주세요.
5. 리뷰를 거쳐 `main`에 병합됩니다. `main`에는 PR을 통해서만 병합합니다.

## 브랜치 규칙

브랜치 prefix는 PascalCase로 통일합니다.

| 작업 종류 | 브랜치 이름 예시 |
|---|---|
| 새 기능 | `Feat/generator-dto`, `Feat/parser-docx` |
| 버그 수정 | `Fix/xml-parse-error` |
| 긴급 수정 | `Hotfix/build-error` |
| 리팩토링 | `Refactor/interceptor-core` |
| 문서 | `Docs/readme-update` |
| 빌드/설정 | `Chore/gradle-setup` |

## 커밋 메시지

`타입: 내용` 형식의 소문자 prefix를 사용합니다 (Conventional Commits 스타일).

| 타입 | 용도 |
|---|---|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `hotfix` | 긴급 수정 |
| `docs` | 문서 수정 |
| `style` | 코드 포맷팅 (기능 변경 없음) |
| `refactor` | 코드 리팩토링 |
| `test` | 테스트 코드 추가 |
| `chore` | 빌드/설정 변경 |

## IR 스키마를 바꾸는 경우

IR(JSON)은 `parser`와 `generator`가 공유하는 계약입니다. 구조는 [`docs/schema-example.json`](docs/schema-example.json)에 정의되어 있습니다.

- IR 구조를 바꾸는 PR에는 `[BREAKING-IR]` 라벨을 붙입니다.
- `parser`와 `generator` 담당자 모두에게 리뷰를 받아야 합니다.
- PR 본문에 Before/After를 남겨 주세요.

## 테스트 기준

- `PublicDataErrorInterceptor`를 바꾸는 PR은 정상 응답, `resultCode` 실패, HTTP 200으로 위장한 오류 3가지 케이스의 테스트를 포함해야 합니다.
- 실제 공공데이터 API 응답 샘플로 검증했다면 샘플을 첨부하되 서비스키는 반드시 마스킹합니다.
- 새 에러코드를 추가할 때는 [`docs/error-codes.md`](docs/error-codes.md)도 함께 갱신합니다.
- 커버리지 리포트는 `./gradlew test` 실행 후 각 모듈의 `build/reports/jacoco/test/html/index.html`에서 볼 수 있습니다.

## 보안

서비스키나 인증 정보를 이슈, PR, 로그, 테스트 샘플에 올리지 마세요. 취약점은 공개 이슈 대신 저장소 관리자에게 비공개로 알려 주세요.

## 라이선스

기여한 코드는 프로젝트와 같은 [MIT License](LICENSE)로 배포됩니다.

## 향후 계획

[ROADMAP.md](ROADMAP.md)에서 예정된 작업과 알려진 한계를 확인할 수 있습니다.
