package kr.go.h2spec.eval.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.eval.Markdown;
import kr.go.h2spec.eval.proxy.ProxyReport.DocumentSignals;
import kr.go.h2spec.eval.proxy.ProxyReport.Proxy;
import kr.go.h2spec.eval.proxy.ProxyReport.Signal;
import kr.go.h2spec.eval.proxy.ResponseExamples.ResponseExample;
import kr.go.h2spec.eval.proxy.SuccessCodes.Candidate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static kr.go.h2spec.eval.Markdown.cell;

/**
 * 대리 지표를 proxy-summary.md(한국어 요약표)와 proxy.json(문서별 근거)으로 쓴다.
 * 휴리스틱으로 센 값이라는 것을 요약 머리와 JSON에 함께 적는다.
 */
public final class ProxyReportWriter {

    /** 요약 파일 이름 */
    public static final String SUMMARY = "proxy-summary.md";
    /** 근거 파일 이름 */
    public static final String EVIDENCE = "proxy.json";
    private static final String HEURISTIC_NOTE = "원문 블록과 rule 엔진 결과를 패턴으로 비교해 센 휴리스틱이라 정확하지 않다. "
            + "llm 엔진이 필요한 근거와 generator가 아직 못 하는 일(인증 방식, 루트 없는 JSON)의 규모를 보는 데 쓴다.";
    /** 지표마다 요약표에 나누어 적을 세부 종류 */
    private static final Map<Proxy, List<String>> KINDS = Map.of(
            Proxy.JSON_EXAMPLE_XML_IR, List.of(ProxyEvaluator.JSON_ONLY),
            Proxy.NON_STANDARD_STRUCTURE, List.of(ProxyEvaluator.ROOT_DIFFERS, ProxyEvaluator.CONTAINER_DIFFERS),
            Proxy.NON_00_SUCCESS_CODE, List.of(ProxyEvaluator.NON_NUMERIC_CODE, ProxyEvaluator.ZERO_VARIANT,
                    ProxyEvaluator.OTHER_NUMBER, ProxyEvaluator.CONFLICT),
            Proxy.NON_SERVICE_KEY_AUTH, List.of(AuthSignals.PATH_KEY, AuthSignals.HEADER_KEY,
                    AuthSignals.OTHER_QUERY_KEY, AuthSignals.NO_KEY_PARAMETER));

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 요약과 근거를 쓴다.
     *
     * @param report 대리 지표 결과
     * @param outDir 리포트 디렉터리. 없으면 만든다
     * @return 쓴 파일 (요약, 근거 순)
     * @throws IOException 쓰지 못했을 때
     */
    public List<Path> write(ProxyReport report, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path summary = outDir.resolve(SUMMARY);
        Files.writeString(summary, summary(report));
        Path evidence = outDir.resolve(EVIDENCE);
        Files.writeString(evidence, mapper.writeValueAsString(json(report)) + "\n");
        return List.of(summary, evidence);
    }

