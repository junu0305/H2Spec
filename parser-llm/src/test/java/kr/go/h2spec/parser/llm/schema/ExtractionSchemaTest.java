package kr.go.h2spec.parser.llm.schema;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.TestDocuments;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 세 회사에 그대로 넘기는 스키마 파일(extraction-schema-v2.json)이 record와 같고, 세 회사가 모두 받는 범위(설계 4.2)만 쓰는지 본다.
 * 스키마는 record에서 만들지 않고 직접 쓰므로, 둘이 어긋나면 LLM 응답을 record로 읽지 못한다.
 */
class ExtractionSchemaTest {

    /** 세 회사가 함께 받지 않거나 설계 4.2가 쓰지 않기로 한 키워드. 제약은 코드가 검증(5.1)에서 본다 */
    private static final Set<String> FORBIDDEN = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "pattern", "format", "maxItems", "$ref", "$defs", "definitions",
            "anyOf", "oneOf", "allOf", "not");
    /** 스키마 노드에 쓸 수 있는 키워드. 그 밖의 키워드는 회사마다 받는지 확인하지 않았다 */
    private static final Set<String> ALLOWED = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minItems");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "boolean");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 스키마와_record의_속성_이름과_enum_값이_같다() throws Exception {
        assertMatches(schema(), LlmExtraction.class, "$");
    }

    @Test
    void 모든_object가_속성을_모두_required로_두고_다른_속성을_막는다() throws Exception {
        List<String> objects = new ArrayList<>();
        forEachSchema(schema(), "$", (path, node) -> {
            if (!"object".equals(node.path("type").asText())) {
                return;
            }
            objects.add(path);
            assertEquals(names(node.path("properties")), texts(node.path("required")), path + " required");
            JsonNode additional = node.path("additionalProperties");
            assertTrue(additional.isBoolean() && !additional.booleanValue(), path + " additionalProperties: " + additional);
        });

        // LlmExtraction, LlmOperation, LlmUrlCandidates, LlmAuth, LlmRequestParameter, LlmResponseField,
        // LlmResultCode, LlmSuccessCode, LlmErrorCode
        assertEquals(9, objects.size(), objects.toString());
    }

    @Test
    void 세_회사가_함께_받는_타입과_키워드만_쓴다() throws Exception {
        forEachSchema(schema(), "$", (path, node) -> {
            node.fieldNames().forEachRemaining(keyword -> {
                assertFalse(FORBIDDEN.contains(keyword), path + ": 쓰지 않기로 한 키워드 " + keyword);
                assertTrue(ALLOWED.contains(keyword), path + ": 회사마다 받는지 확인하지 않은 키워드 " + keyword);
            });
            // 타입은 하나만 적는다. ["string", "null"] 같은 묶음은 null을 쓰지 않는 규칙에도 어긋난다
            assertTrue(TYPES.contains(node.path("type").asText()), path + ": 타입 " + node.path("type"));
            if (node.has("minItems")) {
                assertTrue(node.get("minItems").asInt() <= 1, path + ": Claude는 minItems 0과 1만 받는다");
            }
            if (node.has("enum")) {
                assertEquals("string", node.path("type").asText(), path + ": enum은 문자열에만 쓴다");
                node.get("enum").forEach(value -> assertTrue(value.isTextual(), path + ": enum 값 " + value));
            }
        });
    }

    @Test
    void 부록_A의_예시를_record로_읽고_그대로_다시_쓴다() throws Exception {
        // 설계 부록 A(jejudatahub-wifi-usage.hwp에서 기대하는 LLM 출력)를 그대로 옮긴 녹화
        String json = TestDocuments.recording(TestDocuments.PROVIDER, "jejudatahub-wifi-usage.json");
        // 빠진 필드나 null이 있으면 읽지 않아야 녹화가 스키마의 모든 필드를 채웠다고 말할 수 있다
        ObjectMapper strict = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

        LlmExtraction extraction = strict.readValue(json, LlmExtraction.class);

        assertEquals(objectMapper.readTree(json), objectMapper.valueToTree(extraction));
        assertEquals(extraction, strict.readValue(objectMapper.writeValueAsString(extraction), LlmExtraction.class));
        LlmOperation operation = extraction.operations().get(0);
        assertEquals(LlmAuth.Kind.PATH_KEY, operation.auth().kind());
        assertEquals(LlmOperation.ResponseStructure.EXAMPLE, operation.responseStructure());
        assertEquals(LlmRequestParameter.Requirement.REQUIRED, operation.requestParameters().get(0).required());
        assertTrue(operation.requestParameters().get(1).common());
        assertEquals("data[].baseDate", operation.responseFields().get(2).path());
        assertEquals(LlmResponseField.PathSource.RESPONSE_EXAMPLE, operation.responseFields().get(2).pathSource());
    }

    /** record의 구성 요소를 따라 스키마를 내려가며 이름·타입·enum 값을 맞춰 본다 */
    private void assertMatches(JsonNode node, Type type, String path) {
        if (type == String.class) {
            assertEquals("string", node.path("type").asText(), path);
            assertFalse(node.has("enum"), path + ": record는 String인데 스키마에 enum이 있다");
        } else if (type == boolean.class) {
            assertEquals("boolean", node.path("type").asText(), path);
        } else if (type instanceof Class<?> enumType && enumType.isEnum()) {
            assertEquals("string", node.path("type").asText(), path);
            List<String> constants = Arrays.stream(enumType.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
            assertEquals(constants, texts(node.path("enum")), path + " enum");
        } else if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            assertEquals("array", node.path("type").asText(), path);
            assertMatches(node.path("items"), list.getActualTypeArguments()[0], path + "[]");
        } else if (type instanceof Class<?> recordType && recordType.isRecord()) {
            assertEquals("object", node.path("type").asText(), path);
            RecordComponent[] components = recordType.getRecordComponents();
            assertEquals(Arrays.stream(components).map(RecordComponent::getName).toList(),
                    names(node.path("properties")), path + " 속성");
            for (RecordComponent component : components) {
                assertMatches(node.path("properties").path(component.getName()), component.getGenericType(),
                        path + "." + component.getName());
            }
        } else {
            fail(path + ": 스키마로 옮길 수 없는 타입 " + type);
        }
    }

    /** 스키마 노드를 모두 돈다. properties 아래의 키는 속성 이름이라 키워드로 보지 않는다 */
    private void forEachSchema(JsonNode node, String path, BiConsumer<String, JsonNode> visitor) {
        visitor.accept(path, node);
        node.path("properties").fields().forEachRemaining(
                property -> forEachSchema(property.getValue(), path + "." + property.getKey(), visitor));
        if (node.has("items")) {
            forEachSchema(node.get("items"), path + "[]", visitor);
        }
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree(ExtractionPrompt.load().schemaText());
    }

    private List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }
}
