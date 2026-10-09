package kr.go.h2spec.parser;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기관마다 다르게 적는 구간 제목·정보 표 키를 비교 가능한 꼴로 맞추고 역할을 판별한다.
 * 표준 기술문서는 "요청 메시지 명세"라 적지만 실제 문서는 "요청메시지 명세", "- 요청메세지 명세",
 * "요청 변수(Request Parameters)", "입력값 명세"처럼 띄어쓰기·맞춤법·용어가 제각각이다.
 * 모든 비교는 공백을 지우고 영문을 소문자로 바꾼 꼴로 한다.
 */
final class SpecLabels {

    /** 헤딩이나 표 안 구간 행이 알려주는 문서 구간 */
    enum Section {
        /** 상세기능(오퍼레이션) 정보 표가 오는 구간 */
        INFO,
        REQUEST,
        RESPONSE,
        /** 요청/응답 메시지 예제. 표가 와도 필드 명세가 아니다 */
        EXAMPLE,
        /** 에러 코드 정리 */
        ERROR,
        /** 서비스 개요·오퍼레이션 목록처럼 오퍼레이션 명세 밖의 구간 */
        OTHER
    }

    private static final Pattern NO_BREAK_SPACE = Pattern.compile("[\\u00a0\\u2007\\u202f\\ufeff]");
    /** 이보다 긴 문단은 본문 문장으로 보고 구간 제목으로 쓰지 않는다 */
    private static final int MAX_HEADING_LENGTH = 60;
    /** 문장으로 끝나는 문단 (…한다, …함, …됨, …임, …음, 마침표). "개요"처럼 요로 끝나는 제목이 있어 요는 넣지 않는다 */
    private static final Pattern SENTENCE_ENDING = Pattern.compile("(다|함|됨|임|음|것)[.!]?$|\\.$");
    /** 구간 표지 바로 뒤에 붙으면 문장이 되는 조사 */
    private static final String PARTICLES = "는은를을와과의에로도가이";
    /** 제목 앞에 붙는 번호·글머리("다.", "(가)", "1.2.3", "□")로 보는 길이 */
    private static final int TITLE_PREFIX_LENGTH = 6;
    /** 안내 문장에만 나오는 낱말 */
    private static final Pattern NOTE_WORDS = Pattern.compile("참고|참조|동일한|경우|그대로|입니다|바랍니다");
    /** 목차 항목: 제목 뒤에 탭이나 점선, 쪽 번호가 붙는다 ("다. 요청 메시지 명세\t5") */
    private static final Pattern TOC_ENTRY = Pattern.compile("(?s).*(\\t|\\.{3,}|·{3,}|…+)\\s*\\d+\\s*$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s가-힣ㄱ-ㅎ()<>\"'\\[\\]|]+",
            Pattern.CASE_INSENSITIVE);
    /** 산문 속 URL 뒤에 붙어 나오는 문장부호 */
    private static final Pattern URL_TRAILER = Pattern.compile("[/.,;:]+$");

    private static final List<String> EXAMPLE_MARKERS = List.of("예제", "예시", "샘플", "sample", "example");
    private static final List<String> ERROR_MARKERS = List.of("에러코드", "오류코드", "에러메시지", "에러메세지",
            "errorcode", "결과코드");
    private static final List<String> REQUEST_MARKERS = List.of("요청메시지", "요청메세지", "요청변수", "요청파라미터",
            "요청파라메터", "요청인자", "요청항목", "입력파라미터", "입력변수", "입력값", "입력항목", "입력메시지",
            "호출메시지", "requestparameter", "requestmessage", "requestelement", "request");
    private static final List<String> RESPONSE_MARKERS = List.of("응답메시지", "응답메세지", "응답변수", "응답파라미터",
            "응답항목", "응답결과", "응답필드", "응답명세", "출력값", "출력변수", "출력결과", "출력항목", "출력메시지",
            "responsemessage", "responseelement", "responseparameter", "response");
    private static final List<String> INFO_MARKERS = List.of("상세기능정보", "오퍼레이션정보", "상세기능명세",
            "오퍼레이션명세");
    private static final List<String> OTHER_MARKERS = List.of("서비스개요", "서비스정보", "서비스명세", "오퍼레이션목록",
            "상세기능목록", "상세기능내역", "오퍼레이션내역", "개정이력", "목차");

