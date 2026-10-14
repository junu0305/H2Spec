package kr.go.h2spec.eval;

import kr.go.h2spec.eval.DetailMetric.Check;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.OperationPairing.Match;
import kr.go.h2spec.eval.OperationPairing.Method;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 정답 세트를 한 엔진으로 평가한 결과. summary.md와 문서별 차이 JSON이 이것을 그대로 옮긴다.
 *
 * @param set       세트 이름 (A)
 * @param engine    엔진 이름 (rule, llm)
 * @param date      평가한 날짜. 리포트 디렉터리 이름에 들어간다
 * @param documents 문서마다의 결과 (세트 순서)
 */
record BaselineReport(String set, String engine, LocalDate date, List<DocumentResult> documents) {

    /**
     * 문서 하나의 결과.
     *
     * @param document       문서 경로 (저장소 루트 기준)
     * @param gold           정답 파일 경로 (저장소 루트 기준). 없으면 null
     * @param error          엔진이 결과를 내지 못했거나 정답 파일을 읽지 못한 이유. 그런 일이 없었으면 null
     * @param operations     엔진이 찾은 오퍼레이션 (문서 순서)
     * @param generator      operations와 같은 자리의 generator 확인 결과. IR이 없는 자리는 null
     * @param goldOperations 정답 오퍼레이션. 정답 파일이 없으면 null
     * @param pairs          짝지은 오퍼레이션마다의 비교 (정답 순서)
     * @param missedGold     엔진이 찾지 못한 정답 오퍼레이션의 자리
     * @param extraEngine    정답에 없는 엔진 오퍼레이션의 자리
     */
    record DocumentResult(String document, String gold, String error, List<EngineOperation> operations,
                          List<GeneratorCheck.Outcome> generator, List<GoldOperation> goldOperations,
                          List<PairResult> pairs, List<Integer> missedGold, List<Integer> extraEngine) {

        /** @return 지표에 넣는 문서인지. 정답 파일을 읽은 문서만 넣는다 */
        boolean scored() {
            return goldOperations != null;
        }
    }

    /**
     * 짝 하나의 비교.
     *
     * @param match     짝 (정답·엔진 자리와 근거)
     * @param extracted 엔진이 이 오퍼레이션의 IR을 냈는지. false면 아래 비교는 모두 비어 있다
     * @param urlMatch  주소가 같은지. 정답에 url이 없으면 null
     * @param request   요청 파라미터 이름 비교. 정답에 request가 없으면 null
     * @param response  응답 필드 이름 비교. 정답에 response가 없으면 null
     * @param checks    정답 파일의 세부 키로 잰 지표({@link DetailMetric}). 키가 없는 지표는 빠진다
     */
    record PairResult(Match match, boolean extracted, Boolean urlMatch, NameMatch request, NameMatch response,
                      Map<DetailMetric, Check> checks) {
    }

    /**
     * 세트 전체의 지표. 정답 파일이 있는 문서만 센다.
     *
     * @param documents         평가한 문서 수
     * @param scoredDocuments   정답 파일이 있는 문서 수
     * @param goldOperations    정답 오퍼레이션 수
     * @param extracted         엔진이 IR을 낸 오퍼레이션 수 (짝과 무관)
     * @param recall            짝을 짓고 IR이 있는 정답 오퍼레이션 / 정답 오퍼레이션
     * @param precision         짝을 짓고 IR이 있는 엔진 오퍼레이션 / IR을 낸 엔진 오퍼레이션
     * @param pairedBy          짝을 지은 근거마다의 수
     * @param url               주소가 맞은 짝 / 주소를 비교한 짝
     * @param request           요청 파라미터 이름 점수
     * @param response          응답 필드 이름 점수
     * @param generator         generator를 통과한 정답 오퍼레이션 / supportedByGenerator가 false가 아닌 정답 오퍼레이션
     * @param generatorAssumed  generator 분모 중 supportedByGenerator가 없어 지원 범위 안으로 가정한 수
     * @param details           정답 파일의 세부 키로 잰 지표({@link DetailMetric})
     * @param coverage          지표마다 키가 있는 정답 오퍼레이션 수
     */
    record Totals(int documents, int scoredDocuments, int goldOperations, int extracted, Ratio recall,
                  Ratio precision, Map<Method, Integer> pairedBy, Ratio url, Score request, Score response,
                  Ratio generator, int generatorAssumed, Map<DetailMetric, Ratio> details,
                  Map<DetailMetric, Integer> coverage) {

        /** @return 짝지은 오퍼레이션 중 주소가 틀린 수. 조용한 오류라 가장 중요하다 */
        int wrongUrls() {
            return url.total() - url.hits();
        }
    }

