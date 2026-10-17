package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.EngineOperation;
import kr.go.h2spec.eval.RuleEvalEngine;
import kr.go.h2spec.eval.proxy.ExampleParser.Format;
import kr.go.h2spec.eval.proxy.ProxyReport.DocumentSignals;
import kr.go.h2spec.eval.proxy.ProxyReport.Proxy;
import kr.go.h2spec.eval.proxy.ProxyReport.Signal;
import kr.go.h2spec.eval.proxy.ResponseExamples.ResponseExample;
import kr.go.h2spec.eval.proxy.SuccessCodes.Candidate;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.DocumentReaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 정답 없는 문서 묶음(세트 C)에서 대리 지표 네 가지를 센다. 문서마다 rule 엔진을 돌리고,
 * 같은 블록에서 응답 예제·성공코드 후보·인증 흔적을 찾아 rule 결과와 견준다. 패턴으로 세는 휴리스틱이라 정확하지 않다.
 */
public final class ProxyEvaluator {

    /** (a)의 세부 종류: JSON 예제뿐이라 rule의 XML이 틀렸을 가능성이 큰 문서 */
    static final String JSON_ONLY = "XML 예제 없음";
    /** (b)의 세부 종류 */
    static final String ROOT_DIFFERS = "루트가 response 하나가 아님";
    static final String CONTAINER_DIFFERS = "표준에 없는 자리의 컨테이너";
    /** (c)의 세부 종류 */
    static final String NON_NUMERIC_CODE = "숫자가 아닌 코드 (INFO-00 등)";
    static final String ZERO_VARIANT = "00이 아닌 0 표기 (0, 000 등)";
    static final String OTHER_NUMBER = "00이 아닌 숫자 코드";
    static final String CONFLICT = "출처끼리 값이 엇갈림";

    private static final String DEFAULT_SUCCESS_CODE = "00";