    /** 오퍼레이션 주소를 담는 키. Call Back URL 외에도 기관마다 이름이 다르다 */
    private static final List<String> ENDPOINT_KEYS = List.of("callbackurl", "callbackxmlurl", "callbackjsonurl",
            "요청주소", "요청url", "호출url", "호출주소", "endpointurl", "endpoint", "apiurl",
            "requesturl", "오퍼레이션url", "상세기능url", "메시지명(영문)/endpointurl");
    /** 오퍼레이션 영문명을 담는 키 */
    private static final List<String> OPERATION_NAME_KEYS = List.of("오퍼레이션명(영문)", "상세기능명(영문)",
            "오퍼레이션영문명", "상세기능영문명", "오퍼레이션명", "상세기능명", "operationname");
    /** 오퍼레이션 국문명을 담는 키 */
    private static final List<String> OPERATION_TITLE_KEYS = List.of("상세기능명(국문)", "오퍼레이션명(국문)",
            "오퍼레이션국문명", "상세기능국문명", "상세기능명", "오퍼레이션명");
    private static final List<String> OPERATION_DESCRIPTION_KEYS = List.of("상세기능설명", "오퍼레이션설명",
            "상세기능개요", "오퍼레이션개요", "기능설명");
    /** 정보 표만 갖는 키. 이것이 있으면 서비스 개요나 오퍼레이션 목록 표가 아니라 상세기능 정보 표다 */
    private static final List<String> OPERATION_NUMBER_KEYS = List.of("상세기능번호", "오퍼레이션번호");
    private static final List<String> INFO_FIRST_CELLS = List.of("오퍼레이션정보", "상세기능정보");
    /** 서비스 개요 표의 기본 주소 키 */
    private static final List<String> SERVICE_URL_KEYS = List.of("서비스url", "서비스주소", "서비스url(운영)",
            "서비스기본url", "baseurl");
    /** 서비스 URL 아래 개발/운영 주소를 가르는 칸 */
    private static final String PRODUCTION_MARKER = "운영";

    private SpecLabels() {
    }

    /**
     * 리더가 셀·문단 글을 넘기기 전에 다듬는다. 줄바꿈 없는 공백(U+00A0 등)은 {@link String#strip()}이
     * 공백으로 보지 않아 빈 칸("항목 | 목록 | NBSP")을 값이 있는 칸으로 착각하게 하므로 일반 공백으로 바꾼다.
     */
    static String cleanText(String text) {
        return NO_BREAK_SPACE.matcher(text).replaceAll(" ").strip();
    }

    /** 공백(전각·줄바꿈 포함)을 지우고 영문을 소문자로 바꾼다. */
    static String normalize(String text) {
        return text.replaceAll("[\\s\\u00a0\\u3000]+", "").toLowerCase(Locale.ROOT);
    }

    static boolean isTocEntry(String text) {
        return TOC_ENTRY.matcher(text).matches();
    }

    /**
     * 헤딩이 여는 구간을 판별한다. 구간을 알 수 없는 문단(주석, 본문 문장)은 null.
     * "요청/응답 메시지 예제"처럼 요청·응답을 함께 담는 제목이 있어 예제를 먼저 본다.
     * "OOO 오퍼레이션 요청메시지 명세"는 오퍼레이션도 담지만 요청 구간이다.
     */
    static Section headingSection(String text) {
        String key = normalize(text);
        if (key.isEmpty() || key.length() > MAX_HEADING_LENGTH || key.startsWith("※") || key.startsWith("*")
                || isSentence(text)) {
            return null;
        }
        boolean request = containsAny(key, REQUEST_MARKERS);
        boolean response = containsAny(key, RESPONSE_MARKERS);
        if (containsAny(key, EXAMPLE_MARKERS) && (request || response || key.contains("메시지") || key.contains("메세지"))) {
            return Section.EXAMPLE;
        }
        // "응답 메시지 명세(결과코드 포함)"처럼 요청·응답 제목이 에러 표지를 함께 담으면 요청·응답이다
        if (request) {
            return Section.REQUEST;
        }
        if (response) {
            return Section.RESPONSE;
        }
        if (containsAny(key, ERROR_MARKERS)) {
            return Section.ERROR;
        }
        if (containsAny(key, INFO_MARKERS)) {
            return Section.INFO;
        }
        if (containsAny(key, OTHER_MARKERS)) {
            return Section.OTHER;
        }
        return null;
    }

