package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.eval.BaselineReport.DocumentResult;
import kr.go.h2spec.eval.BaselineReport.PairResult;
import kr.go.h2spec.eval.DataSets.EvalDocument;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.OperationPairing.Key;
import kr.go.h2spec.eval.OperationPairing.Match;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 정답 세트를 엔진 하나로 돌려 정답과 비교한다. 지표 계산은 {@link BaselineReport#totals()}가 맡고,
 * 여기서는 문서마다 엔진을 돌리고, 짝을 짓고, 짝마다 비교하고, IR마다 generator를 끝까지 돌려 본다.
 */
final class BaselineEvaluator {

    private final EvalEngine engine;
    private final GeneratorCheck generator;

    /**
     * @param engine    평가할 엔진
     * @param generator generator 통과 확인
     */
    BaselineEvaluator(EvalEngine engine, GeneratorCheck generator) {
        this.engine = engine;
        this.generator = generator;
    }

    /**
     * 세트를 평가한다. IR과 생성 코드는 임시 디렉터리에 만들고 끝나면 지운다.
     *
     * @param set       세트 이름
     * @param documents 평가할 문서
     * @param root      리포트에 적을 경로의 기준 (저장소 루트)
     * @param date      평가 날짜
     * @param progress  문서마다 한 줄씩 진행 상황을 받는다
     * @return 평가 결과
     * @throws IOException 임시 디렉터리를 쓰지 못했을 때
     */
    BaselineReport evaluate(String set, List<EvalDocument> documents, Path root, LocalDate date,
                            Consumer<String> progress) throws IOException {
        Path work = Files.createTempDirectory("llm-eval-");
        try {
            List<DocumentResult> results = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                DocumentResult result = evaluate(documents.get(i), root, work.resolve("document-" + (i + 1)));
                progress.accept(describe(result));
                results.add(result);
            }
            return new BaselineReport(set, engine.name(), date, results);
        } finally {
            try {
                deleteRecursively(work);
            } catch (IOException | UncheckedIOException e) {
                // 임시 디렉터리를 못 지운 것으로 낸 지표나 평가 중의 예외를 잃지 않는다. 남은 디렉터리는 알리기만 한다
                progress.accept("경고: 임시 디렉터리를 지우지 못했습니다: " + work + " (" + e.getMessage() + ")");
            }
        }
    }

    private DocumentResult evaluate(EvalDocument document, Path root, Path work) throws IOException {
        String name = display(root, document.document());
        String goldName = document.gold() == null ? null : display(root, document.gold());
        List<EngineOperation> operations;
        try {
            operations = engine.extract(document.document());
        } catch (ReplayLlmEvalEngine.MissingRecordingException e) {
            // 녹화가 없는 것은 엔진이 틀린 것이 아니다. 못 찾은 것으로 세면 엔진 품질과 무관하게 녹화 수만큼 재현율이 떨어진다
            return new DocumentResult(name, goldName, e.getMessage() + " (지표에서 뺌)", List.of(), List.of(), null,
                    List.of(), List.of(), List.of());
        } catch (IOException | RuntimeException e) {
            // 엔진이 결과를 내지 못한 문서의 정답 오퍼레이션은 모두 못 찾은 것으로 센다. 문서를 읽지 못했거나,
            // llm 엔진이 녹화 응답을 쓸 수 없었던 경우다. 이유는 예외 메시지가 말한다
            return failed(name, goldName, document.gold(), "엔진이 결과를 내지 못했습니다: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        List<GeneratorCheck.Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            EngineOperation operation = operations.get(i);
            outcomes.add(operation.extracted()
                    ? generator.check(operation.ir(), work.resolve("operation-" + (i + 1)))
                    : null);
        }
        if (document.gold() == null) {
            return new DocumentResult(name, null, null, operations, outcomes, null, List.of(), List.of(), List.of());
        }
        GoldFile gold;
        try {
            gold = GoldFile.read(document.gold());
        } catch (IOException | RuntimeException e) {
            return new DocumentResult(name, goldName, "정답 파일을 읽지 못했습니다: " + e, operations, outcomes,
                    null, List.of(), List.of(), List.of());
        }
        OperationPairing.Result pairing = OperationPairing.pair(
                gold.operations().stream().map(op -> new Key(op.name(), op.title())).toList(),
                operations.stream().map(op -> new Key(op.operationName(), op.title())).toList());
        List<PairResult> pairs = new ArrayList<>();
        for (Match match : pairing.matches()) {
            pairs.add(compare(match, gold.operations().get(match.gold()), operations.get(match.engine())));
        }
        return new DocumentResult(name, goldName, null, operations, outcomes, gold.operations(), pairs,
                pairing.unmatchedGold(), pairing.unmatchedEngine());
    }

    /** 문서를 읽지 못해도 정답 파일을 읽을 수 있으면 지표에 넣는다. 빼면 엔진이 실패한 문서가 재현율에서 사라진다 */
    private DocumentResult failed(String name, String goldName, Path goldPath, String error) {
        List<GoldOperation> goldOperations = null;
        List<Integer> missed = List.of();
        if (goldPath != null) {
            try {
                goldOperations = GoldFile.read(goldPath).operations();
                missed = IntStream.range(0, goldOperations.size()).boxed().toList();
            } catch (IOException | RuntimeException ignored) {
                // 정답 파일까지 못 읽으면 지표에서 빼고 오류만 남긴다
            }
        }
        return new DocumentResult(name, goldName, error, List.of(), List.of(), goldOperations, List.of(), missed,
                List.of());
    }

    /**
     * 짝 하나를 비교한다. 엔진이 IR을 내지 못한 짝은 아무것도 비교하지 않는다.
     */
    static PairResult compare(Match match, GoldOperation gold, EngineOperation operation) {
        if (!operation.extracted()) {
            return new PairResult(match, false, null, null, null, Map.of());
        }
        JsonNode api = operation.ir().path("api");
        return new PairResult(match, true,
                gold.url() == null ? null : sameUrl(gold.url(), operation.url()),
                gold.request() == null ? null : NameMatch.of(gold.request(), requestNames(api)),
                gold.response() == null ? null : NameMatch.of(gold.response(), leafNames(api)),
                DetailMetric.checkAll(gold, operation));
    }

    /**
     * 주소는 끝 슬래시만 무시하고 글자 그대로 비교한다 (교차검증과 같다). 스킴을 무시하면 http와 https가 갈린 경우를 놓친다.
     */
    static boolean sameUrl(String expected, String actual) {
        return expected.replaceAll("/+$", "").equals(actual.replaceAll("/+$", ""));
    }

    private static List<String> requestNames(JsonNode api) {
        List<String> names = new ArrayList<>();
        api.path("requestParameters").forEach(param -> names.add(param.path("name").asText()));
        return names;
    }

    /** 응답 경로의 마지막 칸. RealDocumentCorpusTest가 정답과 맞추는 방식과 같다 */
    static List<String> leafNames(JsonNode api) {
        List<String> names = new ArrayList<>();
        api.path("responseFields").forEach(field -> {
            String path = field.path("path").asText();
            names.add(path.substring(path.lastIndexOf('.') + 1).replace("[]", ""));
        });
        return names;
    }

    private static String describe(DocumentResult result) {
        long extracted = result.operations().stream().filter(EngineOperation::extracted).count();
        long passed = result.generator().stream().filter(outcome -> outcome != null && outcome.passed()).count();
        String gold = result.scored() ? "정답 " + result.goldOperations().size() : "정답 없음";
        String line = result.document() + ": " + gold + ", 엔진 " + result.operations().size() + " (IR " + extracted
                + "), generator 통과 " + passed + "/" + extracted;
        return result.error() == null ? line : line + " — " + result.error();
    }

    /** 리포트의 경로는 저장소 루트 기준으로 적는다. 실행한 위치마다 리포트가 달라지지 않게 한다 */
    static String display(Path root, Path path) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        return (absolute.startsWith(absoluteRoot) ? absoluteRoot.relativize(absolute) : absolute)
                .toString().replace('\\', '/');
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
