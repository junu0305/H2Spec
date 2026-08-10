---
name: "🐛 버그 리포트 / ✨ 기능 제안"
about: H2Spec 버그 신고 또는 새 기능 제안 시 사용해주세요.
title: "[BUG] " # 또는 [FEATURE], [IR-SCHEMA]
labels: ""
assignees: ""
---

## 이슈 종류

- [ ] 🐛 버그 리포트
- [ ] ✨ 기능 제안
- [ ] 📄 문서 개선
- [ ] 🔗 IR 스키마 변경 (parser ↔ generator 계약 변경 — 이 경우 아래 "IR 스키마 영향" 섹션 필수 작성)

## 대상 모듈

- [ ] parser (HWP/DOCX → IR JSON)
- [ ] generator (IR JSON → OpenAPI/코드)
- [ ] interceptor-core (`PublicDataErrorInterceptor` 등)
- [ ] web / CLI
- [ ] 기타: `<직접 입력>`

## 현재 동작 / 문제 상황

<!-- 무엇이 잘못되었는지, 혹은 어떤 게 없어서 불편한지 구체적으로 작성해주세요 -->

## 기대하는 동작

<!-- 어떻게 동작해야 하는지 -->

## 재현 방법 (버그인 경우)

1.
2.
3.

## 사용한 입력 파일 / 명세서 정보 (해당 시)

- 기관명:
- API 종류(OpenAPI 방식/공공데이터포털 등):
- 첨부 파일: <!-- 민감정보(서비스키 등)는 반드시 마스킹 후 첨부해주세요 -->

## IR 스키마 영향 (해당 시에만 작성)

- 변경되는 필드:
- Breaking Change 여부: [ ] 예 / [ ] 아니오
- 영향받는 모듈: parser / generator (둘 다 담당자 리뷰 필요)

## 환경

- OS:
- Java 버전:
- Spring Boot 버전:
- H2Spec 버전/커밋:

## 스크린샷 / 로그

<!-- 가능하면 스택트레이스, 생성된 JSON/코드 등을 코드블록으로 첨부해주세요 -->
