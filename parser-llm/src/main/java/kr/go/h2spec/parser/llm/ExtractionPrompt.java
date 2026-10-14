package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmUrlCandidates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 리소스의 추출 프롬프트(시스템 프롬프트)와 중간 스키마(JSON Schema)를 읽고, 작업 지시를 더해 보낼 요청을 만든다.
 * <p>
 * 버전 문자열은 IR {@code metadata.llm}에 표기용으로만 쓴다. 결과 캐시 키에는 버전 대신 프롬프트·스키마 글 자체가 들어간다
 * ({@link ExtractionCache#key}). 내용을 고치고 버전 올리기를 잊어도 이전 결과가 조용히 재사용되지 않게 하기 위해서다.
 * {@link #promptSha256()}·{@link #schemaSha256()}는 리소스 파일의 바이트로 계산하므로, 보내는 글을 UTF-8로 바꾼 바이트의 해시와 같다.
 */
public final class ExtractionPrompt {

    private static final String PROMPT_VERSION = "extraction-v4";
    private static final String SCHEMA_VERSION = "3";
    private static final String PROMPT_RESOURCE = "prompt/" + PROMPT_VERSION + ".md";
    private static final String SCHEMA_RESOURCE = "extraction-schema-v" + SCHEMA_VERSION + ".json";
    /**
     * 문서 전체를 한 번에 뽑으라는 작업 지시 (기본 호출). 시스템 프롬프트가 "작업 지시가 정한 범위"의 오퍼레이션을 적으라고 하므로
     * 범위를 여기서 정한다. 프롬프트의 일부라 고치면 프롬프트 버전을 올린다. 결과 캐시 키에는 버전이 아니라 이 글 자체가 들어간다.
     */
    private static final String WHOLE_DOCUMENT_INSTRUCTION =
            "위 <document>의 문서 전체에서 오퍼레이션을 모두 찾아, 문서에 나온 순서대로 operations에 적어라.";
    /**
     * 나눠 부르기의 첫 호출 지시. 문서까지의 앞부분은 그대로 두고 범위만 줄인다. 자리에 한 번에 적을 오퍼레이션 수가 들어간다.
     * 프롬프트의 일부라 고치면 프롬프트 버전을 올린다.
     */
    private static final String FIRST_PART_INSTRUCTION =
            "위 <document>의 문서 전체에서 오퍼레이션을 찾아 문서에 나온 순서대로 operations에 적되, 처음 %d개까지만 적어라. "
                    + "나머지는 다음 호출에서 적는다.";
    /**
     * 나눠 부르기의 다음 호출 지시. 이미 적은 오퍼레이션을 알려 빼게 한다. 빠진 오퍼레이션이 앞에 있어도 다시 찾도록 "그 뒤"가 아니라
     * "남은 것 가운데 먼저 나오는 것"을 적게 한다. 문서 공통 값은 첫 호출의 것을 쓰므로 비워 출력을 줄인다.
     * 자리에 한 번에 적을 오퍼레이션 수와 이미 적은 오퍼레이션 목록이 들어간다. 프롬프트의 일부라 고치면 프롬프트 버전을 올린다.
     */
    private static final String NEXT_PART_INSTRUCTION = """
            위 <document>의 문서 전체에서 오퍼레이션을 문서에 나온 순서대로 찾아라. 아래 <done_operations>의 오퍼레이션은 앞선 호출에서 \
            이미 적었으니 빼고, 남은 오퍼레이션 가운데 문서에 먼저 나오는 것부터 최대 %d개까지 operations에 적어라. \
            남은 오퍼레이션이 없으면 operations를 빈 배열로 둔다. serviceName, provider, errorCodes, documentNotes는 \
            앞선 호출에서 적었으니 빈 문자열과 빈 배열로 둔다.
            <done_operations>
            %s
            </done_operations>""";

    /**
     * 원문 대조에서 버린 것을 알려 다시 부를 때 작업 지시 뒤에 붙이는 글. 프롬프트의 일부라 고치면 프롬프트 버전을 올린다.
     * 사이에 {@code ValidationFeedback}이 적은 줄이 들어간다.
     */
    private static final String RETRY_HEAD = """


            앞서 같은 지시로 뽑은 결과를 원문과 대조했더니 아래 항목을 버려야 했다. 버린 이유를 보고 원문에 적힌 그대로 고쳐서, \
            위 작업 지시가 정한 범위의 오퍼레이션을 문제가 없던 것까지 처음부터 다시 적어라.
            <validation_problems>
            """;
    private static final String RETRY_TAIL = """

            </validation_problems>
            응답 경로의 칸 이름은 응답 예제(예제가 없으면 응답 표)에 실제로 나오는 키·태그만 쓴다. 예제에 없는 감싸는 칸을 보태지 않는다. \
            원문에서 근거를 찾지 못한 항목은 지어내지 말고 뺀다.""";

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

    /** @return 중간 스키마 JSON. 회사와 상관없이 같은 파일을 그대로 넘긴다 */
    public String schemaText() {
        return schemaText;
    }

    /** @return IR {@code metadata.llm.promptVersion}에 적는 값 ({@code extraction-v4}) */
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    /** @return IR {@code metadata.llm.schemaVersion}에 적는 값 ({@code 3}) */
    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    /** @return 프롬프트 파일 내용의 SHA-256 (소문자 16진수 64자) */
    public String promptSha256() {
        return promptSha256;
    }

    /** @return 스키마 파일 내용의 SHA-256 (소문자 16진수 64자) */
    public String schemaSha256() {
        return schemaSha256;
    }

    /**
     * 문서 전체를 한 번에 뽑는 요청을 만든다. 평가 도구에 배치 호출을 더할 때도 이 요청을 써야 llm 엔진과 같은 것을 평가한다.
     *
     * @param document        직렬화한 문서({@link DocumentSerializer}). {@code <document>} 태그는 어댑터가 감싼다
     * @param maxOutputTokens 출력 토큰 상한. thinking 토큰도 이 안에서 쓰인다
     * @return 시스템 프롬프트, 문서, 작업 지시, 스키마를 담은 요청
     */
    public LlmRequest wholeDocumentRequest(String document, int maxOutputTokens) {
        return new LlmRequest(promptText, document, WHOLE_DOCUMENT_INSTRUCTION, schemaText, maxOutputTokens);
    }

    /**
     * 나눠 부르기의 요청 하나를 만든다. 같은 문서를 여러 번 부르므로 문서도 회사의 프롬프트 캐시에 올린다.
     *
     * @param document          직렬화한 문서. 문서 전체를 한 번에 뽑는 요청과 같은 글이다
     * @param done              앞선 호출에서 이미 적은 오퍼레이션. 비었으면 첫 호출이다
     * @param operationsPerCall 한 번에 적을 오퍼레이션 수
     * @param maxOutputTokens   출력 토큰 상한
     * @return 범위만 다른 작업 지시를 담은 요청
     */
    public LlmRequest partRequest(String document, List<LlmOperation> done, int operationsPerCall, int maxOutputTokens) {
        if (operationsPerCall < 1) {
            throw new IllegalArgumentException("한 번에 적을 오퍼레이션 수는 1 이상이어야 합니다: " + operationsPerCall);
        }
        String instruction = done.isEmpty()
                ? FIRST_PART_INSTRUCTION.formatted(operationsPerCall)
                : NEXT_PART_INSTRUCTION.formatted(operationsPerCall,
                        String.join("\n", done.stream().map(ExtractionPrompt::doneLabel).toList()));
        return new LlmRequest(promptText, document, instruction, schemaText, maxOutputTokens, true);
    }

    /** 이미 적은 오퍼레이션을 가리키는 한 줄. 영문 오퍼레이션명, 국문 기능명, URL 후보로 문서 속 자리를 알려 준다 */
    private static String doneLabel(LlmOperation operation) {
        LlmUrlCandidates urls = operation.urlCandidates();
        String url = urls == null ? "" : firstText(urls.callBackUrl(), urls.tableUrl(), urls.urlLine(), urls.requestExampleUrl());
        String name = oneLine(operation.operationName());
        return "- " + (name.isEmpty() ? "(영문 오퍼레이션명 없음)" : name) + " | " + oneLine(operation.title()) + " | " + url;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (!oneLine(value).isEmpty()) {
                return oneLine(value);
            }
        }
        return "";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /**
     * 원문 대조에서 버린 것을 알려 다시 부르는 요청을 만든다. 시스템 프롬프트와 문서는 처음 요청 그대로이고 작업 지시만 늘어난다.
     *
     * @param first    처음 요청
     * @param problems {@code ValidationFeedback}이 적은 줄. 비어 있으면 안 된다
     * @return 작업 지시 뒤에 검증 결과를 붙인 요청
     */
    public LlmRequest retryRequest(LlmRequest first, List<String> problems) {
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("알릴 검증 결과가 없습니다");
        }
        return new LlmRequest(first.systemPrompt(), first.document(),
                first.instruction() + RETRY_HEAD + String.join("\n", problems) + RETRY_TAIL,
                first.jsonSchema(), first.maxOutputTokens(), first.cacheDocument());
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
