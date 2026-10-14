package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.Block;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Reader가 만든 {@link Block} 목록을 LLM에 넘길 텍스트로 바꾼다.
 * <pre>{@code
 * [P23] 요청 URL
 * [P24] https://open.jejudatahub.net/api/proxy/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}?{params(key=value)}
 * [P26] Request Parameters
 * [T27] 표 7행 4열
 * | Name | Description | Type | Note |
 * | searchDate | 분석일(필수) | string | YYYYMMDD |
 * }</pre>
 * 블록 번호는 문단({@code P})과 표({@code T})가 함께 쓰는 1부터의 순번이라, {@code T27}은 목록의 27번째 블록이다.
 * LLM은 근거({@code evidence})를 이 번호로 돌려준다.
 * <p>
 * 원문 대조는 이 텍스트가 아니라 Block의 원래 글로 하므로, 여기서는 블록과 표의 행이 한 줄씩 남도록 줄바꿈과 칸 구분자만 바꾼다.
 * 목차·개정이력도 지우지 않는다. 걸러내는 규칙을 다시 만들지 않기 위해서다.
 * 같은 Block 목록은 항상 바이트 단위로 같은 텍스트가 된다. 결과 캐시 키가 이것에 기댄다.
 */
public class DocumentSerializer {

    /**
     * 기본 길이 상한. {@link String#length()}로 센 글자 수다.
     * 샘플 DOCX 12개를 직렬화하면 9천~3만 6천 자이고 corpus HWP는 모두 7천 자 아래라, 가장 긴 문서의 다섯 배쯤인 20만 자에
     * 실제 문서가 걸릴 일은 드물다. 어림잡아(3만 6천 자에 약 2만 토큰) 약 11만 토큰이고, 토크나이저에 따라 30%를 더 써도
     * 시스템 프롬프트와 출력 상한(12만 8천)을 더해 Claude Sonnet 5.5·Opus 5.5의 컨텍스트(100만 토큰)에 넉넉히 들어간다.
     * 나눠 불러도 호출마다 문서 전체를 보내므로, 이보다 긴 문서는 보낼 크기가 아니다.
     */
    public static final int DEFAULT_MAX_LENGTH = 200_000;

    // 줄 구조를 깨는 줄바꿈은 종류(\r\n, \r, U+2028 등)와 상관없이 모두 바꾼다. \R은 \r\n을 하나로 센다
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final int maxLength;

    /** 기본 길이 상한({@link #DEFAULT_MAX_LENGTH})을 쓴다. */
    public DocumentSerializer() {
        this(DEFAULT_MAX_LENGTH);
    }

    /** @param maxLength 직렬화한 텍스트의 최대 글자 수. 넘으면 자르지 않고 {@link DocumentTooLargeException}을 던진다 */
    public DocumentSerializer(int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("길이 상한은 1 이상이어야 합니다: " + maxLength);
        }
        this.maxLength = maxLength;
    }

    /**
     * Block 목록을 텍스트로 바꾼다. 줄마다 줄바꿈 문자 하나({@code \n})로 끝난다.
     *
     * @param blocks Reader가 읽은 문서 순서 그대로의 블록
     * @return 블록마다 번호를 붙인 텍스트
     * @throws DocumentTooLargeException 텍스트가 길이 상한을 넘을 때. 조용히 자르면 뒤쪽 필드가 빠지기 때문이다
     */
    public String serialize(List<Block> blocks) {
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (Block block : blocks) {
            number++;
            if (block instanceof Block.Heading heading) {
                text.append("[P").append(number).append("] ").append(oneLine(heading.text())).append('\n');
            } else if (block instanceof Block.Table table) {
                appendTable(text, number, table.rows());
            } else {
                // 블록 종류가 늘었는데 여기를 고치지 않으면 그 블록이 조용히 빠진다
                throw new IllegalArgumentException("직렬화할 수 없는 블록입니다: " + block);
            }
        }
        if (text.length() > maxLength) {
            throw new DocumentTooLargeException(text.length(), maxLength);
        }
        return text.toString();
    }

    /**
     * 열 수는 가장 긴 행의 칸 수다. 짧은 행은 빈 칸을 채우지 않는다. 칸의 열 위치는 Reader가 이미 맞춰 두었고,
     * 모자란 칸이 행의 어느 쪽에서 빠졌는지는 여기서 알 수 없기 때문이다.
     */
    private void appendTable(StringBuilder text, int number, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        // 숫자는 String.format 대신 append로 붙인다. format은 기본 로캘에 따라 다른 숫자 모양을 낼 수 있다
        text.append("[T").append(number).append("] 표 ").append(rows.size()).append("행 ")
                .append(columns).append("열\n");
        for (List<String> row : rows) {
            text.append('|');
            for (String cell : row) {
                text.append(' ').append(escapeCell(cell)).append(" |");
            }
            text.append('\n');
        }
    }

    // 칸 안의 |는 칸 구분자와 헷갈리지 않게 \|로 바꾼다. 원문 대조 전에 검증 쪽이 되돌린다
    private static String escapeCell(String text) {
        return oneLine(text.replace("|", "\\|"));
    }

    private static String oneLine(String text) {
        return LINE_BREAK.matcher(text).replaceAll("<br>");
    }
}
