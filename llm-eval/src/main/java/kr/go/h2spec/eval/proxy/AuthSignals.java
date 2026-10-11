package kr.go.h2spec.eval.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.eval.EngineOperation;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * serviceKey 쿼리가 아닌 인증 방식의 흔적을 찾는다 (대리 지표 (d)). generator는 이름이 serviceKey(대소문자 무시)인
 * 파라미터만 인증키로 알아보므로(ClientEmitter), 이런 문서는 지금 코드를 만들지 못한다. 휴리스틱이다.
 * <ul>
 *   <li>경로 키: 주소의 경로에 {@code {your_appkey}}처럼 키를 넣는 자리가 있다</li>
 *   <li>헤더 키: Authorization·X-…-Key 헤더나 "헤더에 인증키" 같은 설명, 위치 열이 header인 키 파라미터가 있다</li>
 *   <li>게이트웨이(apis.data.go.kr)가 아닌 주소에 serviceKey 파라미터가 없다. 이름이 다른 쿼리 키(apiKey, authApiKey)가
 *       있으면 "다른 이름의 쿼리 키", 키 같은 파라미터가 아예 없으면 "인증 파라미터 없음"으로 나눈다.
 *       게이트웨이 주소는 rule 엔진이 serviceKey를 채우므로 보지 않는다</li>
 * </ul>
 */
final class AuthSignals {

    /** 대리 지표 (d)의 세부 종류 */
    static final String PATH_KEY = "경로 키";
    static final String HEADER_KEY = "헤더 키";
    static final String OTHER_QUERY_KEY = "다른 이름의 쿼리 키";
    static final String NO_KEY_PARAMETER = "인증 파라미터 없음";

    /** 키를 담는 파라미터·자리 이름 (your_appkey, apiKey, authApiKey, accessToken, {인증키}) */
    private static final Pattern KEY_NAME = Pattern.compile("(?i).*(key|token|auth|인증키|서비스키).*");
    private static final Pattern GATEWAY_URL = Pattern.compile("(?i)^https?://apis\\.data\\.go\\.kr/");
    /** 주소 경로 안의 자리 표시. 질의 문자열(?serviceKey={인증키})의 것은 경로 키가 아니다 */
    private static final Pattern URL_PATH = Pattern.compile("(?i)https?://[^\\s?#\"'<>]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}/]+)}");
    private static final Pattern HEADER_TEXT = Pattern.compile(
            "(?i)authorization\\s*:|\\bx-[a-z0-9-]*(key|token)\\b"
                    + "|(헤더|header)\\s*(에|으로|로|값)?\\s*[^|\\n]{0,30}(인증\\s*키|api\\s*key|토큰|token|키\\s*값)");
    private static final Set<String> HEADER_LOCATIONS = Set.of("header", "헤더", "http header", "httpheader");

    private AuthSignals() {
    }

    /**
     * 흔적을 찾는다.
     *
     * @param blocks     문서 블록
     * @param operations rule 엔진의 오퍼레이션
     * @param evidence   찾은 근거를 더할 목록
     * @return 찾은 세부 종류 (찾은 순서)
     */
    static Set<String> kinds(List<Block> blocks, List<EngineOperation> operations, List<String> evidence) {
        Set<String> kinds = new LinkedHashSet<>();
        // 같은 주소를 요청 URL·예시·요청 예제에 거듭 적으므로 자리 표시마다 처음 나온 곳만 근거로 남긴다
        Set<String> placeholders = new LinkedHashSet<>();
        for (int b = 0; b < blocks.size(); b++) {
            String block = (blocks.get(b) instanceof Block.Table ? "T" : "P") + (b + 1);
            for (String text : texts(blocks.get(b))) {
                pathKeys(text, block, placeholders, evidence);
                if (HEADER_TEXT.matcher(text).find()) {
                    kinds.add(HEADER_KEY);
                    evidence.add(HEADER_KEY + ": " + shorten(text) + " (" + block + ")");
                }
            }
            if (blocks.get(b) instanceof Block.Table table) {
                headerParameters(table.rows(), block, kinds, evidence);
            }
        }
        if (!placeholders.isEmpty()) {
            kinds.add(PATH_KEY);
        }
        for (EngineOperation operation : operations) {
            if (operation.extracted()) {
                parameters(operation, kinds, evidence);
            }
        }
        return kinds;
    }

    private static void pathKeys(String text, String block, Set<String> placeholders, List<String> evidence) {
        Matcher url = URL_PATH.matcher(text);
        while (url.find()) {
            Matcher placeholder = PLACEHOLDER.matcher(url.group());
            while (placeholder.find()) {
                if (KEY_NAME.matcher(placeholder.group(1)).matches() && placeholders.add(placeholder.group())) {
                    evidence.add(PATH_KEY + ": 주소의 " + placeholder.group() + " (" + block + ")");
                }
            }
        }
    }

    /** 위치 열이 header인 행에 키 같은 이름이 있으면 헤더 키다 */
    private static void headerParameters(List<List<String>> rows, String block, Set<String> kinds,
                                         List<String> evidence) {
        for (List<String> row : rows) {
            boolean header = row.stream().anyMatch(cell -> HEADER_LOCATIONS.contains(cell.strip().toLowerCase(Locale.ROOT)));
            if (!header) {
                continue;
            }
            for (String cell : row) {
                String name = cell.strip();
                if (name.matches("[A-Za-z][A-Za-z0-9_-]*") && KEY_NAME.matcher(name).matches()) {
                    kinds.add(HEADER_KEY);
                    evidence.add(HEADER_KEY + ": 위치가 header인 " + name + " (" + block + ")");
                }
            }
        }
    }

    private static void parameters(EngineOperation operation, Set<String> kinds, List<String> evidence) {
        JsonNode api = operation.ir().path("api");
        List<String> pathKeys = new ArrayList<>();
        List<String> queryKeys = new ArrayList<>();
        boolean serviceKey = false;
        for (JsonNode param : api.path("requestParameters")) {
            String name = param.path("name").asText();
            serviceKey |= IrConventions.SERVICE_KEY.equalsIgnoreCase(name);
            if (KEY_NAME.matcher(name).matches()) {
                ("path".equals(param.path("in").asText()) ? pathKeys : queryKeys).add(name);
            }
        }
        if (!pathKeys.isEmpty()) {
            kinds.add(PATH_KEY);
            evidence.add(PATH_KEY + ": 경로 변수 " + pathKeys + " (" + operation.apiId() + ")");
        }
        String url = operation.url();
        if (serviceKey || GATEWAY_URL.matcher(url).find()) {
            return;
        }
        if (!queryKeys.isEmpty()) {
            kinds.add(OTHER_QUERY_KEY);
            evidence.add(OTHER_QUERY_KEY + ": " + queryKeys + " (" + url + ")");
        } else if (pathKeys.isEmpty()) {
            kinds.add(NO_KEY_PARAMETER);
            evidence.add(NO_KEY_PARAMETER + ": serviceKey도 키 같은 파라미터도 없음 (" + url + ")");
        }
    }

    private static List<String> texts(Block block) {
        if (block instanceof Block.Heading heading) {
            return List.of(heading.text());
        }
        List<String> cells = new ArrayList<>();
        for (List<String> row : ((Block.Table) block).rows()) {
            row.stream().filter(cell -> !cell.isBlank()).forEach(cells::add);
        }
        return cells;
    }

    private static String shorten(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= 60 ? line : line.substring(0, 60) + "…";
    }
}
