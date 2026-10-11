package kr.go.h2spec.eval;

import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.llm.ExtractionCache;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.client.LlmResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 녹화한 응답으로 돌리는 llm 엔진. 회사를 부르지 않고, 문서마다 {@code <응답 디렉터리>/<문서 파일 이름>.json}을 LLM의 답으로 돌려준다.
 * 그 뒤의 검증·정규화·교차검증은 llm 엔진({@link LlmSpecParser}) 그대로라, 같은 응답이면 실제 호출과 같은 IR을 낸다.
 * <p>
 * 실제 호출이나 배치 API에서 받아 둔 응답, 다른 모델이 대신 쓴 응답을 키 없이 같은 지표로 다시 잴 때 쓴다.
 * 응답은 회사가 돌려준 글 그대로(중간 스키마 JSON)다. 회사의 구조화 출력이 스키마를 지키게 하는 것과 달리 여기서는 막는 장치가 없어,
 * 스키마를 어긴 응답은 llm 엔진이 읽지 못한 응답으로 다룬다.
 */
public final class ReplayLlmEvalEngine implements EvalEngine {

    /** 리포트에 적는 엔진 이름 */
    public static final String NAME = "llm";
    /** IR metadata.llm에 적는 값. 녹화 응답은 어느 회사·모델에서 왔는지 알 수 없어 재생이라는 것만 남긴다 */
    static final String PROVIDER = "replay";
    static final String MODEL = "recorded";
    static final String EFFORT = "unknown";
    private static final LlmResponse.Usage NO_USAGE = new LlmResponse.Usage(0, 0, 0, 0, 0);

    private final Path responses;
    private final Consumer<String> warnings;

    /**
     * @param responses 녹화 응답 디렉터리
     * @param warnings  llm 엔진의 경고(교차검증에서 rule 엔진만 찾은 오퍼레이션 등)를 받을 곳
     */
    public ReplayLlmEvalEngine(Path responses, Consumer<String> warnings) {
        this.responses = Objects.requireNonNull(responses, "responses");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 문서의 녹화 응답을 llm 엔진에 넣어 오퍼레이션을 얻는다. 지원 범위 밖으로 건너뛴 오퍼레이션도 정규화한 IR과 함께 담는다(설계 9.4).
     *
     * @throws IOException 문서를 읽지 못했거나, 녹화 응답이 없거나, 응답을 결과로 쓸 수 없을 때(JSON이 아니거나 스키마를 어김)
     */
    @Override
    public List<EngineOperation> extract(Path document) throws IOException {
        Path response = responses.resolve(document.getFileName() + ".json");
        if (!Files.isRegularFile(response)) {
            throw new IOException("녹화 응답이 없습니다: " + response);
        }
        String json = Files.readString(response);
        // 결과 캐시는 끈다. 녹화 응답이 바뀌어도 앞서 저장한 결과가 대신 쓰이지 않게 한다
        LlmSpecParser parser = new LlmSpecParser(
                request -> new LlmResponse(json, MODEL, NO_USAGE, LlmResponse.Finish.COMPLETE),
                PROVIDER, MODEL, EFFORT,
                LlmSpecParser.Options.defaults().withCache(ExtractionCache.disabled()).withWarnings(warnings));
        SpecParseResult result;
        try {
            result = parser.extract(document);
        } catch (SpecParseException e) {
            throw new IOException(e.getMessage(), e);
        }
        return result.operations().stream()
                .map(op -> new EngineOperation(op.operationName(), op.title(),
                        op.api() == null ? null : op.api().ir(), op.skipReason(), op.outOfScope()))
                .toList();
    }
}
