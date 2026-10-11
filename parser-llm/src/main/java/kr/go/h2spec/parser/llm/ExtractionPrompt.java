package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.client.LlmRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 리소스의 추출 프롬프트(시스템 프롬프트)와 중간 스키마(JSON Schema)를 읽고, 작업 지시를 더해 보낼 요청을 만든다 (설계 4.3).
 * <p>
 * 버전 문자열은 IR {@code metadata.llm}에 표기용으로만 쓴다. 결과 캐시 키(설계 5.4)에는 버전 대신 파일 내용의 SHA-256을 쓴다.
 * 내용을 고치고 버전 올리기를 잊어도 이전 결과가 조용히 재사용되지 않게 하기 위해서다.
 * 해시는 리소스 파일의 바이트로 계산하므로, 보내는 글을 UTF-8로 바꾼 바이트의 해시와 같다.
 */
public final class ExtractionPrompt {

    private static final String PROMPT_VERSION = "extraction-v2";
    private static final String SCHEMA_VERSION = "2";
    private static final String PROMPT_RESOURCE = "prompt/" + PROMPT_VERSION + ".md";
    private static final String SCHEMA_RESOURCE = "extraction-schema-v" + SCHEMA_VERSION + ".json";
    /**
     * 문서 전체를 한 번에 뽑으라는 작업 지시 (설계 4.4의 기본 호출). 시스템 프롬프트가 "작업 지시가 정한 범위"의 오퍼레이션을 적으라고 하므로
     * 범위를 여기서 정한다. 프롬프트의 일부라 고치면 프롬프트 버전을 올린다. 결과 캐시 키에는 버전이 아니라 이 글 자체가 들어간다.
     * 나눠 부르기(2-2)의 오퍼레이션별 호출은 문서까지의 앞부분을 그대로 두고 이 지시만 바꾼다.
     */
    private static final String WHOLE_DOCUMENT_INSTRUCTION =
            "위 <document>의 문서 전체에서 오퍼레이션을 모두 찾아, 문서에 나온 순서대로 operations에 적어라.";

    private final String promptText;
    private final String schemaText;
    private final String promptSha256;
    private final String schemaSha256;

    private ExtractionPrompt(byte[] prompt, byte[] schema) {
        this.promptText = new String(prompt, StandardCharsets.UTF_8);
        this.schemaText = new String(schema, StandardCharsets.UTF_8);
        this.promptSha256 = sha256(prompt);
        this.schemaSha256 = sha256(schema);
    }

    /**
     * 프롬프트와 스키마 파일을 읽는다.
     *
     * @return 읽은 프롬프트와 스키마
     * @throws IllegalStateException 리소스가 없거나 읽을 수 없을 때. 배포본이 잘못 만들어진 경우다
     */
    public static ExtractionPrompt load() {
        return new ExtractionPrompt(read(PROMPT_RESOURCE), read(SCHEMA_RESOURCE));
    }

    /** @return 시스템 프롬프트. 회사와 상관없이 같은 글을 보낸다 */
    public String promptText() {
        return promptText;
    }

    /** @return 중간 스키마 JSON. 세 회사에 같은 파일을 그대로 넘긴다 */
    public String schemaText() {
        return schemaText;
    }

    /** @return IR {@code metadata.llm.promptVersion}에 적는 값 ({@code extraction-v2}) */
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    /** @return IR {@code metadata.llm.schemaVersion}에 적는 값 ({@code 2}) */
    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    /** @return 프롬프트 파일 내용의 SHA-256 (소문자 16진수 64자). 결과 캐시 키에 쓴다 */
    public String promptSha256() {
        return promptSha256;
    }

    /** @return 스키마 파일 내용의 SHA-256 (소문자 16진수 64자). 결과 캐시 키에 쓴다 */
    public String schemaSha256() {
        return schemaSha256;
    }

    /** @return 문서 전체를 한 번에 뽑으라는 작업 지시 */
    public String wholeDocumentInstruction() {
        return WHOLE_DOCUMENT_INSTRUCTION;
    }

    /**
     * 문서 전체를 한 번에 뽑는 요청을 만든다. 평가 도구가 배치 요청을 만들 때도 이 요청을 써야 llm 엔진과 같은 것을 평가한다.
     *
     * @param document        직렬화한 문서({@link DocumentSerializer}). {@code <document>} 태그는 어댑터가 감싼다
     * @param maxOutputTokens 출력 토큰 상한. thinking 토큰도 이 안에서 쓰인다
     * @return 시스템 프롬프트, 문서, 작업 지시, 스키마를 담은 요청
     */
    public LlmRequest wholeDocumentRequest(String document, int maxOutputTokens) {
        return new LlmRequest(promptText, document, WHOLE_DOCUMENT_INSTRUCTION, schemaText, maxOutputTokens);
    }

    private static byte[] read(String resource) {
        try (InputStream input = ExtractionPrompt.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("추출 프롬프트 리소스를 찾을 수 없습니다: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("추출 프롬프트 리소스를 읽을 수 없습니다: " + resource, e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // 모든 Java 플랫폼이 SHA-256을 갖추도록 MessageDigest 명세가 정해 두어 일어나지 않는다
            throw new IllegalStateException(e);
        }
    }
}
