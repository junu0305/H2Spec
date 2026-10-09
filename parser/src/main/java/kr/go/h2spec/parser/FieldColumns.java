package kr.go.h2spec.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 필드 명세 표의 머리행에서 찾은 열 위치.
 * 표준 기술문서는 "항목명(영문)/항목명(국문)/항목크기/항목구분/샘플데이터/항목설명"을 쓰지만
 * 기관마다 "요청변수/설명/필수여부", "Name/Description/Type/Note", "항목명/타입/필수/값 샘플/항목설명"처럼
 * 열 이름과 수가 다르다. 머리행의 라벨을 표준 열로 맞추고, 데이터 행을 표준 열 순서로 옮긴다.
 */
final class FieldColumns {

    static final int NAME = 0;
    static final int KOR_NAME = 1;
    static final int SIZE = 2;
    static final int REQUIRED = 3;
    static final int SAMPLE = 4;
    static final int DESCRIPTION = 5;
    static final int TYPE = 6;
    /** 표준 열 수. IrAssembler가 받는 행의 폭이다 */
    static final int COUNT = 7;
    /** 표준 기술문서 필드 표의 열 수 (항목명(영문)~항목설명) */
    static final int STANDARD_WIDTH = 6;

    private static final List<String> NAME_LABELS = List.of("항목명(영문)", "항목(영문)", "영문항목명", "항목영문명",
            "항목명영문", "항목명(영문명)", "영문명", "영문항목", "변수명", "요청변수", "응답변수", "출력변수", "입력변수",
            "요청변수명", "응답변수명", "출력변수명", "파라미터", "파라미터명", "요청파라미터", "응답파라미터", "인자명",
            "필드명", "데이터필드명", "속성명", "요소명", "name", "parameter", "parametername", "paramname", "field",
            "fieldname", "element");
    /** 목록에 없는 영문명·국문명 라벨 ("항목명(영어)", "출력항목명(영문)", "변수명(한글)") */
    private static final Pattern ENGLISH_NAME_LABEL = Pattern.compile("^.{0,6}(항목|변수|필드|파라미터|요소)명?\\(?(영문|영어)(명)?\\)?$");
    private static final Pattern KOREAN_NAME_LABEL = Pattern.compile("^.{0,6}(항목|변수|필드|파라미터|요소)명?\\(?(국문|한글)(명)?\\)?$");
    /** 국문명 열이 따로 있을 때만 영문명 열로 보는 라벨 */
    private static final String GENERIC_NAME_LABEL = "항목명";
    private static final List<String> KOR_NAME_LABELS = List.of("항목명(국문)", "항목(국문)", "국문항목명", "항목국문명",
            "항목명국문", "항목명(국문명)", "항목명(한글)", "국문명", "한글명", "한글항목명", "변수설명", "간략설명");
    private static final List<String> SIZE_LABELS = List.of("항목크기", "항목크기(bytes)", "항목크기(byte)", "크기",
            "길이", "최대길이", "사이즈", "자릿수", "size", "length", "maxlength");
    private static final List<String> REQUIRED_LABELS = List.of("항목구분", "항목구분*", "필수", "필수여부", "필수구분",
            "필수유무", "필수(y/n)", "필수여부(y/n)", "필수/선택", "필수선택", "선택", "선택여부", "옵션", "required",
            "mandatory");
    private static final List<String> SAMPLE_LABELS = List.of("샘플데이터", "샘플", "샘플값", "값샘플", "예시", "예시값",
            "예제", "예제값", "sample", "sampledata", "example");
    /** 필수 여부 라벨이 따로 없을 때만 필수 여부로 쓰는 열 ("크기 | 구분 | 샘플데이터"의 구분: 1(필수)/0(선택)) */
    private static final String GENERIC_REQUIRED_LABEL = "구분";
    /** 샘플 열이 없을 때만 샘플로 쓰는 기본값 열 */
    private static final List<String> DEFAULT_LABELS = List.of("기본값", "default", "defaultvalue");
    private static final List<String> DESCRIPTION_LABELS = List.of("항목설명", "설명", "상세설명", "상세내용", "내용",
            "description", "desc", "설명및예시", "설명및사용법", "항목설명및예시");
    /** 설명이 따로 있으면 버리는 보조 설명 열 */
    private static final List<String> NOTE_LABELS = List.of("비고", "note", "notes", "etc", "remark", "remarks");
    private static final List<String> TYPE_LABELS = List.of("타입", "형식", "데이터타입", "데이터형식", "자료형",
            "자료타입", "데이터형", "항목유형", "type", "datatype");
    /** 머리행의 이름 라벨이 그 표의 구간을 알려주는 경우 */
    private static final Map<String, SpecLabels.Section> NAME_LABEL_SECTIONS = Map.of(
            "요청변수", SpecLabels.Section.REQUEST, "요청변수명", SpecLabels.Section.REQUEST,
            "입력변수", SpecLabels.Section.REQUEST, "요청파라미터", SpecLabels.Section.REQUEST,
            "응답변수", SpecLabels.Section.RESPONSE, "응답변수명", SpecLabels.Section.RESPONSE,
            "출력변수", SpecLabels.Section.RESPONSE, "출력변수명", SpecLabels.Section.RESPONSE,
            "응답파라미터", SpecLabels.Section.RESPONSE);
    /** 하위 항목을 들여쓰는 표지 (└ name, ㄴname, - name, + name) */
    private static final Pattern TREE_MARKER = Pattern.compile("^[\\s└ㄴ\\-‐–+·ㆍ•▶>*\\u2514\\u251c\\u2502]+");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]*");
    /** 낙타 표기 이름이 공백으로 갈라진 꼴: 소문자·숫자 뒤 공백 다음에 대문자가 온다 */
    private static final Pattern SPLIT_CAMEL_CASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*[a-z0-9]( [A-Z][A-Za-z0-9_]*)+");
    /** 이름 앞뒤에 붙은 꺾쇠·구두점 */
    private static final Pattern NAME_PUNCTUATION = Pattern.compile("^[<(\"'`]+|[>)\"'`,;:.]+$");
    /** 영문 이름 뒤에 국문 설명을 괄호로 붙인 칸 ("numOfRows(한 페이지 결과 수)") */
    private static final Pattern NAME_WITH_NOTE = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$\\-]*)\\s*[(（].*$");

    /**
     * 요청·응답을 한 표에 담고 "구분" 열에 In/Out을 적는 표에서 영문명을 담는 라벨
     * ("구분 | 코드 | 코드명 | 샘플데이터"). 코드표와 모양이 같아 In/Out 값이 있을 때만 필드 표로 본다.
     */
    private static final List<String> DIRECTIONAL_NAME_LABELS = List.of("코드", "항목", "항목코드", "요소", "요소명");
    private static final List<String> DIRECTIONAL_KOR_LABELS = List.of("코드명", "항목명", "요소설명");
    private static final Map<String, SpecLabels.Section> DIRECTIONS = Map.ofEntries(
            Map.entry("in", SpecLabels.Section.REQUEST), Map.entry("input", SpecLabels.Section.REQUEST),
            Map.entry("입력", SpecLabels.Section.REQUEST), Map.entry("요청", SpecLabels.Section.REQUEST),
            Map.entry("request", SpecLabels.Section.REQUEST), Map.entry("out", SpecLabels.Section.RESPONSE),
            Map.entry("output", SpecLabels.Section.RESPONSE), Map.entry("출력", SpecLabels.Section.RESPONSE),
            Map.entry("응답", SpecLabels.Section.RESPONSE), Map.entry("response", SpecLabels.Section.RESPONSE));
    /** 필수 여부 열이 없는 표에서 설명에 붙여 적는 필수 표지 ("분석일(필수)") */
    private static final String REQUIRED_NOTE = "(필수)";

    private final int[] index;
    private final SpecLabels.Section section;
    /** In/Out을 적는 열. 없으면 -1 */
    private final int direction;

    private FieldColumns(int[] index, SpecLabels.Section section, int direction) {
        this.index = index;
        this.section = section;
        this.direction = direction;
    }

    /**
     * 행이 필드 표의 머리행이면 열 위치를 돌려준다. 아니면 null.
     * 영문명 열이 있어야 하고, 라벨로 알아본 칸이 영문명 외에 둘 이상이거나
     * 칸이 모두 라벨이어야 한다(응답변수/설명 같은 두 칸짜리 표).
     */
    static FieldColumns detect(List<String> cells) {
        int[] index = new int[COUNT];
        Arrays.fill(index, -1);
        List<Integer> descriptions = new ArrayList<>();
        int note = -1;
        int fallbackSample = -1;
        int genericName = -1;
        int genericRequired = -1;
        int recognized = 0;
        int nonBlank = 0;
        for (int i = 0; i < cells.size(); i++) {
            String label = SpecLabels.normalize(cells.get(i));
            if (label.isEmpty()) {
                continue;
            }
            nonBlank++;
            int role = roleOf(label);
            if (role == NAME && index[NAME] < 0) {
                index[NAME] = i;
            } else if (label.equals(GENERIC_NAME_LABEL) && genericName < 0) {
                genericName = i;
            } else if (role == DESCRIPTION) {
                descriptions.add(i);
            } else if (role >= 0 && role != NAME && index[role] < 0) {
                index[role] = i;
            } else if (NOTE_LABELS.contains(label) && note < 0) {
                note = i;
            } else if (DEFAULT_LABELS.contains(label) && fallbackSample < 0) {
                fallbackSample = i;
            } else if (label.equals(GENERIC_REQUIRED_LABEL) && genericRequired < 0) {
                genericRequired = i;
            } else {
                continue;
            }
            recognized++;
        }
        if (index[NAME] < 0) {
            index[NAME] = genericName;
        } else if (genericName >= 0 && index[KOR_NAME] < 0) {
            // "변수명 | 항목명" — 이름 라벨이 따로 있으면 항목명은 국문명이다
            index[KOR_NAME] = genericName;
        }
        if (index[NAME] < 0) {
            return directional(cells, genericRequired);
        }
        assignDescriptions(index, descriptions, note);
        if (index[SAMPLE] < 0) {
            index[SAMPLE] = fallbackSample;
        }
        if (index[REQUIRED] < 0) {
            index[REQUIRED] = genericRequired;
        }
        int others = recognized - 1;
        boolean allLabels = recognized == nonBlank && nonBlank >= 2;
        if (others < 2 && !allLabels) {
            return null;
        }
        if (recognized * 10 < nonBlank * 6) {
            return null;
        }
        String nameLabel = SpecLabels.normalize(cells.get(index[NAME]));
        return new FieldColumns(index, NAME_LABEL_SECTIONS.get(nameLabel), -1);
    }

    /** "구분 | 코드 | 코드명 | 샘플데이터"처럼 구분 열이 요청/응답을 가르는 머리행. 아니면 null */
    private static FieldColumns directional(List<String> cells, int directionColumn) {
        if (directionColumn < 0) {
            return null;
        }
        int[] index = new int[COUNT];
        Arrays.fill(index, -1);
        for (int i = 0; i < cells.size(); i++) {
            String label = SpecLabels.normalize(cells.get(i));
            if (DIRECTIONAL_NAME_LABELS.contains(label) && index[NAME] < 0) {
                index[NAME] = i;
            } else if (DIRECTIONAL_KOR_LABELS.contains(label) && index[KOR_NAME] < 0) {
                index[KOR_NAME] = i;
            } else if (SAMPLE_LABELS.contains(label) && index[SAMPLE] < 0) {
                index[SAMPLE] = i;
            } else if (DESCRIPTION_LABELS.contains(label) && index[DESCRIPTION] < 0) {
                index[DESCRIPTION] = i;
            } else if (TYPE_LABELS.contains(label) && index[TYPE] < 0) {
                index[TYPE] = i;
            } else if (SIZE_LABELS.contains(label) && index[SIZE] < 0) {
                index[SIZE] = i;
            }
        }
        if (index[NAME] < 0 || index[KOR_NAME] < 0) {
            return null;
        }
        return new FieldColumns(index, null, directionColumn);
    }

    /** 머리행 없이 표준 열 순서(항목명(영문)/항목명(국문)/항목크기/항목구분/샘플데이터/항목설명)로 읽는 열 위치 */
    static FieldColumns positional() {
        int[] index = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            index[i] = i < STANDARD_WIDTH ? i : -1;
        }
        return new FieldColumns(index, null, -1);
    }

    /** 영문 식별자꼴 이름인지 ("stationName", "RESULT_CODE", "item.name") */
    static boolean isIdentifier(String name) {
        return IDENTIFIER.matcher(name).matches();
    }

    /** 구분 열의 In/Out 값이 있어야만 필드 표로 믿을 수 있는 머리행인지 */
    boolean needsDirection() {
        return direction >= 0;
    }

    /** 데이터 행의 구분 열이 알려주는 구간. In/Out 값이 없으면 null */
    SpecLabels.Section direction(List<String> cells) {
        if (direction < 0 || direction >= cells.size()) {
            return null;
        }
        // "\\Out"처럼 오타 기호가 붙은 값이 있어 글자만 남겨 비교한다
        return DIRECTIONS.get(SpecLabels.normalize(cells.get(direction)).replaceAll("[^a-z가-힣]", ""));
    }

    /**
     * 설명 라벨이 둘이면 앞의 것이 국문명이다("항목명 | 항목설명 | … | 항목설명", "항목설명 | … | 상세내용").
     * 비고 열은 설명 열이 없을 때만 설명으로 쓴다.
     */
    private static void assignDescriptions(int[] index, List<Integer> descriptions, int note) {
        if (descriptions.size() >= 2 && index[KOR_NAME] < 0) {
            index[KOR_NAME] = descriptions.get(0);
            index[DESCRIPTION] = descriptions.get(descriptions.size() - 1);
        } else if (!descriptions.isEmpty()) {
            index[DESCRIPTION] = descriptions.get(0);
        } else {
            index[DESCRIPTION] = note;
        }
    }

    private static int roleOf(String label) {
        if (NAME_LABELS.contains(label) || ENGLISH_NAME_LABEL.matcher(label).matches()) {
            return NAME;
        }
        if (KOR_NAME_LABELS.contains(label) || KOREAN_NAME_LABEL.matcher(label).matches()) {
            return KOR_NAME;
        }
        if (SIZE_LABELS.contains(label)) {
            return SIZE;
        }
        if (REQUIRED_LABELS.contains(label)) {
            return REQUIRED;
        }
        if (SAMPLE_LABELS.contains(label)) {
            return SAMPLE;
        }
        if (DESCRIPTION_LABELS.contains(label)) {
            return DESCRIPTION;
        }
        if (TYPE_LABELS.contains(label)) {
            return TYPE;
        }
        return -1;
    }

    /** 머리행의 이름 라벨이 알려주는 구간. 요청변수/응답변수처럼 구간을 담은 라벨이 아니면 null */
    SpecLabels.Section section() {
        return section;
    }

    /**
     * 데이터 행을 표준 열 순서로 옮긴다. 이름 칸이 비면 들여쓴 자식 행으로 보고
     * 다음 열 직전까지 훑어 실제 이름을 찾는다. 이름을 못 찾으면 null.
     */
    List<String> canonical(List<String> cells) {
        List<String> row = new ArrayList<>(COUNT);
        for (int column : index) {
            row.add(column >= 0 && column < cells.size() ? cells.get(column).strip() : "");
        }
        int nameColumn = nameColumn(cells);
        String name = cleanName(nameColumn < 0 ? "" : cells.get(nameColumn));
        if (name.isEmpty()) {
            return null;
        }
        row.set(NAME, name);
        if (index[REQUIRED] < 0 && (row.get(KOR_NAME) + row.get(DESCRIPTION)).contains(REQUIRED_NOTE)) {
            row.set(REQUIRED, "1");
        }
        return row;
    }

    /**
     * 실제 이름이 놓인 열. 이름 열부터 다음 표준 열 직전까지가 들여쓰기 칸이다. 자식 행은 앞 칸을 비우고
     * 한두 칸 들여 적고, "meta | doc_id"처럼 부모 이름과 첫 자식 이름을 한 행에 함께 적는 문서도 있어
     * 가장 오른쪽에 적힌 이름을 필드로 본다. 못 찾으면 -1.
     */
    private int nameColumn(List<String> cells) {
        int limit = cells.size();
        for (int column : index) {
            if (column > index[NAME] && column < limit) {
                limit = column;
            }
        }
        int first = -1;
        int identifier = -1;
        for (int i = index[NAME]; i < limit; i++) {
            if (cells.get(i).isBlank()) {
                continue;
            }
            if (first < 0) {
                first = i;
            }
            if (IDENTIFIER.matcher(cleanName(cells.get(i))).matches()) {
                identifier = i;
            }
        }
        // 들여쓰기 칸에 국문을 잘못 적은 행도 있어, 영문 식별자 가운데 가장 오른쪽 것을 고르고 없으면 첫 칸을 쓴다
        return identifier >= 0 ? identifier : first;
    }

    /**
     * 데이터 행의 들여쓰기 깊이. 이름이 이름 열에서 몇 칸 오른쪽에 있는지, 이름 열에 있으면
     * 트리 표지(└, ㄴ)가 붙었는지로 잰다. 바로 다음 행이 더 깊으면 그 행은 하위 필드를 감싸는 컨테이너다.
     */
    int depth(List<String> cells) {
        int column = nameColumn(cells);
        if (column < 0) {
            return 0;
        }
        if (column == index[NAME]) {
            return TREE_MARKER.matcher(cells.get(column)).lookingAt() ? 1 : 0;
        }
        return column - index[NAME];
    }

    /**
     * 트리 표지와 이름 뒤에 덧붙인 설명("numOfRows\n(한 페이지 결과 수)"),
     * 오타로 남은 꺾쇠·구두점("mnab3Mnud4DlpnCnt>")을 떼어 낸다.
     */
    static String cleanName(String raw) {
        String name = TREE_MARKER.matcher(raw.strip()).replaceAll("");
        // 칸 폭 때문에 식별자가 여러 줄로 꺾인 칸("TEST_EVAL_IS\nUE_DD", "pm\n25Value")은 잇고,
        // 그 밖의 다음 줄은 덧붙인 설명("(한 페이지 결과 수)")이라 버린다
        name = joinWrappedIdentifier(name);
        int lineBreak = name.indexOf('\n');
        if (lineBreak > 0) {
            name = name.substring(0, lineBreak);
        }
        name = NAME_PUNCTUATION.matcher(name.strip()).replaceAll("");
        Matcher withNote = NAME_WITH_NOTE.matcher(name);
        name = withNote.matches() ? withNote.group(1) : name;
        // 칸 폭 때문에 낙타 표기 이름 가운데가 공백으로 꺾인 칸("data Type", "ndrgItmIcsn TpCd")은 잇는다.
        // "relevant departments"처럼 소문자로 이어지는 이름은 문서가 적은 그대로 둔다
        return SPLIT_CAMEL_CASE.matcher(name).matches() ? name.replace(" ", "") : name;
    }

    /**
     * 첫 줄부터 식별자 조각만 담은 줄들을 잇는다. 숫자만 있는 줄은 이름이 아니라 값이라 잇지 않는다.
     */
    private static String joinWrappedIdentifier(String name) {
        String[] lines = name.split("\\s*\\n\\s*");
        if (lines.length < 2 || !lines[0].matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return name;
        }
        StringBuilder joined = new StringBuilder(lines[0]);
        int used = 1;
        while (used < lines.length && lines[used].matches("[A-Za-z0-9_]+") && !lines[used].matches("\\d+")) {
            joined.append(lines[used++]);
        }
        if (used == 1) {
            return name;
        }
        for (int i = used; i < lines.length; i++) {
            joined.append('\n').append(lines[i]);
        }
        return joined.toString();
    }

    /** 다른 표의 열 위치를 이어받을 수 있는지 볼 때 쓰는 열 수 */
    int width() {
        int max = 0;
        for (int column : index) {
            max = Math.max(max, column + 1);
        }
        return max;
    }
}
