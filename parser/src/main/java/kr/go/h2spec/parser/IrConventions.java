package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rule 엔진과 llm 엔진이 같이 쓰는 IR 규칙. 같은 칸, 같은 후보에서는 엔진이 달라도 같은 값이 나오게 한다.
 * 주소가 같아야 apiId, 곧 생성 클래스명과 파일 경로가 바뀌지 않는다.
 * <p>
 * 입력은 표의 행이 아니라 칸(문단)의 원래 글, 거기서 꺼낸 값, IR 노드다. rule 엔진은 표준 열 순서로 맞춘 행의 칸에서,
 * llm 엔진은 원문 대조로 찾은 칸에서 같은 함수를 부른다. 규칙은 rule 엔진이 기관 문서로 다듬어 온 것을 그대로 옮겼다.
 */
public final class IrConventions {

    /** 칸이나 문단의 http(s) 주소. 한글·괄호·따옴표·꺾쇠·대괄호·세로줄에서 끝난다 */
    private static final Pattern URL = Pattern.compile("https?://[^\\s가-힣ㄱ-ㅎ()<>\"'\\[\\]|]+",
            Pattern.CASE_INSENSITIVE);
    /** 산문 속 URL 뒤에 붙어 나오는 문장부호 */
    private static final Pattern URL_TRAILER = Pattern.compile("[/.,;:]+$");
    /** 주소 안에서 슬래시 뒤를 띄어 쓴 자리("…/BidPublicInfoService/ getBidPblancListInfoServcPPSSrch"). 다음 주소의 시작은 아니다 */
    private static final Pattern SPACE_AFTER_SLASH = Pattern.compile("(?<=\\w/)\\h+(?=[A-Za-z_])(?!https?://)");
    /** "portal.kosha.or.kr/openapi/v1/koshagw"처럼 스킴 없이 적은 주소 */
    private static final Pattern SCHEMELESS_URL =
            Pattern.compile("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?/[\\w./{}-]*");
    /** 공공데이터포털 게이트웨이 주소 */
    private static final Pattern GATEWAY_URL = Pattern.compile("(?i)^https?://apis\\.data\\.go\\.kr/");
    /** 값이 비어 있음을 뜻하는 표기. Call Back URL 칸을 비워 두는 대신 적기도 한다 */
    static final String NOT_AVAILABLE = "N/A";
    /** 공백 없는 영문 식별자 (getMsrstnList, RESULT_CODE) */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 하위 항목을 들여쓰는 표지 (└ name, ㄴname, - name, + name) */
    private static final Pattern TREE_MARKER = Pattern.compile("^[\\s└ㄴ\\-‐–+·ㆍ•▶>*\\u2514\\u251c\\u2502]+");
    /** 낙타 표기 이름이 공백으로 갈라진 꼴: 소문자·숫자 뒤 공백 다음에 대문자가 온다 */
    private static final Pattern SPLIT_CAMEL_CASE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*[a-z0-9]( [A-Z][A-Za-z0-9_]*)+");
    /** 이름 앞뒤에 붙은 꺾쇠·구두점 */
    private static final Pattern NAME_PUNCTUATION = Pattern.compile("^[<(\"'`]+|[>)\"'`,;:.]+$");
    /** 영문 이름 뒤에 국문 설명을 괄호로 붙인 칸 ("numOfRows(한 페이지 결과 수)") */
    private static final Pattern NAME_WITH_NOTE = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$\\-]*)\\s*[(（].*$");

    private static final String OPERATION_PREFIX = "get";
    /** 숫자로 시작하는 오퍼레이션 경로로 만든 apiId 앞에 붙이는 접두사 */
    private static final String API_ID_PREFIX = "Api";
    /** 엔드포인트의 경로 변수 */
    private static final Pattern PATH_TEMPLATE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** 게이트웨이 인증키 파라미터 이름. generator의 ClientEmitter는 이 이름(대소문자 무시)으로 인증키를 찾는다 */
    public static final String SERVICE_KEY = "serviceKey";
    /**
     * 게이트웨이 serviceKey를 보충했을 때 남기는 검수 노트. rule 엔진은 주소를 고르며 남긴 노트 다음,
     * 겹치는 주소를 바로잡은 노트 앞에 둔다.
     */
    public static final String GATEWAY_SERVICE_KEY_NOTE =
            "요청 명세 표에 serviceKey가 없어 공공데이터포털 게이트웨이 인증키 파라미터를 추가함";
    private static final String SERVICE_KEY_DESCRIPTION = "공공데이터포털에서 발급받은 인증키";
    /** 인증키로 보는 질의 파라미터 이름(소문자, _·- 없음). generator의 AuthKey와 같다 */
    private static final Set<String> AUTH_KEY_NAMES = Set.of("servicekey",
            "apikey", "authapikey", "authkey", "certkey", "crtfckey", "accesskey", "appkey", "openapikey", "key");
    private static final Pattern PATH_AUTH_KEY =
            Pattern.compile("(?i)(your[_-]?)?((app|api|project|service|auth|access|cert|open[_-]?api)[_-]?)?key");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * 공공데이터 표준 응답에서 response.header 바로 아래에 오는 결과코드·결과메시지 필드.
     * RESULT_CODE처럼 대문자·밑줄로 적는 기관이 있어 metaKey로 맞춘 꼴로 비교한다.
     */
    private static final Set<String> RESULT_FIELDS = Set.of("resultcode", "resultmsg", "resultmessage");
    private static final String RESULT_CODE_KEY = "resultcode";
    /** 선행 0이 붙은 정수 샘플(0311 등)은 숫자로 만들면 값이 바뀐다 */
    private static final Pattern LEADING_ZERO = Pattern.compile("^0\\d+$");

    /**
     * 응답 포맷을 고르는 요청 파라미터. 이름이 기관마다 다르다.
     * generator의 ClientEmitter가 힌트 없이 포맷 값을 채워 보내는 이름과 같아야 한다. 여기서만 넓히면
     * 클라이언트는 포맷 파라미터를 보내지 않는데 DTO는 JSON을 기대해 응답을 읽지 못한다.
     * 이 밖의 이름은 llm 엔진이 generatorHints.responseFormatParameter로 알린다.
     * 두 목록이 같은지는 IrConventionsTest가 확인한다.
     */
    private static final Set<String> RESPONSE_FORMAT_PARAMETERS = Set.of("returnType", "dataType", "resultType");
    /** 매니페스트가 없는 실행(테스트, IDE의 클래스 디렉터리)에서 적는 파서 버전 */
    private static final String DEV_VERSION = "dev";
    /** IR의 metadata.parserVersion. 배포판 jar 매니페스트의 버전이다(parser/build.gradle) */
    private static final String PARSER_VERSION =
            parserVersion(IrConventions.class.getPackage().getImplementationVersion());
    /** metadata.engine에 적는 rule 엔진 이름. {@link SpecParseResult.Engine#RULE}의 이름과 같다 */
    private static final String ENGINE = "rule";
    private static final String SUCCESS_RESULT_CODE = "00";
    private static final List<String> RESULT_CODE_FIELD_CANDIDATES = List.of(
            "response.header.resultCode", "resultCode", "RESULT_CODE", "cmmMsgHeader.returnReasonCode");

    private IrConventions() {
    }

    // ---- 칸의 원래 글에서 주소 꺼내기 ----

    /**
     * 칸이나 문단에서 첫 http(s) 주소를 꺼낸다. 질의 문자열과 끝 문장부호는 버린다.
     * 칸 폭 때문에 주소가 여러 줄로 꺾여 있으면 줄바꿈을 지우고 이어 붙인다. 슬래시 뒤를 띄어 쓴 경로도 이어 붙인다.
     * 공백에서 끊으면 요청 예제가 서비스 주소 자체를 부르는 것으로 보여 {@link #endpointUrl}이 오퍼레이션 경로를 버린다.
     * 표 안의 주소 행, "요청 URL" 문단, 요청 예제 칸에 쓴다.
     *
     * @param text 칸(문단)의 원래 글
     * @return 주소. 없으면 null
     */
    public static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        String joined = text.replaceAll("(?<=[/\\w.-])[\\r\\n]+(?=[/\\w.-])", "");
        Matcher matcher = URL.matcher(SPACE_AFTER_SLASH.matcher(joined).replaceAll(""));
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

    /**
     * 정보 표의 주소 칸(Call Back URL, 요청주소 등)에서 주소를 꺼낸다. 비었거나 N/A면 없는 것으로 본다.
     * 칸 폭 때문에 공백·줄바꿈으로 꺾인 주소는 잇는다.
     *
     * @param cellText 주소 칸의 원래 글
     * @return 주소. 없으면 null
     */
    public static String callBackUrl(String cellText) {
        if (cellText == null) {
            return null;
        }
        String value = cellText.strip();
        if (value.isEmpty() || NOT_AVAILABLE.equalsIgnoreCase(value)) {
            return null;
        }
        return firstUrl(joinWrapped(value));
    }

    /**
     * 정보 표의 주소 칸에 스킴 없이 적은 주소("portal.kosha.or.kr/openapi/v1/koshagw")를 꺼낸다. 끝 슬래시는 지운다.
     * 주소를 고를 때 다른 후보가 없으면 https://를 붙여 쓴다.
     *
     * @param cellText 주소 칸의 원래 글
     * @return 스킴 없는 주소. 칸 전체가 그런 꼴이 아니면 null
     */
    public static String schemelessUrl(String cellText) {
        if (cellText == null) {
            return null;
        }
        String value = cellText.strip();
        return SCHEMELESS_URL.matcher(value).matches() ? value.replaceAll("/+$", "") : null;
    }

    /**
     * 서비스 개요 표의 서비스 URL 칸에서 주소를 꺼낸다. http로 시작하는 칸만 보고, 꺾인 주소는 잇는다.
     *
     * @param cellText 서비스 URL 칸의 원래 글
     * @return 주소. 없으면 null
     */
    public static String serviceUrl(String cellText) {
        if (cellText == null) {
            return null;
        }
        String value = cellText.strip();
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? firstUrl(joinWrapped(value)) : null;
    }

    /** 칸 폭 때문에 공백·줄바꿈으로 꺾인 주소를 잇는다. 주소 뒤에 한글 설명이 붙은 칸은 그대로 둔다 */
    private static String joinWrapped(String value) {
        return value.matches("[\\x21-\\x7e\\s]+") ? value.replaceAll("\\s+", "") : value;
    }

    // ---- 영문 오퍼레이션명, 이름 정리 ----

    /**
     * 정보 표의 이름 칸에서 영문 오퍼레이션명을 꺼낸다. 원래 글이 공백 없는 영문 식별자일 때만 쓴다.
     * 국문명이나 "getMsrstnList(측정소 목록)"처럼 설명이 붙은 칸은 오퍼레이션명으로 쓰지 않는다.
     *
     * @param cellText 이름 칸의 원래 글
     * @return 오퍼레이션명. 없으면 null
     */
    public static String operationName(String cellText) {
        if (cellText == null) {
            return null;
        }
        String value = cellText.strip();
        return IDENTIFIER.matcher(value).matches() ? value : null;
    }

    /**
     * 정보 표에서 영문 오퍼레이션명을 담는 칸의 이름표("오퍼레이션명(영문)", "상세기능명" 등)인지 본다. rule 엔진은 행 안에서 이 이름표 다음의
     * 빈칸이 아닌 칸을 이름 칸으로 읽는다. llm 엔진의 원문 대조도 같은 칸만 이름 칸으로 본다.
     *
     * @param cellText 칸의 원래 글
     * @return 이름표면 true
     */
    public static boolean isOperationNameLabel(String cellText) {
        return cellText != null && SpecLabels.isOperationNameKey(cellText);
    }

    /**
     * 파라미터·필드 이름 칸의 원래 글을 이름으로 다듬는다. 트리 표지와 이름 뒤에 덧붙인 설명
     * ({@code "numOfRows\n(한 페이지 결과 수)"}), 오타로 남은 꺾쇠·구두점({@code "mnab3Mnud4DlpnCnt>"})을 떼고,
     * 칸 폭 때문에 꺾인 이름은 잇는다.
     *
     * @param raw 이름 칸의 원래 글
     * @return 다듬은 이름. 이름이 없으면 빈 문자열
     */
    public static String cleanName(String raw) {
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

    /** 칸이 트리 표지(└, ㄴ)로 시작하는지. 필드 표에서 들여쓰기 깊이를 잴 때 이름 정리와 같은 표지를 쓴다 */
    static boolean startsWithTreeMarker(String cell) {
        return TREE_MARKER.matcher(cell).lookingAt();
    }

    // ---- 주소 고르기 ----

    /**
     * 주소 후보에서 오퍼레이션 주소를 고른다. 아래 순서로 처음 맞는 것을 쓴다.
     * <ol>
     *   <li>Call Back URL. 다만 Call Back URL이 서비스 URL 밖을 가리키는데 요청 예제가 서비스 URL 아래에서 같은
     *       오퍼레이션을 부르거나, 요청 예제가 Call Back URL 아래 경로를 부르면 요청 예제</li>
     *   <li>표 안의 END POINT URL</li>
     *   <li>마지막 경로 칸에 영문 오퍼레이션명이 든 요청 예제</li>
     *   <li>서비스 URL + 영문 오퍼레이션명. 요청 예제가 서비스 URL 자체를 부르면 요청 예제</li>
     *   <li>"요청 URL" 문단</li>
     *   <li>요청 예제</li>
     *   <li>스킴 없는 Call Back URL에 https://를 붙인 주소</li>
     *   <li>서비스 URL. 공통 구간은 이 단계로 주소를 얻지 않는다</li>
     * </ol>
     * 문서가 적은 주소 대신 다른 후보를 쓴 사정 가운데 검수자가 확인할 것은 notes에 남긴다.
     *
     * @param candidates 주소 후보
     * @param notes      노트를 더할 목록
     * @return 고른 주소. 고를 후보가 없으면 null
     */
    public static String endpointUrl(UrlCandidates candidates, List<String> notes) {
        String name = candidates.operationName();
        String callBack = candidates.callBackUrl();
        String example = candidates.exampleUrl();
        String service = candidates.serviceUrl();
        if (callBack != null) {
            if (example != null && service != null && !isUnder(callBack, service)
                    && isUnder(example, service) && lastSegment(example).equals(lastSegment(callBack))) {
                // Call Back URL의 서비스 경로를 잘못 적은 문서(…/wellnessTursmSyncList/wellnessTursmSyncList):
                // 요청 예제가 문서의 서비스 주소 아래에서 같은 오퍼레이션을 부르면 예제가 맞다
                notes.add("Call Back URL이 서비스 URL 밖을 가리켜 요청 예제의 주소를 씀: " + callBack);
                return example;
            }
            if (example != null && normalizeUrl(example).startsWith(normalizeUrl(callBack) + "/")) {
                // Call Back URL에 서비스 주소만 적고 오퍼레이션 경로를 빠뜨린 문서: 요청 예제가 그 아래 경로를 부른다
                return example;
            }
            // 서비스 주소와 같아도 그대로 쓴다. 국립해양조사원 문서처럼 서비스 주소 자체가 호출 주소이고
            // 요청 예제도 그 주소를 쓰는 기관이 있다
            return callBack;
        }
        if (candidates.tableUrl() != null) {
            return candidates.tableUrl();
        }
        if (example != null && name != null && lastSegment(example).contains(name.toLowerCase(Locale.ROOT))) {
            // Call Back URL이 비었거나 "서비스URL/getX" 같은 자리표시면 요청 예제의 실제 주소가 가장 정확하다
            // (trafficLightList → …/trafficLightList4, 기관 서버 주소 대신 게이트웨이 주소)
            return example;
        }
        if (service != null && name != null) {
            // 서비스 URL에 오퍼레이션 경로까지 적은 문서는 이름을 다시 붙이지 않는다 (…/getSumperfuel5m)
            String joined = lastSegment(service).equals(name.toLowerCase(Locale.ROOT))
                    ? service.replaceAll("/+$", "")
                    : service.replaceAll("/+$", "") + "/" + name;
            // 서비스 주소 자체가 호출 주소인 기관이 있다. 요청 예제가 그렇게 부르면 예제를 따른다
            if (example != null && sameUrl(example, service) && !sameUrl(joined, service)) {
                return example;
            }
            return joined;
        }
        if (candidates.lineUrl() != null) {
            return candidates.lineUrl();
        }
        if (example != null) {
            return example;
        }
        String schemeless = candidates.schemelessCallBackUrl();
        if (schemeless != null) {
            notes.add("Call Back URL에 스킴이 없어 https://를 붙임: " + schemeless);
            return "https://" + schemeless;
        }
        // 공통 구간은 서비스 URL만으로 오퍼레이션이 되지 않아야 뒤 오퍼레이션에 붙는다
        if (service != null && !candidates.common()) {
            notes.add("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀");
            return service;
        }
        return null;
    }

    /**
     * 주소가 겹치는 오퍼레이션을 바로잡는다. 두 기능이 같은 주소를 가질 수는 없으므로 앞뒤 기능의 Call Back URL을
     * 베껴 두고 고치지 않은 문서로 보고, 자기 요청 예제가 같은 호스트의 다른 주소를 부르는 오퍼레이션은 예제 주소를 쓴다
     * (전통식품정보 getFoodHistoryList, TourAPI areaIntlDivList).
     * 겹침은 바로잡기 전 주소로 판정한다. 앞 것을 고친 뒤 다시 보면 뒤 것은 더 이상 겹치지 않기 때문이다.
     *
     * @param urls        문서 순서대로 오퍼레이션마다 {@link #endpointUrl}이 고른 주소. null이 없어야 한다
     * @param exampleUrls urls와 같은 순서의 요청 예제 주소. 없는 자리는 null
     * @param notes       urls와 같은 순서의 오퍼레이션별 노트 목록. 바로잡은 오퍼레이션의 목록에 노트를 더한다
     * @return 바로잡은 주소 목록 (urls와 같은 순서)
     */
    public static List<String> correctSharedUrls(List<String> urls, List<String> exampleUrls,
                                                 List<List<String>> notes) {
        List<String> corrected = new ArrayList<>(urls);
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            String example = exampleUrls.get(i);
            long sharing = urls.stream().filter(other -> sameUrl(other, url)).count();
            if (sharing > 1 && example != null && !sameUrl(example, url) && sameHost(example, url)) {
                notes.get(i).add("Call Back URL이 다른 오퍼레이션과 같아 요청 예제의 주소를 씀: " + url);
                corrected.set(i, example);
            }
        }
        return corrected;
    }

    /**
     * 두 주소가 같은지 본다. 스킴(http/https)과 끝 슬래시, 대소문자는 무시한다.
     *
     * @param a 주소
     * @param b 주소
     * @return 같은 주소면 true
     */
    public static boolean sameUrl(String a, String b) {
        return normalizeUrl(a).equals(normalizeUrl(b));
    }

    private static boolean sameHost(String a, String b) {
        return normalizeUrl(a).split("/")[0].equals(normalizeUrl(b).split("/")[0]);
    }

    /** url이 base 주소 아래 경로인지 (스킴·대소문자 무시) */
    private static boolean isUnder(String url, String base) {
        return normalizeUrl(url).startsWith(normalizeUrl(base) + "/");
    }

    private static String lastSegment(String url) {
        String trimmed = url.replaceAll("/+$", "");
        return trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeUrl(String url) {
        return url.replaceFirst("(?i)^https?://", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    // ---- 주소 나누기, 경로 변수, 게이트웨이 serviceKey ----

    /**
     * 주소를 나눈 결과. 나눌 수 없으면 error에 이유가 있고 나머지는 null이다.
     *
     * @param baseUrl  오퍼레이션 경로 앞까지 ({@code http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc})
     * @param endpoint 오퍼레이션 경로부터 끝까지. 끝의 경로 변수를 담는다 ({@code /getMsrstnList}, {@code /2058570a/{your_appkey}})
     * @param apiId    오퍼레이션 경로로 만든 식별자. 생성 클래스명과 패키지명이 된다 ({@code MsrstnList})
     * @param error    나눌 수 없는 이유. 나눴으면 null
     */
    public record UrlParts(String baseUrl, String endpoint, String apiId, String error) {

        /**
         * 나눴는지 본다.
         *
         * @return 오류 없이 나눴으면 true
         */
        public boolean ok() {
            return error == null;
        }

        private static UrlParts failed(String error) {
            return new UrlParts(null, null, null, error);
        }
    }

    /**
     * 주소를 baseUrl, endpoint, apiId로 나눈다. 끝 슬래시는 지우고, 마지막 경로 칸을 오퍼레이션으로 본다.
     * {@code …/proxy/2058570a/{your_appkey}}처럼 주소 끝에 경로 변수가 오면 그 앞 칸이 오퍼레이션이고,
     * 경로 변수는 endpoint에 남는다.
     *
     * @param url 고른 주소
     * @return 나눈 결과. 오퍼레이션 경로를 찾을 수 없으면 예외 대신 이유를 담은 결과
     */
    public static UrlParts splitUrl(String url) {
        if (url == null) {
            return UrlParts.failed("오퍼레이션 주소가 없습니다");
        }
        return splitUntrimmed(url.replaceAll("/+$", ""));
    }

    /**
     * 끝 슬래시를 지우지 않고 나눈다. 끝이 슬래시면 오퍼레이션 경로가 없다고 알린다.
     * 정보 표의 Call Back URL 칸을 그대로 받는 {@link IrAssembler} 직접 호출이 이렇게 동작해 왔다.
     */
    static UrlParts splitUntrimmed(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == url.length() - 1) {
            return UrlParts.failed(noOperationPath(url));
        }
        // "…/proxy/<id>/{your_appkey}"처럼 주소 끝에 경로 변수가 오면 그 앞 칸이 오퍼레이션이다
        int operationSlash = lastSlash;
        while (isTemplateSegment(url.substring(operationSlash + 1).split("/")[0]) && operationSlash > 0) {
            int previous = url.lastIndexOf('/', operationSlash - 1);
            if (previous < 0 || url.substring(0, previous).endsWith("/")) {
                break;
            }
            operationSlash = previous;
        }
        String segment = url.substring(operationSlash + 1);
        String operation = segment.split("/")[0];
        int scheme = url.indexOf("://");
        if (operation.isEmpty() || (scheme >= 0 && operationSlash <= scheme + 2)) {
            // "http://{host}"처럼 경로 변수 앞에 오퍼레이션 칸이 없거나, "http://apis.data.go.kr"처럼 호스트 뒤에 경로가 없다.
            // 호스트를 오퍼레이션으로 나누면 baseUrl이 "http:/"가 된다
            return UrlParts.failed(noOperationPath(url));
        }
        String baseUrl = url.substring(0, operationSlash);
        if (baseUrl.indexOf('{') >= 0 || baseUrl.indexOf('}') >= 0) {
            // 생성 클라이언트는 endpoint의 경로 변수만 채운다. baseUrl에 남은 자리표시는 그대로 요청 주소가 되어 호출이 실패한다
            return UrlParts.failed("경로 변수가 endpoint가 아니라 baseUrl에 있습니다: " + url);
        }
        return new UrlParts(baseUrl, "/" + segment, toApiId(operation), null);
    }

    private static String noOperationPath(String url) {
        return "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다: " + url;
    }

    /**
     * getMsrstnList → MsrstnList (get 접두사 제거, 첫 글자 대문자).
     * 떼고 나면 숫자로 시작하는 이름(get24DivisionsInfo)은 자바 클래스명으로 쓸 수 없어
     * 접두사를 남긴다 → Get24DivisionsInfo.
     */
    private static String toApiId(String segment) {
        String name = segment.startsWith(OPERATION_PREFIX)
                ? segment.substring(OPERATION_PREFIX.length())
                : segment;
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = segment;
        }
        // 클래스명으로 쓰이므로 식별자에 못 쓰는 글자(".do", "-")는 밑줄로 바꾸고,
        // 숫자로 시작하는 경로("2058570a…")는 접두사를 붙인다
        name = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isLetter(name.charAt(0))) {
            name = API_ID_PREFIX + name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isTemplateSegment(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /**
     * @param endpoint 나눈 endpoint
     * @return endpoint의 경로 변수 이름({@code {itemId}}의 {@code itemId}). {@link #addPathParameters}가 경로 변수로 채우는 이름과 같다
     */
    public static Set<String> pathVariableNames(String endpoint) {
        Set<String> names = new LinkedHashSet<>();
        Matcher template = PATH_TEMPLATE.matcher(endpoint);
        while (template.find()) {
            names.add(template.group(1));
        }
        return names;
    }

    /**
     * endpoint의 경로 변수({@code {your_appkey}})가 요청 파라미터에 없으면 필수 path 파라미터로 맨 앞부터 채운다.
     * 같은 이름의 파라미터가 있으면 path·필수로 바꾼다.
     *
     * @param requestParameters IR의 api.requestParameters 배열. 이 배열을 고친다
     * @param endpoint          {@link #splitUrl}이 나눈 endpoint
     */
    public static void addPathParameters(ArrayNode requestParameters, String endpoint) {
        Matcher template = PATH_TEMPLATE.matcher(endpoint);
        int insertAt = 0;
        while (template.find()) {
            String name = template.group(1);
            ObjectNode existing = null;
            for (JsonNode param : requestParameters) {
                if (name.equals(param.get("name").asText())) {
                    existing = (ObjectNode) param;
                }
            }
            if (existing != null) {
                existing.put("in", "path");
                existing.put("required", true);
                continue;
            }
            ObjectNode param = requestParameters.objectNode();
            param.put("name", name);
            param.put("in", "path");
            param.put("type", "string");
            param.put("required", true);
            param.put("description", "주소의 {" + name + "} 자리에 넣는 값");
            requestParameters.insert(insertAt++, param);
        }
    }

    /**
     * 게이트웨이 serviceKey를 보충해야 하는지 본다. 공공데이터포털 게이트웨이(apis.data.go.kr)는 모든 호출에
     * serviceKey를 요구하지만, 요청 명세 표에서 이를 빼고 본문 설명으로만 적는 기관이 있다.
     * 게이트웨이가 아닌 기관 서버는 인증 방식이 제각각이라 채우지 않는다.
     *
     * @param url            고른 주소. 겹치는 주소 바로잡기는 호스트를 바꾸지 않으므로 바로잡기 전후가 같다
     * @param parameterNames 요청 파라미터 이름. 공통 구간에서 붙인 것도 넣는다
     * @return 주소가 게이트웨이이고 이름이 serviceKey(대소문자 무시)인 파라미터가 없으면 true
     */
    public static boolean needsGatewayServiceKey(String url, Collection<String> parameterNames) {
        return GATEWAY_URL.matcher(url).find() && parameterNames.stream().noneMatch(SERVICE_KEY::equalsIgnoreCase);
    }

    /**
     * 인증키로 보는 질의 파라미터 이름인지. 대소문자와 _·-를 가리지 않는다. generator가 api.auth 없이 인증키를 찾는 이름
     * (AuthKey)과 같은 목록이다. 이름이 key로 끝나도 검색·정렬 키는 넣지 않는다
     */
    public static boolean isAuthKeyName(String name) {
        return name != null && AUTH_KEY_NAMES.contains(name.replaceAll("[_-]", "").toLowerCase(Locale.ROOT));
    }

    /** 주소 경로에 인증키 꼴의 변수({your_appkey}, {KEY})가 있는지. generator(AuthKey)가 경로의 키로 보는 이름꼴이다 */
    public static boolean hasPathAuthKey(String endpoint) {
        Matcher matcher = PATH_VARIABLE.matcher(endpoint);
        while (matcher.find()) {
            if (PATH_AUTH_KEY.matcher(matcher.group(1)).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 게이트웨이 serviceKey 파라미터를 맨 앞에 넣는다. {@link #addPathParameters}보다 먼저 불러야 경로 변수
     * 파라미터 다음 자리가 된다(rule 엔진과 같은 자리). 노트({@link #GATEWAY_SERVICE_KEY_NOTE})는 부르는 쪽이 남긴다.
     *
     * @param requestParameters IR의 api.requestParameters 배열. 이 배열을 고친다
     */
    public static void insertGatewayServiceKey(ArrayNode requestParameters) {
        ObjectNode param = requestParameters.objectNode();
        param.put("name", SERVICE_KEY);
        param.put("in", "query");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", SERVICE_KEY_DESCRIPTION);
        requestParameters.insert(0, param);
    }

    // ---- 필드 판정 ----

    /** resultCode·RESULT_CODE·result_code를 같은 이름으로 본다 */
    static String metaKey(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 결과코드·결과메시지 필드(resultCode, resultMsg, RESULT_CODE 등)인지 본다. 이 필드는 샘플이 숫자("00")여도
     * 앞자리 0을 지키려고 항상 string으로 둔다.
     *
     * @param name 필드 이름
     * @return 결과코드·결과메시지 필드면 true
     */
    public static boolean isResultCodeOrMessageField(String name) {
        return RESULT_FIELDS.contains(metaKey(name));
    }

    /**
     * 결과코드 필드인지 본다. IR 응답 필드에 isResultIndicator를 붙이는 기준이다.
     *
     * @param name 필드 이름
     * @return 결과코드 필드면 true
     */
    public static boolean isResultCodeField(String name) {
        return RESULT_CODE_KEY.equals(metaKey(name));
    }

    /**
     * 앞자리 0이 붙은 숫자(0311)인지 본다. 숫자 타입으로 두면 값이 바뀌므로 숫자 타입이어도 string으로 둔다.
     *
     * @param value 샘플·예시 값
     * @return {@code ^0\d+$} 꼴이면 true
     */
    public static boolean hasLeadingZero(String value) {
        return LEADING_ZERO.matcher(value).matches();
    }

    // ---- apiName·설명 ----

    /**
     * 정보 표 칸의 값을 국문 기능명·기능 설명 자리에 쓸 수 있는지 본다. 비었거나 N/A이거나
     * 영문 식별자만 담긴 값(getXxx)은 국문명 자리에 맞지 않아 쓰지 않는다.
     *
     * @param value 칸의 값
     * @return 쓸 수 있으면 true
     */
    public static boolean isDisplayText(String value) {
        if (value == null) {
            return false;
        }
        String text = value.strip();
        return !text.isEmpty() && !NOT_AVAILABLE.equalsIgnoreCase(text) && !IDENTIFIER.matcher(text).matches();
    }

    /**
     * api.apiName을 정한다. 국문 기능명을 쓸 수 없으면({@link #isDisplayText}) apiId로 채운다.
     *
     * @param title 국문 기능명. 없으면 null
     * @param apiId {@link #splitUrl}이 만든 apiId
     * @return 한 줄로 이은 apiName
     */
    public static String apiName(String title, String apiId) {
        return IrText.oneLine(isDisplayText(title) ? title : apiId);
    }

    /**
     * 요청 파라미터·응답 필드의 description을 정한다. 항목설명이 비어 있으면 국문 항목명을 설명으로 쓴다.
     *
     * @param description 항목설명
     * @param korName     국문 항목명
     * @return 한 줄로 이은 설명
     */
    public static String description(String description, String korName) {
        return IrText.oneLine(description.isBlank() ? korName : description);
    }

    // ---- 포맷 파라미터, 기본값 ----

    /**
     * 응답 포맷을 고르는 요청 파라미터 이름. generator의 ClientEmitter가 힌트 없이 포맷 값을 채워 보내는 이름과 같다.
     *
     * @return returnType, dataType, resultType
     */
    public static Set<String> responseFormatParameters() {
        return RESPONSE_FORMAT_PARAMETERS;
    }

    /**
     * 문서가 성공코드와 결과코드 위치를 알려 주지 않을 때의 api.errorSpec.
     *
     * @return 성공코드 00과 결과코드 필드 기본 후보
     */
    public static ObjectNode errorSpec() {
        ObjectNode errorSpec = JsonNodeFactory.instance.objectNode();
        errorSpec.put("successResultCode", SUCCESS_RESULT_CODE);
        ArrayNode candidates = errorSpec.putArray("resultCodeFieldCandidates");
        RESULT_CODE_FIELD_CANDIDATES.forEach(candidates::add);
        return errorSpec;
    }

    /**
     * IR의 generatorHints. 패키지명은 apiId로 만든다.
     *
     * @param apiId {@link #splitUrl}이 만든 apiId
     * @return generatorHints
     */
    // TODO(rule 포맷 파라미터): 포맷 파라미터 힌트는 llm 엔진만 더한다(IrNormalizer). rule 엔진도 더할 일은 IrAssembler에 적어 두었다
    public static ObjectNode generatorHints(String apiId) {
        ObjectNode hints = JsonNodeFactory.instance.objectNode();
        hints.put("targetClientType", "RestTemplate");
        // 기본 로캘을 따르면 같은 문서도 PC에 따라 패키지가 달라진다(터키어 로캘의 "Info" → "ınfo")
        hints.put("targetPackage", "kr.go.h2spec.client." + apiId.toLowerCase(Locale.ROOT));
        hints.put("generateInterceptor", true);
        hints.put("generateDto", true);
        hints.put("dtoNamingStrategy", "PascalCase");
        return hints;
    }

    /**
     * IR의 metadata. 검수 노트가 하나라도 있으면 manualReviewRequired가 true다.
     * 엔진은 rule로 적는다. llm 엔진은 같은 metadata에서 엔진을 llm으로 바꾸고 실행 설정을 더한다.
     *
     * @param sourceFile   원본 문서 파일 이름
     * @param sourceFormat 원본 포맷 (DOCX, HWP, HWPX)
     * @param reviewNotes  검수 노트
     * @return metadata
     */
    /**
     * jar 매니페스트의 버전을 IR에 적는 파서 버전으로 바꾼다. JitPack과 릴리스는 태그 이름(v0.2.1)을 버전으로 넘기므로 v를 뗀다.
     *
     * @param implementationVersion jar 매니페스트의 Implementation-Version. 매니페스트가 없으면 null
     * @return 파서 버전. 매니페스트가 없으면 {@value #DEV_VERSION}
     */
    static String parserVersion(String implementationVersion) {
        if (implementationVersion == null || implementationVersion.isBlank()) {
            return DEV_VERSION;
        }
        return implementationVersion.startsWith("v") ? implementationVersion.substring(1) : implementationVersion;
    }

    public static ObjectNode metadata(String sourceFile, String sourceFormat, List<String> reviewNotes) {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        metadata.put("sourceFile", sourceFile);
        metadata.put("sourceFormat", sourceFormat);
        metadata.put("parsedAt", OffsetDateTime.now().toString());
        metadata.put("parserVersion", PARSER_VERSION);
        // 두 엔진이 같은 자리에 엔진 이름을 적어, 커밋된 IR만 보고도 어느 엔진이 만들었는지 안다
        metadata.put("engine", ENGINE);
        metadata.put("manualReviewRequired", !reviewNotes.isEmpty());
        ArrayNode notes = metadata.putArray("reviewNotes");
        reviewNotes.forEach(notes::add);
        return metadata;
    }
}
