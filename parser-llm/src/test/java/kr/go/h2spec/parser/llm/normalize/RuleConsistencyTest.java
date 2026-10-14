package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.llm.TestDocuments;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 엔진이 같이 쓰는 IR 규칙(IrConventions)이 정말 같은 IR을 내는지 지킨다. 에어코리아 측정소정보 문서(msrstn-info.docx)를
 * 충실히 옮긴 LLM 출력(픽스처)을 검증·정규화한 IR이, 같은 Block 목록으로 돌린 rule 엔진의 IR과 metadata를 빼고 같아야 한다.
 * 주소 고르기·나누기, apiId, generatorHints, 결과코드 필드의 타입과 표시, 응답 포맷, errorSpec 기본값이 여기서 맞물린다.
 * <p>
 * rule 엔진이 채우지 않는 값 두 가지만 비교에서 뺀다. {@code api.provider}는 rule 엔진이 늘 빈 값이고,
 * {@code errorSpec.knownErrorCodes}는 llm 엔진만 문서의 에러코드 표로 채운다. 둘 다 이미 IR 계약에 있는 필드라
 * llm 엔진이 채우기 시작할 뿐이다. 빼는 대신 llm 쪽 값이 문서와 맞는지 따로 본다.
 */
class RuleConsistencyTest {

    private static final String DOCUMENT = "msrstn-info.docx";

    @TempDir
    Path tempDir;

    @Test
    void 측정소정보_문서를_충실히_옮긴_LLM_출력의_IR은_metadata와_검수_노트_말고는_rule_엔진의_IR과_같다() throws Exception {
        List<Block> blocks = TestDocuments.read(DOCUMENT);
        List<NormalizedOperation> llm = normalize(blocks);
        RuleEngine.Result rule = new RuleEngine().assemble(blocks, DOCUMENT, "DOCX");

        assertEquals(3, llm.size());
        assertEquals(3, rule.apis().size());
        for (int i = 0; i < llm.size(); i++) {
            NormalizedOperation operation = llm.get(i);
            assertTrue(operation.converted(), operation.skipReason());

            ObjectNode llmIr = operation.ir().deepCopy();
            ObjectNode ruleIr = rule.apis().get(i).ir().deepCopy();
            ObjectNode llmApi = (ObjectNode) llmIr.get("api");
            assertEquals("한국환경공단", llmApi.get("provider").asText());
            assertEquals("", ruleIr.get("api").get("provider").asText());
            assertEquals(14, llmApi.get("errorSpec").get("knownErrorCodes").size());
            assertFalse(ruleIr.get("api").get("errorSpec").has("knownErrorCodes"));
            llmApi.put("provider", "");
            ((ObjectNode) llmApi.get("errorSpec")).remove("knownErrorCodes");
            llmIr.remove("metadata");
            ruleIr.remove("metadata");

            assertEquals(ruleIr.toPrettyString(), llmIr.toPrettyString(), "오퍼레이션 " + (i + 1) + "의 키 순서까지");
            assertEquals(ruleIr, llmIr, "오퍼레이션 " + (i + 1));
        }
    }

    private List<NormalizedOperation> normalize(List<Block> blocks) {
        LlmMetadata llm = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v4", "3");
        return new IrNormalizer(llm).normalize(
                new ExtractionValidator().validate(TestDocuments.fixture("msrstn-info-faithful.json"), blocks), DOCUMENT, "DOCX");
    }
}
