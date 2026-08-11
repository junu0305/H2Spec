## 개요

<!-- 이 PR이 무엇을 하는지 1~3줄로 요약해주세요 -->

## 관련 이슈

Closes #

## 변경 유형

- [ ] 🐛 버그 수정
- [ ] ✨ 신규 기능
- [ ] ♻️ 리팩토링 (기능 변화 없음)
- [ ] 📄 문서 변경
- [ ] 🧪 테스트 추가/보강
- [ ] 🔧 빌드/CI 설정

## 대상 모듈

- [ ] parser
- [ ] generator
- [ ] interceptor-core
- [ ] web / CLI
- [ ] docs

## IR 스키마(schema-example.json 계약) 변경 여부

- [ ] 변경 없음
- [ ] 변경 있음 (Breaking Change) → `[BREAKING-IR]` 라벨 추가 및 parser/generator 담당자 모두 리뷰어로 지정 필요

변경이 있다면 Before/After를 간단히 남겨주세요.

```diff
- (기존 필드)
+ (변경된 필드)
```

## 테스트

- [ ] 로컬에서 `./gradlew test` (또는 `mvn test`) 통과 확인
- [ ] `PublicDataErrorInterceptor` 관련 변경 시: 정상 응답 / resultCode 실패 / 200 OK 위장 에러 3가지 케이스 테스트 추가 확인
- [ ] 실제 공공데이터 API 응답 샘플로 수동 검증 (해당 시 샘플 첨부, 서비스키는 마스킹)

## 체크리스트

- [ ] 셀프 리뷰 완료
- [ ] 새 코드에 대한 주석/문서 추가
- [ ] 기존 테스트가 깨지지 않음
- [ ] 커밋 메시지가 컨벤션을 따름 (`feat:`, `fix:`, `docs:`, `refactor:` 등)

## 스크린샷 / 실행 결과 (해당 시)

<!-- 생성된 OpenAPI JSON, 코드 diff, CLI 실행 로그 등 -->

## 리뷰어에게

<!-- 특히 봐줬으면 하는 부분, 고민되었던 트레이드오프 등 -->
