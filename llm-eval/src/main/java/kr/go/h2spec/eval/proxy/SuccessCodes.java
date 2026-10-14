package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.proxy.ResponseExamples.ResponseExample;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서에 적힌 정상(성공) 결과코드 후보를 모은다. llm 엔진의 성공코드 후보 출처와 같은 세 자리를 본다:
 * 응답 필드 표의 결과코드 행(샘플·설명), 응답 예제의 결과코드 값, 에러코드 표의 정상 행.
 * rule 엔진은 문서와 상관없이 늘 00을 쓰므로, 00이 아닌 후보가 있는 문서가 대리 지표 (c)다. 휴리스틱이다.
 */
final class SuccessCodes {

    /** 후보로 받는 코드 꼴. 검증 규칙 V8의 꼴에 영숫자 한 자 이상을 더 요구해 빈 칸 표시인 "-"는 코드로 보지 않는다 */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]*[A-Za-z0-9][A-Za-z0-9_-]*");
    /** 샘플 칸의 코드. 뒤에 붙인 괄호 설명("00(정상)")은 뗀다 */
    private static final Pattern SAMPLE_CODE =
            Pattern.compile("([A-Za-z0-9_-]*[A-Za-z0-9][A-Za-z0-9_-]*)\\s*(?:[(（].*)?");
    /** 설명 칸의 "00:성공", "INFO-00 : 정상" */
    private static final Pattern DESCRIBED_CODE =
            Pattern.compile("(?i)(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{1,30})\\s*[:：=]\\s*(정상|성공|normal|success)");
    /** 정상 행의 표시. "비정상"과 "ABNORMAL"은 거른다 */
    private static final Pattern NORMAL_MARK =
            Pattern.compile("(?i)(?<!비)정상|성공|(?<![a-z])normal(?![a-z])|(?<![a-z])success");
    /** 필드 표 머리행의 샘플 열 라벨 (공백을 지우고 소문자로 맞춘 꼴) */
    private static final Set<String> SAMPLE_LABELS = Set.of("샘플데이터", "샘플", "샘플값", "값샘플", "예시", "예시값",
            "예제", "예제값", "sample", "sampledata", "example");
    private static final Set<String> DESCRIPTION_LABELS = Set.of("항목설명", "설명", "상세설명", "description", "desc");
    /** 에러코드 표 머리행의 첫 칸 ("에러코드", "에러 코드", "결과코드") */
    private static final Pattern CODE_TABLE_LABEL =
            Pattern.compile("(에러|오류|결과|응답|error|result|response)(코드|code)");

    private SuccessCodes() {
    }

    /** 후보의 출처 ({@code LlmSuccessCode.Source}와 같다) */
    enum Source {
        FIELD_TABLE("응답 필드 표"),
        RESPONSE_EXAMPLE("응답 예제"),
        ERROR_TABLE("에러코드 표");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** @return 리포트에 쓰는 이름 */
        String label() {
            return label;
        }
    }

    /**
     * 성공코드 후보.
     *
     * @param code   코드 (원문 그대로)
     * @param source 출처
     * @param block  블록 번호
     */
    record Candidate(String code, Source source, String block) {
    }

    /**
     * 후보를 문서 순서로 모은다. 에러 응답 형식의 예제는 빼고 본다.
     *
     * @param blocks   문서 블록
     * @param examples 응답 예제
     * @return 후보
     */
    static List<Candidate> collect(List<Block> blocks, List<ResponseExample> examples) {
        List<Candidate> candidates = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) {
            if (blocks.get(b) instanceof Block.Table table) {
                String block = "T" + (b + 1);
                fieldTable(table.rows(), block, candidates);
                errorTable(table.rows(), block, candidates);
            }
        }
        for (ResponseExample example : examples) {
            if (!example.error() && example.resultCode() != null && CODE.matcher(example.resultCode()).matches()) {
                candidates.add(new Candidate(example.resultCode(), Source.RESPONSE_EXAMPLE, example.block()));
            }
        }
        return candidates;
    }

    /** 샘플 열이 있는 필드 표에서 결과코드 행의 샘플과 설명을 본다 */
    private static void fieldTable(List<List<String>> rows, String block, List<Candidate> candidates) {
        int sample = -1;
        int description = -1;
        for (List<String> row : rows) {
            int sampleAt = indexOf(row, SAMPLE_LABELS);
            if (sampleAt >= 0) {
                sample = sampleAt;
                description = indexOf(row, DESCRIPTION_LABELS);
                continue;
            }
            if (sample < 0 || !hasResultCodeName(row, sample)) {
                continue;
            }
            if (sample < row.size()) {
                Matcher code = SAMPLE_CODE.matcher(row.get(sample).strip());
                if (code.matches()) {
                    candidates.add(new Candidate(code.group(1), Source.FIELD_TABLE, block));
                }
            }
            if (description >= 0 && description < row.size()) {
                Matcher described = DESCRIBED_CODE.matcher(row.get(description));
                while (described.find()) {
                    candidates.add(new Candidate(described.group(1), Source.FIELD_TABLE, block));
                }
            }
        }
    }

    /** 이름 열은 샘플 열 앞에 있다. 하위 항목을 오른쪽 칸에 들여 적는 표도 있어 앞쪽 칸을 모두 본다 */
    private static boolean hasResultCodeName(List<String> row, int sample) {
        for (int i = 0; i < Math.min(sample, row.size()); i++) {
            String name = IrConventions.cleanName(row.get(i));
            if (!name.isEmpty() && IrConventions.isResultCodeField(name)) {
                return true;
            }
        }
        return false;
    }

    /** 첫 머리칸이 에러코드·결과코드인 표에서 정상·NORMAL이 함께 적힌 행의 코드를 본다 */
    private static void errorTable(List<List<String>> rows, String block, List<Candidate> candidates) {
        if (rows.isEmpty() || indexOf(rows.get(0), SAMPLE_LABELS) >= 0) {
            return;
        }
        String first = rows.get(0).stream().filter(cell -> !cell.isBlank()).findFirst().orElse("");
        if (!CODE_TABLE_LABEL.matcher(normalize(first)).matches()) {
            return;
        }
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.isEmpty() || !CODE.matcher(row.get(0).strip()).matches()) {
                continue;
            }
            boolean normal = row.subList(1, row.size()).stream().anyMatch(cell -> NORMAL_MARK.matcher(cell).find());
            if (normal) {
                candidates.add(new Candidate(row.get(0).strip(), Source.ERROR_TABLE, block));
            }
        }
    }

    private static int indexOf(List<String> row, Set<String> labels) {
        for (int i = 0; i < row.size(); i++) {
            if (labels.contains(normalize(row.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String cell) {
        return cell.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
