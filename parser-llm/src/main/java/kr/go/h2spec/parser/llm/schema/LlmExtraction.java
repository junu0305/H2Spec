package kr.go.h2spec.parser.llm.schema;

import java.util.List;

/**
 * LLM이 돌려주는 추출 결과 (중간 스키마). 리소스의 {@code extraction-schema-v3.json}을 그대로 옮긴 record다.
 * <p>
 * IR과 비슷하지만 근거·불확실성을 담고, 코드가 만들 값(apiId, 패키지, metadata 등)은 뺐다.
 * 회사마다 null을 다르게 다루므로 스키마는 모든 필드를 required로 두고, 값이 없으면 빈 문자열·빈 배열·{@code UNKNOWN}을 받는다.
 * 스키마는 세 회사가 모두 받는 범위만 쓰려고 직접 쓰고 이 record에서 만들지 않는다. 둘이 어긋나지 않는지는 테스트가 확인한다.
 *
 * @param serviceName 서비스 이름
 * @param provider 제공 기관
 * @param errorCodes 문서 전체에 공통인 에러코드 표의 오류 행. 오퍼레이션마다 같은 표를 되풀이해 적지 않게 문서에 한 번만 적는다
 * @param operations 문서에 나온 순서의 오퍼레이션
 * @param documentNotes 문서 전체에 대한 확신 없는 점
 */
public record LlmExtraction(
        String serviceName,
        String provider,
        List<LlmErrorCode> errorCodes,
        List<LlmOperation> operations,
        List<String> documentNotes) {
}
