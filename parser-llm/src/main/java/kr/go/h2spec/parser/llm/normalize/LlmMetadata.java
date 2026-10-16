package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.ExtractionPrompt;

/**
 * IR {@code metadata.llm}에 적는 실행 설정. 결과에 영향을 주는 설정이라 같은 IR을 다시 만드는 데 필요하다.
 * 토큰 수와 캐시 사용 여부는 담지 않는다. 결과가 같아도 실행마다 바뀌고 검수에 쓸모가 없어 CLI 출력에만 남긴다.
 *
 * @param provider      회사 ID ({@code claude}, {@code openai}, {@code gemini})
 * @param model         모델 ID
 * @param effort        회사의 추론 깊이 설정값 그대로 (Claude {@code effort}, OpenAI {@code reasoning.effort}, Gemini {@code thinkingLevel})
 * @param promptVersion 프롬프트 버전 ({@code extraction-v4})
 * @param schemaVersion 중간 스키마 버전 ({@code 3})
 */
public record LlmMetadata(String provider, String model, String effort, String promptVersion, String schemaVersion) {

    /**
     * 프롬프트와 스키마의 버전은 리소스에서 읽은 값으로 채운다.
     *
     * @param provider 회사
     * @param model    모델 ID
     * @param effort   추론 깊이 설정값
     * @param prompt   보낸 프롬프트와 스키마
     * @return 실행 설정
     */
    public static LlmMetadata of(String provider, String model, String effort, ExtractionPrompt prompt) {
        return new LlmMetadata(provider, model, effort, prompt.promptVersion(), prompt.schemaVersion());
    }

    /** @return IR {@code metadata.llm} 노드. 모르는 값은 빈 문자열로 적는다 */
    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("provider", orEmpty(provider));
        node.put("model", orEmpty(model));
        node.put("effort", orEmpty(effort));
        node.put("promptVersion", orEmpty(promptVersion));
        node.put("schemaVersion", orEmpty(schemaVersion));
        return node;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