    /**
     * 구간 제목이 아니라 안내 문장인 문단("결과코드는 에러코드 정리를 참고", "요청변수와 동일한 항목은 그대로 반환").
     * 제목은 명사로 끝나고 조사·서술어로 이어지는 문장꼴이 아니다.
     */
    private static boolean isSentence(String text) {
        String key = normalize(text);
        int marker = -1;
        String found = null;
        for (List<String> markers : List.of(REQUEST_MARKERS, RESPONSE_MARKERS, INFO_MARKERS, EXAMPLE_MARKERS, ERROR_MARKERS)) {
            for (String candidate : markers) {
                int at = key.indexOf(candidate);
                if (at >= 0 && (marker < 0 || at < marker)) {
                    marker = at;
                    found = candidate;
                }
            }
        }
        if (marker < 0) {
            return false;
        }
        int after = marker + found.length();
        if (after < key.length() && PARTICLES.indexOf(key.charAt(after)) >= 0) {
            // "결과코드는 …", "요청변수와 동일한 …" — 구간 표지가 문장의 주어·목적어로 쓰였다
            return true;
        }
        // "응답 메시지 (Response Message) – … 정렬됨"처럼 제목 뒤에 안내를 덧붙인 문단은 제목이다
        return marker > TITLE_PREFIX_LENGTH && (SENTENCE_ENDING.matcher(key).find() || NOTE_WORDS.matcher(key).find());
    }

    /**
     * 표 안에서 구간을 가르는 행("입력값 명세", "출력값 명세", "응답메시지(계속)")의 구간.
     * 칸이 병합돼 같은 글이 여러 칸에 걸쳐도 서로 다른 글이 하나뿐인 행만 본다.
     */
    static Section rowSection(List<String> cells) {
        String only = null;
        for (String cell : cells) {
            if (cell.isBlank()) {
                continue;
            }
            if (only != null && !only.equals(cell)) {
                return null;
            }
            only = cell;
        }
        if (only == null) {
            return null;
        }
        Section section = headingSection(only);
        return section == Section.REQUEST || section == Section.RESPONSE ? section : null;
    }

    static boolean isEndpointKey(String cell) {
        String key = normalize(cell);
        return ENDPOINT_KEYS.contains(key) || key.startsWith("callbackurl");
    }

    static boolean isServiceUrlKey(String cell) {
        return SERVICE_URL_KEYS.contains(normalize(cell));
    }

    static boolean isProductionLabel(String cell) {
        return normalize(cell).contains(PRODUCTION_MARKER);
    }

    static boolean isOperationNumberKey(String cell) {
        return OPERATION_NUMBER_KEYS.contains(normalize(cell));
    }

    static boolean isInfoFirstCell(String cell) {
        return INFO_FIRST_CELLS.contains(normalize(cell));
    }

    static boolean isOperationNameKey(String cell) {
        return OPERATION_NAME_KEYS.contains(normalize(cell));
    }

    static List<String> operationNameKeys() {
        return OPERATION_NAME_KEYS;
    }

    static List<String> operationTitleKeys() {
        return OPERATION_TITLE_KEYS;
    }

    static List<String> operationDescriptionKeys() {
        return OPERATION_DESCRIPTION_KEYS;
    }

    /** 헤딩이 "요청 URL"처럼 바로 다음 줄에 주소가 온다는 표지인지 본다. */
    static boolean isUrlLabel(String text) {
        String key = normalize(text).replaceAll("^[^0-9a-z가-힣]+", "").replaceAll("[:：]+$", "");
        return ENDPOINT_KEYS.contains(key);
    }

    /**
     * "● 요청 URL : http://..."처럼 주소 표지와 주소를 한 줄에 적은 문단의 주소.
     * 표지 없는 주소 줄은 예제일 수 있어 여기서는 다루지 않는다.
     */
    static String labeledUrl(String text) {
        int http = text.toLowerCase(Locale.ROOT).indexOf("http");
        if (http <= 0) {
            return null;
        }
        return isUrlLabel(text.substring(0, http)) ? firstUrl(text) : null;
    }

    /**
     * 칸이나 문단에서 첫 http(s) 주소를 꺼낸다. 질의 문자열은 버린다.
     * 칸 폭 때문에 주소가 여러 줄로 꺾여 있으면 줄바꿈을 지우고 이어 붙인다.
     */
    static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL.matcher(text.replaceAll("(?<=[/\\w.-])[\\r\\n]+(?=[/\\w.-])", ""));
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        return URL_TRAILER.matcher(url).replaceAll("");
    }

    private static boolean containsAny(String key, List<String> markers) {
        for (String marker : markers) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