    /**
     * 세트 전체의 지표를 계산한다.
     *
     * @return 지표
     */
    Totals totals() {
        int scored = 0;
        int goldOperations = 0;
        int extracted = 0;
        Ratio recall = Ratio.EMPTY;
        int pairedExtracted = 0;
        Map<Method, Integer> pairedBy = new EnumMap<>(Method.class);
        Ratio url = Ratio.EMPTY;
        Score request = Score.EMPTY;
        Score response = Score.EMPTY;
        Ratio generator = Ratio.EMPTY;
        int generatorAssumed = 0;
        Map<DetailMetric, Ratio> details = new EnumMap<>(DetailMetric.class);
        Map<DetailMetric, Integer> coverage = new EnumMap<>(DetailMetric.class);
        for (DetailMetric metric : DetailMetric.values()) {
            details.put(metric, Ratio.EMPTY);
            coverage.put(metric, 0);
        }
        for (DocumentResult document : documents) {
            if (!document.scored()) {
                continue;
            }
            scored++;
            goldOperations += document.goldOperations().size();
            extracted += (int) document.operations().stream().filter(EngineOperation::extracted).count();
            for (GoldOperation gold : document.goldOperations()) {
                for (DetailMetric metric : DetailMetric.values()) {
                    if (metric.hasKey(gold)) {
                        coverage.merge(metric, 1, Integer::sum);
                    }
                }
            }
            for (int g = 0; g < document.goldOperations().size(); g++) {
                PairResult pair = pairOf(document, g);
                boolean found = pair != null && pair.extracted();
                recall = recall.plus(found);
                Boolean supported = document.goldOperations().get(g).supportedByGenerator();
                if (!Boolean.FALSE.equals(supported)) {
                    // 키가 없으면 지원 범위 안으로 본다. 정답이 채워지면 그 오퍼레이션부터 정확한 값이 된다
                    generatorAssumed += supported == null ? 1 : 0;
                    generator = generator.plus(found && passesGenerator(document, pair.match().engine()));
                }
            }
            for (PairResult pair : document.pairs()) {
                if (!pair.extracted()) {
                    continue;
                }
                pairedExtracted++;
                pairedBy.merge(pair.match().method(), 1, Integer::sum);
                if (pair.urlMatch() != null) {
                    url = url.plus(pair.urlMatch());
                }
                if (pair.request() != null) {
                    request = request.plus(pair.request());
                }
                if (pair.response() != null) {
                    response = response.plus(pair.response());
                }
                pair.checks().forEach((metric, check) -> details.merge(metric, check.ratio(), Ratio::plus));
            }
        }
        return new Totals(documents.size(), scored, goldOperations, extracted, recall,
                new Ratio(pairedExtracted, extracted), pairedBy, url, request, response, generator, generatorAssumed,
                details, coverage);
    }

    private static PairResult pairOf(DocumentResult document, int gold) {
        return document.pairs().stream().filter(pair -> pair.match().gold() == gold).findFirst().orElse(null);
    }

    /** 범위 밖으로 건너뛴 오퍼레이션은 IR이 있어도 generator를 통과한 것으로 치지 않는다. 사용자가 받는 코드가 없다 */
    private static boolean passesGenerator(DocumentResult document, int engine) {
        GeneratorCheck.Outcome outcome = document.generator().get(engine);
        return outcome != null && outcome.passed() && !document.operations().get(engine).outOfScope();
    }
}
