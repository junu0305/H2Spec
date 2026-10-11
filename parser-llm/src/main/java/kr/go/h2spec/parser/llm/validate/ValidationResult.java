package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.llm.schema.LlmExtraction;

import java.util.List;

/**
 * 문서 하나의 검증 결과.
 *
 * @param extraction LLM이 돌려준 추출 결과 그대로. 정규화가 제공 기관과 문서 전체 노트를 읽는다
 * @param operations LLM 결과의 오퍼레이션마다 하나 (문서 순서). 건너뛴 오퍼레이션도 이유와 함께 담는다
 */
public record ValidationResult(LlmExtraction extraction, List<ValidatedOperation> operations) {

    public ValidationResult {
        // 목록은 바꿀 수 없게 복사해 둔다
        operations = List.copyOf(operations);
    }
}