    /**
     * 디렉터리(하위 포함)의 DOCX/HWP/HWPX 문서마다 대리 지표를 센다. 읽지 못한 문서는 이유를 남기고 넘어간다.
     *
     * @param directory 문서 디렉터리
     * @param progress  문서마다 한 줄씩 진행 상황을 받는다
     * @return 결과
     * @throws IOException 디렉터리를 읽지 못했거나 문서가 하나도 없을 때
     */
    public ProxyReport evaluate(Path directory, Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("문서 디렉터리가 없습니다: " + directory);
        }
        List<Path> documents;
        try (Stream<Path> files = Files.walk(directory)) {
            documents = files.filter(Files::isRegularFile).filter(DocumentReaders::isDocument).sorted().toList();
        }
        if (documents.isEmpty()) {
            throw new IOException("디렉터리에 DOCX/HWP/HWPX 문서가 없습니다: " + directory);
        }
        List<DocumentSignals> results = new ArrayList<>();
        for (Path document : documents) {
            DocumentSignals result = evaluate(document, directory.relativize(document).toString().replace('\\', '/'));
            progress.accept(describe(result));
            results.add(result);
        }
        return new ProxyReport(directory.toString().replace('\\', '/'), results);
    }

    /**
     * 문서 하나의 대리 지표를 센다.
     *
     * @param document 문서 경로
     * @param name     리포트에 적을 이름
     * @return 결과
     */
    DocumentSignals evaluate(Path document, String name) {
        List<Block> blocks;
        List<EngineOperation> operations;
        try {
            blocks = DocumentReaders.read(document);
            operations = RuleEvalEngine.operations(blocks, document);
        } catch (IOException | RuntimeException e) {
            return new DocumentSignals(name, e.toString(), 0, 0, List.of(), List.of(), Map.of());
        }
        List<ResponseExample> examples = ResponseExamples.find(blocks);
        List<Candidate> candidates = SuccessCodes.collect(blocks, examples);
        Map<Proxy, Signal> signals = new EnumMap<>(Proxy.class);
        List<ResponseExample> responses = examples.stream().filter(example -> !example.error()).toList();
        signals.put(Proxy.JSON_EXAMPLE_XML_IR, jsonExampleXmlIr(responses, operations));
        signals.put(Proxy.NON_STANDARD_STRUCTURE, nonStandardStructure(responses));
        signals.put(Proxy.NON_00_SUCCESS_CODE, non00SuccessCode(candidates));
        List<String> authEvidence = new ArrayList<>();
        Set<String> authKinds = AuthSignals.kinds(blocks, operations, authEvidence);
        signals.put(Proxy.NON_SERVICE_KEY_AUTH, new Signal(!authKinds.isEmpty(), List.copyOf(authKinds),
                List.copyOf(authEvidence)));
        int irs = (int) operations.stream().filter(EngineOperation::extracted).count();
        return new DocumentSignals(name, null, operations.size(), irs, examples, candidates, signals);
    }

    /** (a) rule 엔진은 포맷 파라미터의 샘플값으로만 포맷을 정해 JSON 전용 API도 XML로 낸다 */
    private static Signal jsonExampleXmlIr(List<ResponseExample> responses, List<EngineOperation> operations) {
        List<String> json = blocksOf(responses, Format.JSON);
        List<String> xmlIrs = operations.stream()
                .filter(EngineOperation::extracted)
                .filter(op -> "XML".equals(op.ir().path("api").path("responseFormat").asText()))
                .map(EngineOperation::apiId)
                .toList();
        if (json.isEmpty() || xmlIrs.isEmpty()) {
            return new Signal(false, List.of(), List.of());
        }
        List<String> kinds = blocksOf(responses, Format.XML).isEmpty() ? List.of(JSON_ONLY) : List.of();
        return new Signal(true, kinds, List.of("JSON 응답 예제 " + json + ", rule IR이 XML인 오퍼레이션 " + xmlIrs));
    }

    /** (b) rule 엔진은 모든 문서에 표준 구조를 가정한다 */
    private static Signal nonStandardStructure(List<ResponseExample> responses) {
        Set<String> kinds = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        for (ResponseExample example : responses) {
            String violation = StandardStructure.violation(example.document());
            if (violation != null) {
                kinds.add(violation.startsWith("루트") ? ROOT_DIFFERS : CONTAINER_DIFFERS);
                evidence.add(example.block() + " " + example.format() + ": " + violation);
            }
        }
        return new Signal(!evidence.isEmpty(), List.copyOf(kinds), evidence);
    }

    /**
     * (c) 후보 하나라도 00이 아니면 센다. llm 엔진의 성공코드 정책({@code SuccessCodeSelector})은 0·000을 00으로 바꾸고
     * 인터셉터도 숫자 코드는 앞자리 0을 떼고 비교하지만, INFO-00처럼 숫자가 아닌 코드는 글자까지 같아야 성공이라
     * rule 엔진의 고정값 00으로는 정상 응답이 예외가 된다. 그래서 따로 나눠 센다
     */
    private static Signal non00SuccessCode(List<Candidate> candidates) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            String code = candidate.code();
            if (DEFAULT_SUCCESS_CODE.equals(code)) {
                continue;
            }
            if (!code.matches("\\d+")) {
                kinds.add(NON_NUMERIC_CODE);
            } else if (code.matches("0+")) {
                kinds.add(ZERO_VARIANT);
            } else {
                kinds.add(OTHER_NUMBER);
            }
        }
        if (kinds.isEmpty()) {
            return new Signal(false, List.of(), List.of());
        }
        if (candidates.stream().map(Candidate::code).distinct().count() > 1) {
            kinds.add(CONFLICT);
        }
        List<String> evidence = candidates.stream()
                .map(candidate -> candidate.source().label() + " " + candidate.code() + " (" + candidate.block() + ")")
                .distinct()
                .toList();
        return new Signal(true, List.copyOf(kinds), evidence);
    }

    private static List<String> blocksOf(List<ResponseExample> responses, Format format) {
        return responses.stream().filter(example -> example.format() == format).map(ResponseExample::block).toList();
    }

    private static String describe(DocumentSignals result) {
        if (result.error() != null) {
            return result.document() + ": 읽지 못함 — " + result.error();
        }
        List<String> marks = new ArrayList<>();
        for (Proxy proxy : Proxy.values()) {
            if (result.flagged(proxy)) {
                marks.add(proxy.mark());
            }
        }
        return result.document() + ": " + (marks.isEmpty() ? "해당 없음" : String.join(" ", marks));
    }
}
