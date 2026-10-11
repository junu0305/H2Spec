package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.proxy.ExampleParser.Format;
import kr.go.h2spec.eval.proxy.ExampleParser.Parsed;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 문서 블록에서 응답 예제를 찾는다. 예제는 "응답메시지" 칸 다음 칸, "Response Body" 문단 다음의 한 칸짜리 표,
 * 여러 문단에 한 줄씩 적은 XML처럼 놓이는 자리가 제각각이다. 그래서 칸과 문단을 모두 예제로 읽어 보고,
 * 바로 앞의 글(이름표)로 요청 예제와 에러 응답 형식을 걸러낸다. 대리 지표용 휴리스틱이다.
 */
final class ResponseExamples {

    /** 에러 응답 형식을 보이는 이름표 ("※ 공공데이터포털에서 출력되는 오류메세지는 XML로만 출력되며") */
    private static final Pattern ERROR_LABEL = Pattern.compile("(?i)오류|에러|error");
    private static final Pattern REQUEST_LABEL = Pattern.compile("(?i)요청|request|호출");
    private static final Pattern RESPONSE_LABEL = Pattern.compile("(?i)응답|response|결과|출력");
    /** 공공데이터포털 게이트웨이의 에러 응답 루트와 머리 */
    private static final Set<String> GATEWAY_ERROR_NODES = Set.of("openapi_serviceresponse", "cmmmsgheader");
    private static final Pattern ERROR_CODE = Pattern.compile("(?i).*err.*");

    private ResponseExamples() {
    }

    /**
     * 응답 예제 하나.
     *
     * @param block      예제가 시작하는 블록 번호 (직렬화와 같은 P23·T27 꼴)
     * @param format     형식
     * @param document   구조. 자식이 루트다
     * @param error      에러 응답 형식의 예제인지. 대리 지표에서 뺀다
     * @param resultCode 결과코드 필드(resultCode, RESULT_CODE 등)의 값. 없으면 null
     */
    record ResponseExample(String block, Format format, ExampleNode document, boolean error, String resultCode) {

        /** @return 루트 이름 (JSON은 최상위 키) */
        List<String> roots() {
            return List.copyOf(document.children().keySet());
        }
    }

    /** 칸이나 문단 하나. 같은 표의 칸끼리, 표 사이에 끼지 않은 문단끼리 같은 묶음이다 */
    private record Line(String text, String block, String group) {
    }

    /**
     * 응답 예제를 문서 순서로 찾는다. 요청 예제는 빼고, 에러 응답 형식은 표시해서 담는다.
     *
     * @param blocks 문서 블록
     * @return 응답 예제
     */
    static List<ResponseExample> find(List<Block> blocks) {
        List<Line> lines = lines(blocks);
        List<ResponseExample> examples = new ArrayList<>();
        String label = "";
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            Parsed parsed = ExampleParser.parse(line.text());
            int used = 1;
            if (parsed == null && ExampleParser.startsExample(line.text())) {
                // 한 줄씩 다른 문단·칸에 적은 예제는 이어 붙여 읽는다
                StringBuilder joined = new StringBuilder(line.text());
                int next = i + 1;
                while (next < lines.size() && lines.get(next).group().equals(line.group())
                        && ExampleParser.continuesExample(lines.get(next).text())) {
                    joined.append('\n').append(lines.get(next).text());
                    next++;
                }
                parsed = ExampleParser.parse(joined.toString());
                used = next - i;
            }
            if (parsed == null) {
                label = line.text();
                continue;
            }
            i += used - 1;
            if (isRequest(label)) {
                continue;
            }
            String resultCode = resultCode(parsed.document());
            boolean error = ERROR_LABEL.matcher(label).find() || isGatewayError(parsed.document())
                    || (resultCode != null && ERROR_CODE.matcher(resultCode).matches());
            examples.add(new ResponseExample(line.block(), parsed.format(), parsed.document(), error, resultCode));
        }
        return examples;
    }

    private static List<Line> lines(List<Block> blocks) {
        List<Line> lines = new ArrayList<>();
        int paragraphGroup = 0;
        for (int b = 0; b < blocks.size(); b++) {
            Block block = blocks.get(b);
            if (block instanceof Block.Heading heading) {
                lines.add(new Line(heading.text(), "P" + (b + 1), "P" + paragraphGroup));
            } else if (block instanceof Block.Table table) {
                paragraphGroup++;
                for (List<String> row : table.rows()) {
                    for (String cell : row) {
                        if (!cell.isBlank()) {
                            lines.add(new Line(cell, "T" + (b + 1), "T" + (b + 1)));
                        }
                    }
                }
            }
        }
        return lines;
    }

    /** "요청메시지"처럼 응답이라는 말 없이 요청만 가리키는 이름표 */
    private static boolean isRequest(String label) {
        return REQUEST_LABEL.matcher(label).find() && !RESPONSE_LABEL.matcher(label).find();
    }

    private static boolean isGatewayError(ExampleNode document) {
        return document.find(name -> GATEWAY_ERROR_NODES.contains(name.toLowerCase(Locale.ROOT))) != null;
    }

    /** 결과코드 필드의 값. 이름 판정은 rule 엔진과 같은 규칙(resultCode·RESULT_CODE·result_code)을 쓴다 */
    private static String resultCode(ExampleNode document) {
        ExampleNode node = document.find(IrConventions::isResultCodeField);
        return node == null || !node.isLeaf() ? null : node.text();
    }
}
