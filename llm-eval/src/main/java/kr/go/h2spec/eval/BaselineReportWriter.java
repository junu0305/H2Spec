package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.eval.BaselineReport.DocumentResult;
import kr.go.h2spec.eval.BaselineReport.PairResult;
import kr.go.h2spec.eval.BaselineReport.Totals;
import kr.go.h2spec.eval.DetailMetric.Check;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.OperationPairing.Method;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kr.go.h2spec.eval.Markdown.NONE;
import static kr.go.h2spec.eval.Markdown.cell;
import static kr.go.h2spec.eval.Markdown.decimal;

/**
 * 평가 결과를 summary.md(한국어 요약표)와 문서별 차이 JSON으로 쓴다. 요약표는 PR 본문에 붙인다.
 * 실행 시각·소요 시간은 쓰지 않는다. 같은 입력이면 날짜 말고는 같은 리포트가 나와야 실행끼리 diff로 비교할 수 있다.
 */
final class BaselineReportWriter {

    /** 요약 파일 이름 */
    static final String SUMMARY = "summary.md";
    /** 문서별 차이 JSON을 두는 디렉터리 이름 */
    static final String DOCUMENTS = "documents";

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 리포트를 쓴다.
     *
     * @param report 평가 결과
     * @param outDir 리포트 디렉터리. 없으면 만든다
     * @return 쓴 파일 (요약, 문서별 JSON 순)
     * @throws IOException 쓰지 못했을 때
     */
    List<Path> write(BaselineReport report, Path outDir) throws IOException {
        Path documents = Files.createDirectories(outDir.resolve(DOCUMENTS));
        // 같은 디렉터리에 다시 쓰면(같은 날 같은 엔진) 이번에 평가하지 않은 문서의 지난 JSON이 남아 섞인다
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(documents, "*.json")) {
            for (Path file : stale) {
                Files.delete(file);
            }
        }
        List<Path> written = new ArrayList<>();
        Path summary = outDir.resolve(SUMMARY);
        Files.writeString(summary, summary(report));
        written.add(summary);
        Set<String> used = new HashSet<>();
        for (DocumentResult result : report.documents()) {
            Path file = documents.resolve(uniqueName(fileName(result.document()), used) + ".json");
            Files.writeString(file, mapper.writeValueAsString(documentJson(result)) + "\n");
            written.add(file);
        }
        return written;
    }

    /**
     * 요약표를 만든다.
     *
     * @param report 평가 결과
     * @return summary.md 내용
     */
    String summary(BaselineReport report) {
        Totals totals = report.totals();
        StringBuilder md = new StringBuilder();
        boolean rule = RuleEvalEngine.NAME.equals(report.engine());
        // rule 엔진의 결과는 llm 엔진을 견줄 기준선이고, llm 엔진의 결과는 그 기준선에 대한 평가다
        md.append("# ").append(report.engine()).append(rule ? " 엔진 기준선 — 세트 " : " 엔진 평가 — 세트 ")
                .append(report.set()).append("\n\n");
        md.append("- 날짜: ").append(report.date()).append('\n');
        md.append("- 엔진: ").append(report.engine()).append('\n');
        // 정답 파일은 있지만 녹화 응답이 없거나 정답 파일을 읽지 못해 지표에서 뺀 문서는 정답 없는 문서와 따로 센다
        long excluded = report.documents().stream().filter(result -> !result.scored() && result.gold() != null).count();
        md.append("- 문서: ").append(totals.documents()).append("개 (정답 있음 ").append(totals.scoredDocuments())
                .append("개, 정답 없음 ").append(totals.documents() - totals.scoredDocuments() - excluded).append("개")
                .append(excluded == 0 ? "" : ", 지표에서 뺌 " + excluded + "개").append(")\n");
        md.append("- 정답 오퍼레이션 ").append(totals.goldOperations()).append("개, 엔진이 IR을 낸 오퍼레이션 ")
                .append(totals.extracted()).append("개 (정답 있는 문서)\n\n");

        md.append("## 지표\n\n");
        md.append("오퍼레이션은 정답의 name, title 순으로 짝짓고 남은 것은 문서 안 순서로 짝짓는다. 주소로는 짝짓지 않는다. ")
                .append("재현율·정밀도는 \"지원 범위 밖\"으로 건너뛰기 전의 추출 결과로 잰다. ")
                .append("요청·응답 이름 F1과 아래 정확도는 짝을 짓고 엔진이 IR을 낸 오퍼레이션만 잰다.\n\n");
        md.append("| 지표 | 값 | 기준 |\n| --- | --- | --- |\n");
        row(md, "오퍼레이션 재현율", percent(totals.recall()), "짝짓기 근거: " + pairedBy(totals.pairedBy()));
        row(md, "오퍼레이션 정밀도", percent(totals.precision()), "IR을 낸 엔진 오퍼레이션 중 정답과 짝지은 것");
        row(md, "틀린 URL 수", totals.url().total() == 0 ? NONE : String.valueOf(totals.wrongUrls()),
                "주소를 비교한 짝 " + totals.url().total() + "개 중. 끝 슬래시만 무시하고 글자 그대로 비교");
        row(md, "요청 파라미터 이름 F1", decimal(totals.request().f1()), scoreNote(totals.request()));
        row(md, "응답 필드 이름 F1", decimal(totals.response().f1()),
                "경로의 마지막 칸. " + scoreNote(totals.response()));
        String assumed = totals.generatorAssumed() == 0 ? ""
                : ". supportedByGenerator가 없는 " + totals.generatorAssumed() + "개는 지원 범위 안으로 가정";
        row(md, "generator 통과율", percent(totals.generator()),
                "IrLoader → CodeGenerator → javac. supportedByGenerator가 false인 오퍼레이션은 뺀다" + assumed);
        for (DetailMetric metric : DetailMetric.values()) {
            Ratio ratio = totals.details().get(metric);
            int covered = totals.coverage().get(metric);
            String value = covered == 0 ? "정답 없음" : percent(ratio);
            row(md, metric.label(), value, metric.goldKey() + " 키가 있는 정답 오퍼레이션 " + covered + "/"
                    + totals.goldOperations());
        }
        md.append(rule ? "\n안정성·비용·잘림 비율은 llm 엔진 평가에만 해당한다.\n\n"
                : "\n녹화 응답을 재생해 평가했으므로 안정성·비용·잘림 비율은 재지 않았다.\n\n");

        documentTable(md, report);
        mismatchSections(md, report);
        generatorFailures(md, report);
        notes(md, report);
        return md.toString();
    }

    private void documentTable(StringBuilder md, BaselineReport report) {
        md.append("## 문서별\n\n");
        md.append("| 문서 | 정답 | 엔진 (IR) | 찾음 | 틀린 URL | 요청 F1 | 응답 F1 | generator |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (DocumentResult result : report.documents()) {
            long extracted = result.operations().stream().filter(EngineOperation::extracted).count();
            long passed = result.generator().stream().filter(outcome -> outcome != null && outcome.passed()).count();
            String engine = result.operations().size() + " (" + extracted + ")";
            String generator = passed + "/" + extracted;
            if (!result.scored()) {
                md.append("| ").append(cell(fileName(result.document()))).append(result.gold() == null ? " | 없음 | " : " | 지표에서 뺌 | ")
                        .append(engine)
                        .append(" | - | - | - | - | ").append(generator).append(" (참고) |\n");
                continue;
            }
            Score request = Score.EMPTY;
            Score response = Score.EMPTY;
            int found = 0;
            int wrong = 0;
            for (PairResult pair : result.pairs()) {
                if (!pair.extracted()) {
                    continue;
                }
                found++;
                wrong += Boolean.FALSE.equals(pair.urlMatch()) ? 1 : 0;
                request = pair.request() == null ? request : request.plus(pair.request());
                response = pair.response() == null ? response : response.plus(pair.response());
            }
            md.append("| ").append(cell(fileName(result.document()))).append(" | ")
                    .append(result.goldOperations().size()).append(" | ").append(engine).append(" | ")
                    .append(found).append('/').append(result.goldOperations().size()).append(" | ").append(wrong)
                    .append(" | ").append(decimal(request.f1())).append(" | ").append(decimal(response.f1()))
                    .append(" | ").append(generator).append(" |\n");
        }
        md.append('\n');
    }

    private void mismatchSections(StringBuilder md, BaselineReport report) {
        List<String> wrongUrls = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (DocumentResult result : report.documents()) {
            if (!result.scored()) {
                continue;
            }
            String document = fileName(result.document());
            for (PairResult pair : result.pairs()) {
                GoldOperation gold = result.goldOperations().get(pair.match().gold());
                EngineOperation operation = result.operations().get(pair.match().engine());
                String label = document + " #" + (pair.match().gold() + 1);
                if (!pair.extracted()) {
                    missed.add(label + ": 짝은 지었지만 IR이 없음 — " + operation.skipReason());
                    continue;
                }
                if (Boolean.FALSE.equals(pair.urlMatch())) {
                    wrongUrls.add(label + ": 정답 `" + gold.url() + "`, IR `" + operation.url() + "`");
                }
                nameDiff(names, label + " 요청", pair.request());
                nameDiff(names, label + " 응답", pair.response());
                pair.checks().forEach((metric, check) -> check.mismatches()
                        .forEach(mismatch -> details.add(label + " " + metric.label() + ": " + mismatch)));
            }
            for (int g : result.missedGold()) {
                GoldOperation gold = result.goldOperations().get(g);
                missed.add(document + " #" + (g + 1) + ": " + describe(gold.name(), gold.title(), gold.url()));
            }
            for (int e : result.extraEngine()) {
                EngineOperation operation = result.operations().get(e);
                extra.add(document + " 엔진 #" + (e + 1) + ": "
                        + describe(operation.operationName(), operation.title(), operation.url())
                        + (operation.skipReason() == null ? "" : " (건너뜀: " + operation.skipReason() + ")"));
            }
        }
        section(md, "틀린 URL", wrongUrls);
        section(md, "못 찾은 오퍼레이션", missed);
        section(md, "정답에 없는 엔진 오퍼레이션", extra);
        section(md, "요청·응답 이름 차이", names);
        section(md, "세부 키 지표에서 틀린 항목", details);
    }

    private void generatorFailures(StringBuilder md, BaselineReport report) {
        List<String> rows = new ArrayList<>();
        for (DocumentResult result : report.documents()) {
            for (int i = 0; i < result.generator().size(); i++) {
                GeneratorCheck.Outcome outcome = result.generator().get(i);
                if (outcome == null || outcome.passed()) {
                    continue;
                }
                // 지원 범위 밖으로 건너뛴 오퍼레이션은 generator 통과율에서 빠지므로, generator 결함으로 읽히지 않게 표시한다
                EngineOperation operation = result.operations().get(i);
                // 엔진이 지원 범위 밖으로 건너뛴 오퍼레이션은 생성 코드가 없어 통과로 세지 않는다. generator 결함과 구별해 표시한다
                rows.add("| " + cell(fileName(result.document())) + (result.scored() ? "" : " (정답 없음)") + " | #"
                        + (i + 1) + " " + cell(operation.apiId()) + (operation.outOfScope() ? " (엔진이 지원 범위 밖으로 건너뜀)" : "")
                        + " | " + outcome.stage().label() + " | " + cell(outcome.message()) + " |");
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        md.append("## generator 실패\n\n| 문서 | 오퍼레이션 | 단계 | 메시지 |\n| --- | --- | --- | --- |\n");
        rows.forEach(row -> md.append(row).append('\n'));
        md.append('\n');
    }

    private void notes(StringBuilder md, BaselineReport report) {
        List<String> noGold = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (DocumentResult result : report.documents()) {
            if (result.error() != null) {
                errors.add(fileName(result.document()) + ": " + result.error());
            } else if (!result.scored()) {
                noGold.add(result.document());
            }
        }
        if (!noGold.isEmpty()) {
            md.append("## 정답 파일이 없는 문서\n\n")
                    .append("세트 A에 들지만 정답 파일(`<문서 파일 이름>.expected.json` 또는 `<이름>.expected.json`)이 아직 없어 ")
                    .append("지표에서 뺐다. 엔진 결과와 generator 통과 수는 참고로만 적었다.\n\n");
            noGold.forEach(document -> md.append("- ").append(document).append('\n'));
            md.append('\n');
        }
        section(md, "결과를 내지 못한 문서", errors);
    }

    private static void nameDiff(List<String> lines, String label, NameMatch match) {
        if (match == null || (match.missing().isEmpty() && match.extra().isEmpty())) {
            return;
        }
        lines.add(label + ": 정답에만 " + match.missing() + ", 엔진에만 " + match.extra());
    }

    private static void section(StringBuilder md, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        md.append("## ").append(title).append("\n\n");
        lines.forEach(line -> md.append("- ").append(line.replaceAll("\\R+", " ")).append('\n'));
        md.append('\n');
    }

    private static void row(StringBuilder md, String metric, String value, String basis) {
        md.append("| ").append(cell(metric)).append(" | ").append(cell(value)).append(" | ").append(cell(basis))
                .append(" |\n");
    }

    private static String percent(Ratio ratio) {
        return Markdown.percent(ratio.hits(), ratio.total());
    }

    private static String scoreNote(Score score) {
        return "정밀도 " + decimal(score.precision()) + " · 재현율 " + decimal(score.recall()) + " (맞음 "
                + score.truePositives() + ", 엔진에만 " + score.falsePositives() + ", 정답에만 "
                + score.falseNegatives() + ")";
    }

    private static String pairedBy(Map<Method, Integer> pairedBy) {
        List<String> parts = new ArrayList<>();
        for (Method method : Method.values()) {
            parts.add(method.label() + " " + pairedBy.getOrDefault(method, 0));
        }
        return String.join(", ", parts);
    }

    private static String describe(String name, String title, String url) {
        List<String> parts = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            parts.add(name);
        }
        if (title != null && !title.isBlank()) {
            parts.add(title);
        }
        if (url != null) {
            parts.add(url);
        }
        return parts.isEmpty() ? "(이름·주소 없음)" : String.join(" · ", parts);
    }

    /**
     * IR에서 검수자가 엔진끼리 견줄 값을 옮긴다. 정답 파일이 아직 담지 않은 응답 포맷·성공코드·응답 경로와,
     * 엔진이 확신하지 못해 남긴 검수 노트다. 정답이 없는 문서도 이것으로 엔진의 결과를 볼 수 있다.
     */
    private static void irSummary(ObjectNode op, EngineOperation operation) {
        if (!operation.extracted()) {
            return;
        }
        JsonNode api = operation.ir().path("api");
        op.put("responseFormat", api.path("responseFormat").asText(null));
        op.put("successResultCode", api.path("errorSpec").path("successResultCode").asText(null));
        ArrayNode paths = op.putArray("responsePaths");
        api.path("responseFields").forEach(field -> paths.add(field.path("path").asText()));
        ArrayNode notes = op.putArray("reviewNotes");
        operation.ir().path("metadata").path("reviewNotes").forEach(note -> notes.add(note.asText()));
    }

    /**
     * 문서 하나의 차이 JSON을 만든다. 오퍼레이션 번호는 문서 안 순서로 1부터 센다.
     *
     * @param result 문서 결과
     * @return JSON
     */
    ObjectNode documentJson(DocumentResult result) {
        ObjectNode json = mapper.createObjectNode();
        json.put("document", result.document());
        json.put("gold", result.gold());
        json.put("error", result.error());
        ArrayNode operations = json.putArray("engineOperations");
        for (int i = 0; i < result.operations().size(); i++) {
            EngineOperation operation = result.operations().get(i);
            ObjectNode op = operations.addObject();
            op.put("index", i + 1);
            op.put("operationName", operation.operationName());
            op.put("title", operation.title());
            op.put("apiId", operation.apiId());
            op.put("url", operation.url());
            op.put("skipReason", operation.skipReason());
            op.put("outOfScope", operation.outOfScope());
            irSummary(op, operation);
            GeneratorCheck.Outcome outcome = result.generator().isEmpty() ? null : result.generator().get(i);
            if (outcome == null) {
                op.putNull("generator");
            } else {
                ObjectNode generator = op.putObject("generator");
                generator.put("passed", outcome.passed());
                generator.put("stage", outcome.stage() == null ? null : outcome.stage().label());
                generator.put("message", outcome.message());
            }
        }
        if (!result.scored()) {
            return json;
        }
        ArrayNode pairs = json.putArray("pairs");
        for (PairResult pair : result.pairs()) {
            GoldOperation gold = result.goldOperations().get(pair.match().gold());
            ObjectNode node = pairs.addObject();
            node.put("gold", pair.match().gold() + 1);
            node.put("engine", pair.match().engine() + 1);
            node.put("pairedBy", pair.match().method().label());
            node.put("extracted", pair.extracted());
            if (!pair.extracted()) {
                continue;
            }
            if (pair.urlMatch() != null) {
                ObjectNode url = node.putObject("url");
                url.put("expected", gold.url());
                url.put("actual", result.operations().get(pair.match().engine()).url());
                url.put("match", pair.urlMatch());
            }
            nameJson(node, "request", pair.request());
            nameJson(node, "response", pair.response());
            if (!pair.checks().isEmpty()) {
                ObjectNode checks = node.putObject("details");
                for (Map.Entry<DetailMetric, Check> entry : pair.checks().entrySet()) {
                    ObjectNode check = checks.putObject(entry.getKey().jsonKey());
                    check.put("metric", entry.getKey().label());
                    check.put("goldKey", entry.getKey().goldKey());
                    check.put("compared", entry.getValue().compared());
                    check.put("correct", entry.getValue().correct());
                    entry.getValue().mismatches().forEach(check.putArray("mismatches")::add);
                }
            }
        }
        ArrayNode missed = json.putArray("missedGold");
        for (int g : result.missedGold()) {
            GoldOperation gold = result.goldOperations().get(g);
            ObjectNode node = missed.addObject();
            node.put("gold", g + 1);
            node.put("name", gold.name());
            node.put("title", gold.title());
            node.put("url", gold.url());
        }
        ArrayNode extra = json.putArray("extraEngine");
        result.extraEngine().forEach(e -> extra.add(e + 1));
        return json;
    }

    private static void nameJson(ObjectNode parent, String key, NameMatch match) {
        if (match == null) {
            return;
        }
        ObjectNode node = parent.putObject(key);
        node.put("truePositives", match.truePositives());
        node.put("falsePositives", match.falsePositives());
        node.put("falseNegatives", match.falseNegatives());
        match.missing().forEach(node.putArray("missing")::add);
        match.extra().forEach(node.putArray("extra")::add);
    }

    private static String fileName(String document) {
        return document.substring(document.lastIndexOf('/') + 1);
    }

    /** 디렉터리가 다르면 파일 이름이 같은 문서가 있을 수 있어 두 번째부터 번호를 붙인다 */
    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        for (int i = 2; !used.add(candidate); i++) {
            candidate = name + "-" + i;
        }
        return candidate;
    }
}
