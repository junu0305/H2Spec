package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 대조의 대상. Reader가 만든 Block 목록을 칸(문단 하나, 표의 칸 하나) 단위로 펼쳐 두고
 * LLM이 옮긴 글을 칸마다 찾는다.
 * <ul>
 *   <li>직렬화한 텍스트가 아니라 Block의 원래 글과 대조하고, 대소문자를 가린다. LLM이 옮긴 글은 직렬화가 바꾼 것
 *       ({@code <br>}, {@code \|})을 먼저 되돌린다({@link LlmText#restore}).</li>
 *   <li>칸의 원래 글과, 양쪽에서 공백·줄바꿈을 모두 지운 글을 둘 다 보고 어느 쪽에서든 맞으면 된다.
 *       지운 글은 칸 폭 때문에 꺾인 값을, 원래 글은 공백으로 나뉜 이름({@code pageNo pageSize})을 찾기 위해서다.</li>
 *   <li>찾은 자리의 앞뒤가 경계여야 맞은 것으로 본다. 진짜 주소의 앞부분, 다른 이름이나 코드의 일부가 맞지 않게 하기 위해서다.</li>
 * </ul>
 * 같은 글이 여러 칸에 있으면 칸 전체가 그 글인 곳, LLM이 근거로 댄 블록, 문서 순서로 고른다.
 * 찾은 뒤에는 LLM이 옮긴 글이 아니라 찾은 칸의 원래 글을 쓰므로, 칸을 고르는 순서가 정해져 있어야 같은 입력에서 같은 IR이 나온다.
 * 블록 번호는 {@code DocumentSerializer}와 같은 1부터의 공용 순번이라 근거({@code evidence})의 {@code T27}이 27번째 블록이다.
 */
final class SourceText {

    /** JSON 키를 감싸는 따옴표. 기관 문서는 워드프로세서가 바꾼 둥근 따옴표를 그대로 두기도 한다 */
    private static final String QUOTES = "\"“”'";
    /** 근거 블록 번호 ({@code T27}, {@code P23}) */
    private static final Pattern EVIDENCE_ID = Pattern.compile("([PpTt])\\s*(\\d+)");
    /** 응답 예제(JSON)의 최상위 키. 칸이 객체로 시작할 때만 본다. 표의 샘플 칸에 적은 구조 도식을 예제로 보지 않기 위해서다 */
    private static final Pattern JSON_ROOT = Pattern.compile("^\\s*\\{\\s*[\"“”']([^\"“”'\\s]+)[\"“”']\\s*:");
    /** XML 요소의 여는 태그. 선언({@code <?xml}), 주석, 닫는 태그, 빈 태그는 걸리지 않는다 */
    private static final Pattern XML_OPEN_TAG = Pattern.compile("<([A-Za-z_][\\w.:-]*)(?:\\s[^<>]*)?(?<!/)>");
    /** 응답 예제(JSON)에서 키와 그 값 */
    private static final Pattern JSON_MEMBER = Pattern.compile(
            "[\"“”']([A-Za-z_][A-Za-z0-9_]*)[\"“”']\\s*:\\s*[\"“”']?([A-Za-z0-9_-]+)");
    /** 응답 예제(XML)에서 값 하나만 담은 요소 */
    private static final Pattern XML_ELEMENT = Pattern.compile(
            "<([A-Za-z_][\\w.:-]*)(?:\\s[^<>]*)?>\\s*([A-Za-z0-9_-]+)\\s*</\\1\\s*>");
    /**
     * 에러코드 표에서 성공 행을 알아보는 표지 (소문자로 비교). "비정상"과 "abnormal"은 오류 행이라 앞 글자를 본다.
     * {@code NORMAL_SERVICE}처럼 밑줄로 이은 표기는 맞는다
     */
    private static final Pattern NORMAL_MARKER = Pattern.compile("(?<!비)정상|(?<![a-z])normal");

    private final List<Cell> cells = new ArrayList<>();
    private final List<List<Cell>> rows = new ArrayList<>();
    /** 블록 번호(1부터)마다 표인지 */
    private final List<Boolean> tableBlocks = new ArrayList<>();

    SourceText(List<Block> blocks) {
        int number = 0;
        for (Block block : blocks) {
            number++;
            if (block instanceof Block.Heading heading) {
                tableBlocks.add(false);
                cells.add(new Cell(cells.size(), number, false, heading.text()));
            } else if (block instanceof Block.Table table) {
                tableBlocks.add(true);
                for (List<String> sourceRow : table.rows()) {
                    List<Cell> row = new ArrayList<>();
                    for (String text : sourceRow) {
                        Cell cell = new Cell(cells.size(), number, true, text);
                        cells.add(cell);
                        row.add(cell);
                    }
                    rows.add(row);
                }
            } else {
                // DocumentSerializer와 같은 이유다. 블록 종류가 늘었는데 여기를 고치지 않으면 근거 번호가 어긋난다
                throw new IllegalArgumentException("대조할 수 없는 블록입니다: " + block);
            }
        }
    }

    // ---- 글 다듬기 ----

    /** 공백·줄바꿈(줄바꿈 없는 공백, 전각 공백 포함)을 모두 지운다 */
    static String stripSpaces(String text) {
        StringBuilder bare = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!isSpace(text.charAt(i))) {
                bare.append(text.charAt(i));
            }
        }
        return bare.toString();
    }

    /**
     * 근거 블록 번호를 블록 번호 집합으로 바꾼다. 종류(P·T)가 맞지 않거나 없는 번호는 버린다.
     *
     * @param evidence LLM이 돌려준 근거 ({@code T27})
     * @return 블록 번호 (1부터)
     */
    Set<Integer> evidenceBlocks(List<String> evidence) {
        Set<Integer> numbers = new HashSet<>();
        if (evidence == null) {
            return numbers;
        }
        for (String id : evidence) {
            Matcher matcher = EVIDENCE_ID.matcher(id == null ? "" : id);
            if (!matcher.find()) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            boolean table = Character.toUpperCase(matcher.group(1).charAt(0)) == 'T';
            if (number >= 1 && number <= tableBlocks.size() && tableBlocks.get(number - 1) == table) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    // ---- 찾기 ----

    /**
     * 이름이나 코드를 찾는다. 앞뒤가 영문자·숫자·{@code _}·{@code -}가 아니어야 한다.
     * 그래서 {@code pageNo}의 {@code No}, {@code INFO-00}의 {@code 00}은 맞지 않는다.
     *
     * @param name       찾을 글 (LLM 표기를 되돌린 것)
     * @param tablesOnly 표의 칸에서만 찾으면 true
     * @param evidence   근거 블록 번호. 같은 글이 여러 칸에 있을 때 먼저 고른다
     * @return 찾은 자리. 고르는 순서대로
     */
    List<Hit> names(String name, boolean tablesOnly, Set<Integer> evidence) {
        return search(name, Boundary.NAME, cell -> !tablesOnly || cell.table, evidence);
    }

    /**
     * 주소 후보를 찾는다. 앞은 보지 않고 뒤만 본다. 뒤가 영문자·숫자와 {@code _ - . /}, 여는 중괄호가 아니어야 하고,
     * 뒤의 {@code .}·{@code ,}는 그다음이 칸 끝이나 공백일 때만 경계다. 질의 문자열({@code ?})은 경계라서
     * {@code …/trafficLightList4?serviceKey=…}에서 {@code …/trafficLightList4}는 맞고 {@code …/trafficLightList}는 맞지 않는다.
     */
    List<Hit> urls(String candidate, Set<Integer> evidence) {
        return search(candidate, Boundary.URL, cell -> true, evidence);
    }

    /**
     * 응답 예제의 JSON 키나 XML 태그를 찾는다. JSON 키는 이름을 {@code "}·{@code “}·{@code ”}·{@code '} 중 하나로 감싸고
     * 뒤에 {@code :}가 오는 꼴이고, XML 태그는 {@code <이름} 뒤에 공백·{@code >}·{@code />}가 오는 꼴이라
     * 속성이 붙은 태그와 빈 태그({@code <TITLESUB/>})도 찾는다.
     */
    List<Hit> keys(String name, Set<Integer> evidence) {
        return search(name, Boundary.KEY, cell -> true, evidence);
    }

    /**
     * 영문 오퍼레이션명을 찾는다. rule 엔진처럼 표의 행에서 이름표({@link IrConventions#isOperationNameLabel}) 다음의 빈칸이 아닌 칸이고,
     * 그 칸의 원래 글 전체가 공백 없는 식별자로 그 이름일 때만 맞는다({@link IrConventions#operationName}). 목록 표·제목에만 있는 이름은
     * rule 엔진도 쓰지 않으므로 맞지 않는다. 공백을 지운 글로는 보지 않는다.
     */
    List<Hit> operationNames(String name, Set<Integer> evidence) {
        List<Hit> hits = new ArrayList<>();
        for (List<Cell> row : rows) {
            Cell label = null;
            for (Cell cell : row) {
                if (cell.text.isBlank()) {
                    // 병합으로 가려진 칸은 Reader가 비워 둔다. rule 엔진도 빈칸을 건너뛰고 다음 칸을 값으로 읽는다
                    continue;
                }
                if (label != null && IrConventions.isOperationNameLabel(label.text)
                        && name.equals(IrConventions.operationName(cell.text))) {
                    int start = cell.text.indexOf(name);
                    hits.add(new Hit(cell, start, start + name.length()));
                }
                label = cell;
            }
        }
        return rank(hits, evidence);
    }

    /**
     * 응답 필드 표의 결과코드 행에서 코드를 찾는다. 결과코드 필드 이름 칸이 있는 행의 다른 칸(샘플·설명)을 본다.
     *
     * @param code             성공코드 후보
     * @param resultCodeName   이름 칸의 다듬은 이름이 결과코드 필드인지
     * @param evidence         근거 블록 번호
     */
    List<Hit> codesInResultCodeRows(String code, Predicate<String> resultCodeName, Set<Integer> evidence) {
        List<Hit> hits = new ArrayList<>();
        for (List<Cell> row : rows) {
            Cell nameCell = row.stream()
                    .filter(cell -> resultCodeName.test(IrConventions.cleanName(cell.text)))
                    .findFirst().orElse(null);
            if (nameCell == null) {
                continue;
            }
            for (Cell cell : row) {
                Hit hit = cell == nameCell ? null : find(cell, code, Boundary.NAME);
                if (hit != null) {
                    hits.add(hit);
                    break;
                }
            }
        }
        return rank(hits, evidence);
    }

    /**
     * 에러코드 표에서 정상이나 NORMAL이 적힌 행의 코드를 찾는다. 응답 필드 표의 결과코드·결과메시지 행(샘플 "NORMAL SERVICE")은
     * 에러코드 표의 행이 아니라 그 행의 크기·구분 칸을 성공코드로 보지 않는다
     */
    List<Hit> codesInNormalRows(String code, Set<Integer> evidence) {
        List<Hit> hits = new ArrayList<>();
        for (List<Cell> row : rows) {
            boolean normal = row.stream()
                    .anyMatch(cell -> NORMAL_MARKER.matcher(cell.text.toLowerCase(Locale.ROOT)).find());
            boolean resultField = row.stream()
                    .anyMatch(cell -> IrConventions.isResultCodeOrMessageField(IrConventions.cleanName(cell.text)));
            if (!normal || resultField) {
                continue;
            }
            for (Cell cell : row) {
                Hit hit = find(cell, code, Boundary.NAME);
                if (hit != null) {
                    hits.add(hit);
                    break;
                }
            }
        }
        return rank(hits, evidence);
    }

    /**
     * 응답 예제에서 결과코드 키·태그의 값을 찾는다 ({@code "resultCode": "00"}, {@code <resultCode>0</resultCode>}).
     *
     * @param code           성공코드 후보
     * @param resultCodeName 키·태그 이름이 결과코드 필드인지
     * @param evidence       근거 블록 번호
     */
    List<Hit> resultCodeValues(String code, Predicate<String> resultCodeName, Set<Integer> evidence) {
        List<Hit> hits = new ArrayList<>();
        for (Cell cell : cells) {
            Hit hit = memberValue(cell, cell.text, null, code, resultCodeName);
            if (hit == null) {
                hit = memberValue(cell, cell.stripped, cell.origin, code, resultCodeName);
            }
            if (hit != null) {
                hits.add(hit);
            }
        }
        return rank(hits, evidence);
    }

    private static Hit memberValue(Cell cell, String text, int[] origin, String code, Predicate<String> resultCodeName) {
        for (Pattern pattern : List.of(JSON_MEMBER, XML_ELEMENT)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (resultCodeName.test(matcher.group(1)) && matcher.group(2).equals(code)) {
                    int start = matcher.start(2);
                    int end = matcher.end(2);
                    return origin == null ? new Hit(cell, start, end) : new Hit(cell, origin[start], origin[end - 1] + 1);
                }
            }
        }
        return null;
    }

    /**
     * 원문 응답 예제의 루트 이름. 칸 하나에 담긴 예제만 본다. JSON은 칸이 객체로 시작할 때 첫 키,
     * XML은 첫 요소가 하위 요소를 담고 닫히는 때 그 이름이다. 샘플 칸의 {@code <label>1</label>} 같은 값은 예제로 보지 않는다.
     *
     * @param evidence 근거 블록 번호. 있으면 그 블록에서만 찾는다(다른 오퍼레이션의 예제를 보지 않게)
     * @return 찾은 루트 이름 (문서 순서, 중복 없이)
     */
    List<String> responseExampleRoots(Set<Integer> evidence) {
        Set<String> roots = new LinkedHashSet<>();
        for (Cell cell : cells) {
            if (!evidence.isEmpty() && !evidence.contains(cell.block)) {
                continue;
            }
            Matcher json = JSON_ROOT.matcher(cell.text);
            if (json.find()) {
                roots.add(json.group(1));
            }
            String xml = xmlRoot(cell.text);
            if (xml != null) {
                roots.add(xml);
            }
        }
        return List.copyOf(roots);
    }

    private static String xmlRoot(String text) {
        Matcher open = XML_OPEN_TAG.matcher(text);
        if (!open.find()) {
            return null;
        }
        String name = open.group(1);
        int close = text.indexOf("</" + name, open.end());
        if (close < 0 || text.substring(open.end(), close).indexOf('<') < 0) {
            return null;
        }
        return name;
    }

    // ---- 칸 하나 ----

    private List<Hit> search(String candidate, Boundary boundary, Predicate<Cell> scope, Set<Integer> evidence) {
        List<Hit> hits = new ArrayList<>();
        if (candidate.isEmpty()) {
            return hits;
        }
        for (Cell cell : cells) {
            Hit hit = scope.test(cell) ? find(cell, candidate, boundary) : null;
            if (hit != null) {
                hits.add(hit);
            }
        }
        return rank(hits, evidence);
    }

    /** 칸에서 글을 찾는다. 원래 글에서 먼저 찾고, 없으면 양쪽 공백을 지운 글에서 찾아 원래 글의 자리로 되돌린다 */
    static Hit find(Cell cell, String candidate, Boundary boundary) {
        if (candidate.isEmpty()) {
            return null;
        }
        int at = indexOf(cell.text, candidate, boundary);
        if (at >= 0) {
            return new Hit(cell, at, at + candidate.length());
        }
        String bare = stripSpaces(candidate);
        int bareAt = bare.isEmpty() ? -1 : indexOf(cell.stripped, bare, boundary);
        if (bareAt < 0) {
            return null;
        }
        return new Hit(cell, cell.origin[bareAt], cell.origin[bareAt + bare.length() - 1] + 1);
    }

    private static int indexOf(String text, String candidate, Boundary boundary) {
        for (int at = text.indexOf(candidate); at >= 0; at = text.indexOf(candidate, at + 1)) {
            if (boundary.accepts(text, at, at + candidate.length())) {
                return at;
            }
        }
        return -1;
    }

    /** 칸 전체가 그 글인 곳, 근거 블록, 문서 순서로 늘어놓는다. 칸 전체가 같으면 LLM이 그 칸을 옮겼을 가능성이 가장 크다 */
    private static List<Hit> rank(List<Hit> hits, Set<Integer> evidence) {
        hits.sort(Comparator.comparingInt((Hit hit) -> hit.whole() ? 0 : 1)
                .thenComparingInt(hit -> evidence.contains(hit.cell().block) ? 0 : 1)
                .thenComparingInt(hit -> hit.cell().order));
        return hits;
    }

    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\ufeff';
    }

    /** 찾은 자리의 앞뒤가 경계인지 본다 */
    @FunctionalInterface
    interface Boundary {

        /** 이름·코드: 앞뒤가 영문자·숫자·{@code _}·{@code -}가 아니다 */
        Boundary NAME = (text, start, end) -> !isNameChar(text, start - 1) && !isNameChar(text, end);
        /** 주소: 뒤만 본다 */
        Boundary URL = (text, start, end) -> endsUrl(text, end);
        /** 응답 예제의 JSON 키나 XML 태그 */
        Boundary KEY = (text, start, end) -> isJsonKey(text, start, end) || isXmlTag(text, start, end);

        boolean accepts(String text, int start, int end);

        private static boolean isNameChar(String text, int index) {
            if (index < 0 || index >= text.length()) {
                return false;
            }
            char c = text.charAt(index);
            return isAsciiLetterOrDigit(c) || c == '_' || c == '-';
        }

        private static boolean endsUrl(String text, int end) {
            if (end >= text.length()) {
                return true;
            }
            char next = text.charAt(end);
            if (next == '.' || next == ',') {
                // 문장 끝의 마침표·쉼표는 주소가 아니다. 그 뒤에 글이 이어지면 주소의 일부(…/getList.do)다
                return end + 1 >= text.length() || isSpace(text.charAt(end + 1));
            }
            return !isAsciiLetterOrDigit(next) && "_-/{".indexOf(next) < 0;
        }

        private static boolean isJsonKey(String text, int start, int end) {
            if (start == 0 || QUOTES.indexOf(text.charAt(start - 1)) < 0
                    || end >= text.length() || QUOTES.indexOf(text.charAt(end)) < 0) {
                return false;
            }
            int colon = end + 1;
            while (colon < text.length() && isSpace(text.charAt(colon))) {
                colon++;
            }
            return colon < text.length() && text.charAt(colon) == ':';
        }

        private static boolean isXmlTag(String text, int start, int end) {
            if (start == 0 || text.charAt(start - 1) != '<' || end >= text.length()) {
                return false;
            }
            char next = text.charAt(end);
            return isSpace(next) || next == '>'
                    || (next == '/' && end + 1 < text.length() && text.charAt(end + 1) == '>');
        }

        private static boolean isAsciiLetterOrDigit(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
        }
    }

    /**
     * 칸 하나. 문단은 칸 하나, 표는 칸마다 하나다.
     */
    static final class Cell {
        /** 문서 안 칸 순번 (0부터). 같은 출처의 성공코드 후보를 문서 순서로 고를 때 쓴다 */
        final int order;
        /** 블록 번호 (1부터, {@code DocumentSerializer}와 같다) */
        final int block;
        final boolean table;
        /** 원래 글 */
        final String text;
        /** 공백·줄바꿈을 모두 지운 글 */
        final String stripped;
        /** 지운 글의 자리 → 원래 글의 자리 */
        final int[] origin;

        Cell(int order, int block, boolean table, String text) {
            this.order = order;
            this.block = block;
            this.table = table;
            this.text = text;
            StringBuilder bare = new StringBuilder(text.length());
            int[] map = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                if (!isSpace(text.charAt(i))) {
                    map[bare.length()] = i;
                    bare.append(text.charAt(i));
                }
            }
            this.stripped = bare.toString();
            this.origin = map;
        }

        /** @return 근거 번호 꼴의 블록 번호 ({@code T27}) */
        String blockId() {
            return (table ? "T" : "P") + block;
        }
    }

    /**
     * 찾은 자리.
     *
     * @param cell  찾은 칸
     * @param start 원래 글에서의 시작 자리
     * @param end   원래 글에서의 끝 자리 (포함하지 않음). 공백을 지운 글에서 찾았으면 사이의 공백·줄바꿈을 담는다
     */
    record Hit(Cell cell, int start, int end) {

        /** @return 찾은 자리의 원래 글. 칸 폭 때문에 꺾인 이름은 줄바꿈을 담는다 */
        String span() {
            return cell.text.substring(start, end);
        }

        /** @return 칸의 글 전체(공백 제외)가 찾은 글이면 true */
        boolean whole() {
            return stripSpaces(span()).equals(cell.stripped);
        }
    }
}