    /**
     * 요약표를 만든다.
     *
     * @param report 대리 지표 결과
     * @return proxy-summary.md 내용
     */
    String summary(ProxyReport report) {
        int read = report.readDocuments();
        long noOperations = report.documents().stream()
                .filter(document -> document.error() == null && document.irs() == 0).count();
        long withExamples = report.documents().stream()
                .filter(document -> !document.responseExamples().isEmpty()).count();
        long withCodes = report.documents().stream().filter(document -> !document.successCodes().isEmpty()).count();
        StringBuilder md = new StringBuilder();
        md.append("# 세트 C 대리 지표 (휴리스틱)\n\n");
        md.append("> ").append(HEURISTIC_NOTE).append("\n\n");
        md.append("- 대상: ").append(report.directory()).append(" (하위 디렉터리 포함)\n");
        md.append("- 문서: ").append(report.documents().size()).append("개 (읽지 못함 ")
                .append(report.documents().size() - read).append("개, rule 엔진이 IR을 내지 못함 ").append(noOperations)
                .append("개)\n");
        md.append("- 응답 예제를 찾은 문서 ").append(withExamples).append("개, 성공코드 후보를 찾은 문서 ").append(withCodes)
                .append("개\n\n");
        md.append("| 지표 | 문서 수 | 비율 (읽은 문서 기준) |\n| --- | --- | --- |\n");
        for (Proxy proxy : Proxy.values()) {
            int count = report.count(proxy);
            md.append("| ").append(proxy.mark()).append(' ').append(cell(proxy.label())).append(" | ").append(count)
                    .append(" | ").append(Markdown.percent(count, read)).append(" |\n");
            for (String kind : KINDS.get(proxy)) {
                md.append("| └ ").append(cell(kind)).append(" | ").append(report.count(proxy, kind)).append(" | |\n");
            }
        }
        md.append("\n세부 종류는 한 문서에 여럿일 수 있어 더하면 문서 수보다 클 수 있다.\n\n");

        md.append("## 문서별\n\n| 문서 | (a) | (b) | (c) | (d) | 근거 |\n| --- | --- | --- | --- | --- | --- |\n");
        for (DocumentSignals document : report.documents()) {
            md.append("| ").append(cell(document.document())).append(" |");
            if (document.error() != null) {
                md.append(" | | | | 읽지 못함: ").append(cell(document.error())).append(" |\n");
                continue;
            }
            List<String> evidence = new ArrayList<>();
            for (Proxy proxy : Proxy.values()) {
                boolean flagged = document.flagged(proxy);
                md.append(flagged ? " O |" : " |");
                if (flagged) {
                    evidence.add(proxy.mark() + " " + String.join("; ", document.signals().get(proxy).evidence()));
                }
            }
            md.append(' ').append(cell(String.join(" / ", evidence))).append(" |\n");
        }
        return md.toString();
    }

    private ObjectNode json(ProxyReport report) {
        ObjectNode json = mapper.createObjectNode();
        json.put("heuristic", true);
        json.put("note", HEURISTIC_NOTE);
        json.put("directory", report.directory());
        json.put("documents", report.documents().size());
        json.put("readDocuments", report.readDocuments());
        ObjectNode counts = json.putObject("counts");
        for (Proxy proxy : Proxy.values()) {
            ObjectNode count = counts.putObject(proxy.jsonKey());
            count.put("label", proxy.mark() + " " + proxy.label());
            count.put("documents", report.count(proxy));
            ObjectNode kinds = count.putObject("kinds");
            KINDS.get(proxy).forEach(kind -> kinds.put(kind, report.count(proxy, kind)));
        }
        ArrayNode documents = json.putArray("perDocument");
        for (DocumentSignals document : report.documents()) {
            ObjectNode node = documents.addObject();
            node.put("document", document.document());
            node.put("error", document.error());
            if (document.error() != null) {
                continue;
            }
            node.put("ruleOperations", document.operations());
            node.put("ruleIrs", document.irs());
            ArrayNode examples = node.putArray("responseExamples");
            for (ResponseExample example : document.examples()) {
                ObjectNode item = examples.addObject();
                item.put("block", example.block());
                item.put("format", example.format().name());
                item.put("errorFormat", example.error());
                example.roots().forEach(item.putArray("roots")::add);
                item.put("resultCode", example.resultCode());
                if (!example.error()) {
                    // 에러 응답 형식은 구조를 보지 않는다
                    String violation = StandardStructure.violation(example.document());
                    item.put("standard", violation == null);
                    item.put("violation", violation);
                }
            }
            ArrayNode codes = node.putArray("successCodeCandidates");
            for (Candidate candidate : document.successCodes()) {
                ObjectNode item = codes.addObject();
                item.put("code", candidate.code());
                item.put("source", candidate.source().name());
                item.put("block", candidate.block());
            }
            ObjectNode signals = node.putObject("signals");
            for (Proxy proxy : Proxy.values()) {
                Signal signal = document.signals().get(proxy);
                ObjectNode item = signals.putObject(proxy.jsonKey());
                item.put("flagged", signal.flagged());
                signal.kinds().forEach(item.putArray("kinds")::add);
                signal.evidence().forEach(item.putArray("evidence")::add);
            }
        }
        return json;
    }
}
