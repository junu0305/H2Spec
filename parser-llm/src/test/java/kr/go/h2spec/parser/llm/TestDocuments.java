package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.DocumentReaders;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 테스트가 쓰는 실제 기관 문서와 그 문서의 녹화한 LLM 출력을 읽는다.
 * <p>
 * 문서는 parser 테스트 리소스와 docs/sample의 원본 그 자체라 복사하지 않고 저장소 루트에서 읽는다. 같은 바이너리를 모듈마다
 * 복사해 두면 한쪽만 고쳐져도 알 수 없기 때문이다. Gradle은 저장소 루트를 시스템 속성으로 넘기고(parser-llm/build.gradle),
 * IDE에서 모듈 디렉터리로 돌리면 그 부모를 쓴다. 테스트는 원본의 긴 이름 대신 녹화 파일과 같은 짧은 영문 이름으로 문서를 가리킨다.
 * <p>
 * 녹화({@code llm-recordings/<회사>/<문서>.json})는 LLM이 그 문서에서 낸 중간 스키마 JSON이다. 실제 Claude 응답
 * ({@code claude-sonnet-5-5}, effort medium, 프롬프트 extraction-v4, 스키마 v3)을 그대로 담았다. 빠진 필드나 null이 있으면 읽지 않아, 녹화가 스키마의
 * 모든 필드를 채웠다고 말할 수 있게 한다. 손으로 쓴 응답은 {@code fixtures/}에 따로 둔다({@link #fixture}).
 */
public final class TestDocuments {

    /** 녹화한 회사. 녹화는 회사와 무관한 중간 스키마 JSON이라 어느 회사의 것이든 같은 하류 코드를 시험한다 */
    public static final String PROVIDER = "claude";

    private static final String CORPUS = "parser/src/test/resources/docs/corpus/";
    private static final String PARSER_DOCS = "parser/src/test/resources/docs/";
    private static final String SAMPLES = "docs/sample/";
    /** 문서 이름 → 저장소 루트 기준 원본 위치. docs/sample의 문서는 원본 이름이 길어 기관을 알 수 있는 영문 이름을 붙였다 */
    private static final Map<String, String> DOCUMENTS = new TreeMap<>(Map.of(
            "its-traffic-light.hwp", CORPUS + "its-traffic-light.hwp",
            "jejudatahub-wifi-usage.hwp", CORPUS + "jejudatahub-wifi-usage.hwp",
            "kosha-certificate-check.hwp", CORPUS + "kosha-certificate-check.hwp",
            "nec-ballot-counting-place.hwpx", CORPUS + "nec-ballot-counting-place.hwpx",
            "msrstn-info.docx", PARSER_DOCS + "msrstn-info.docx",
            "nps-disability-review.hwp", PARSER_DOCS + "nps-disability-review.hwp",
            "kasi-special-day.docx", SAMPLES + "OpenAPI활용가이드_한국천문연구원_천문우주정보__특일_정보제공_서비스_v1.4.docx",
            "kci-reference.docx", SAMPLES + "(업로드용)_02.기술문서_KCI_참고문헌 (3).docx",
            "kma-mid-forecast.docx", SAMPLES + "기상청28_중기예보 조회서비스_오픈API활용가이드_241128.docx"));

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    private TestDocuments() {
    }

    /** @return 녹화가 있는 문서의 이름 (이름 순) */
    public static List<String> names() {
        return List.copyOf(DOCUMENTS.keySet());
    }

    /** @return 저장소 루트 */
    public static Path repoRoot() {
        String root = System.getProperty("h2spec.repoRoot");
        return root != null ? Path.of(root) : Path.of("").toAbsolutePath().getParent();
    }

    /**
     * @param name 문서 이름 ({@code msrstn-info.docx})
     * @return 원본 문서의 경로
     */
    public static Path path(String name) {
        String location = DOCUMENTS.get(name);
        if (location == null) {
            throw new IllegalArgumentException("테스트 문서 목록에 없는 이름입니다: " + name);
        }
        Path path = repoRoot().resolve(location);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("테스트 문서를 찾을 수 없습니다: " + path
                    + " (저장소 루트를 시스템 속성 h2spec.repoRoot로 주세요)");
        }
        return path;
    }

    /** 원본 문서를 확장자에 맞는 Reader로 읽는다 */
    public static List<Block> read(String name) {
        Path path = path(name);
        try {
            return DocumentReaders.find(path).orElseThrow().reader().read(path);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 문서를 읽을 수 없습니다: " + path, e);
        }
    }

    /** IR metadata에 적는 원본 포맷 */
    public static String sourceFormat(String name) {
        return DocumentReaders.find(Path.of(name)).orElseThrow().sourceFormat();
    }

    /**
     * @param document 문서 이름 ({@code msrstn-info.docx})
     * @return 그 문서의 녹화 파일 이름 ({@code msrstn-info.json})
     */
    public static String recordingName(String document) {
        return document.substring(0, document.lastIndexOf('.')) + ".json";
    }

    /**
     * 녹화를 글 그대로 읽는다. 회사가 돌려줄 응답 글이 이것이다.
     *
     * @param provider 회사 ({@code claude})
     * @param name     녹화 파일 이름 ({@code msrstn-info.json})
     * @return 녹화한 중간 스키마 JSON 글
     */
    public static String recording(String provider, String name) {
        String resource = "/llm-recordings/" + provider + "/" + name;
        try (InputStream input = TestDocuments.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("녹화가 없습니다: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("녹화를 읽을 수 없습니다: " + resource, e);
        }
    }

    /** {@link #PROVIDER}의 녹화를 중간 스키마 record로 읽는다 ({@code msrstn-info.json}) */
    public static LlmExtraction extraction(String name) {
        try {
            return STRICT.readValue(recording(PROVIDER, name), LlmExtraction.class);
        } catch (IOException e) {
            throw new IllegalStateException("녹화를 중간 스키마로 읽을 수 없습니다: " + name, e);
        }
    }

    /**
     * 손으로 쓴 중간 스키마 JSON을 읽는다. 실제 응답과 달리 원하는 성질(문서를 충실히 옮김 등)을 정해 두고 시험할 때 쓴다.
     *
     * @param name {@code fixtures/} 아래 파일 이름 ({@code msrstn-info-faithful.json})
     * @return 중간 스키마 record
     */
    public static LlmExtraction fixture(String name) {
        try {
            return STRICT.readValue(fixtureText(name), LlmExtraction.class);
        } catch (IOException e) {
            throw new IllegalStateException("픽스처를 중간 스키마로 읽을 수 없습니다: " + name, e);
        }
    }

    /**
     * @param name {@code fixtures/} 아래 파일 이름
     * @return 손으로 쓴 중간 스키마 JSON 글
     */
    private static String fixtureText(String name) {
        String resource = "/fixtures/" + name;
        try (InputStream input = TestDocuments.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("픽스처가 없습니다: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("픽스처를 읽을 수 없습니다: " + resource, e);
        }
    }
}
