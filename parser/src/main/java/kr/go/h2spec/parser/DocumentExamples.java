package kr.go.h2spec.parser;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오퍼레이션 구간의 글(문단과 필드 표가 아닌 표의 칸)에서 응답 예제(XML, JSON)와 요청 예제 주소를 찾아 읽는다.
 * 응답 필드 표는 성공코드와 응답의 계층을 알려 주지 않는다. 기관이 실제 응답을 붙여 넣은 예제가 그 근거다.
 * <p>
 * 예제는 손으로 고친 흔적(말줄임 줄, 브라우저 XML 보기에서 복사한 "-" 표지, 둥근 따옴표, 끝 쉼표)이 많아 그것만 걷어 내고 읽는다.
 * 그래도 읽지 못한 예제는 버린다. 예제를 하나도 읽지 못하면 부르는 쪽은 지금까지의 규칙을 그대로 쓴다.
 */
final class DocumentExamples {

    /** 게이트웨이 오류 응답의 루트. 정상 응답의 구조와 성공코드를 알려 주지 않는다 */
    private static final String GATEWAY_ERROR_ROOT = "OpenAPI_ServiceResponse";
    private static final List<String> RESULT_CODE_NAMES = List.of("resultCode", "RESULT_CODE", "result_code", "resultcode");
    private static final List<String> RESULT_MSG_NAMES =
            List.of("resultMsg", "RESULT_MSG", "result_msg", "resultMessage", "RESULT_MESSAGE", "resultmsg");
    /** 정상 처리를 뜻하는 결과 메시지 */
    private static final Pattern NORMAL_MESSAGE =
            Pattern.compile("(?i)^\\s*(normal[ _]?(service|code)?\\.?|success(ful)?\\.?|ok|정상.*|성공.*)\\s*$");
    /** 결과코드로 받아들일 꼴 */
    private static final Pattern RESULT_CODE_VALUE = Pattern.compile("[A-Za-z0-9_-]{1,12}");
    /** 결과코드를 찾는 깊이. 데이터 항목 안의 같은 이름은 보지 않는다 */
    private static final int RESULT_SEARCH_DEPTH = 3;
    private static final Pattern XML_START = Pattern.compile("<([A-Za-z_][\\w.:-]*)(\\s[^<>]*)?>");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
    /** 브라우저의 XML 보기에서 복사한 접기 표지 "-<item>" */
    private static final Pattern FOLD_MARK = Pattern.compile("(?m)^(\\s*)[-+]\\s*(?=<)");
    /** 말줄임만 있는 줄 */
    private static final Pattern ELLIPSIS_LINE = Pattern.compile("(?m)^\\s*(\\.{2,}|…+|⋮|중략|생략|\\(중략\\)|\\(생략\\)|이하 생략)\\s*,?\\s*$\\n?");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA,
                    JsonReadFeature.ALLOW_SINGLE_QUOTES, JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS,
                    JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .build();
    private static final DocumentBuilderFactory XML = secureXmlFactory();
    /**
     * 닫히지 않은 태그 같은 문서 예제의 오타를 표준 오류에 찍지 않고 예외로만 알린다. 처리기를 주지 않으면 JDK 파서가
     * 변환은 이어 가면서 "[Fatal Error] …"를 찍는다. 그런 예제는 건너뛴다
     */
    private static final ErrorHandler QUIET_XML_ERRORS = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) {
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    };

    private final List<Example> examples;
    private final List<String> urls;

    private DocumentExamples(List<Example> examples, List<String> urls) {
        this.examples = examples;
        this.urls = urls;
    }

    /** 예제가 없는 구간 */
    static DocumentExamples none() {
        return new DocumentExamples(List.of(), List.of());
    }

    /** @param text 오퍼레이션 구간의 글. 문단과 표의 칸을 줄바꿈으로 이은 것 */
    static DocumentExamples of(String text) {
        if (text == null || text.isBlank()) {
            return none();
        }
        List<Example> found = new ArrayList<>();
        found.addAll(xmlExamples(text));
        found.addAll(jsonExamples(text));
        List<String> urls = new ArrayList<>();
        Matcher m = URL.matcher(text.replace("&amp;", "&"));
        while (m.find()) {
            urls.add(m.group().replaceAll("[).,;\\]]+$", ""));
        }
        return new DocumentExamples(List.copyOf(found), List.copyOf(urls));
    }

    List<Example> examples() {
        return examples;
    }

    /**
     * 정상 응답 예제의 결과코드. 결과 메시지가 정상 처리를 뜻하는 예제만 본다(NORMAL SERVICE, SUCCESS, 정상).
     * 오류 응답 예제와 게이트웨이 오류 예제는 성공코드를 알려 주지 않는다. 정상 예제끼리 코드가 다르면 앞 예제를 쓴다.
     */
    Optional<String> successCode() {
        for (Example example : examples) {
            if (GATEWAY_ERROR_ROOT.equals(example.root().name())) {
                continue;
            }
            String code = example.root().find(RESULT_CODE_NAMES, RESULT_SEARCH_DEPTH);
            String message = example.root().find(RESULT_MSG_NAMES, RESULT_SEARCH_DEPTH);
            if (code != null && message != null && isCode(code)
                    && NORMAL_MESSAGE.matcher(message).matches()) {
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }

    /**
     * 에러 코드 표에서 정상 처리 행의 코드("00 | NORMAL_SERVICE | 정상"). 정상 메시지 칸 바로 앞 칸, 없으면 바로 뒤 칸이
     * 코드 꼴이면 그 값이다. 정상 행이 여럿이면 앞 행을 쓴다.
     */
    static Optional<String> successCodeOfErrorTable(List<List<String>> rows) {
        for (List<String> row : rows) {
            List<String> cells = row.stream().map(String::strip).filter(c -> !c.isEmpty()).toList();
            for (int i = 0; i < cells.size(); i++) {
                if (!NORMAL_MESSAGE.matcher(cells.get(i)).matches()) {
                    continue;
                }
                for (int j : new int[]{i - 1, i + 1}) {
                    if (j >= 0 && j < cells.size() && isCode(cells.get(j))) {
                        return Optional.of(cells.get(j));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 결과코드 꼴. 숫자가 하나는 있어야 한다(정상 메시지 옆의 국문 설명을 코드로 잡지 않는다) */
    private static boolean isCode(String cell) {
        return RESULT_CODE_VALUE.matcher(cell).matches() && cell.chars().anyMatch(Character::isDigit);
    }

    /**
     * 주소가 오퍼레이션 주소와 같은 요청 예제의 질의 파라미터 이름. 스킴과 기본 포트는 가리지 않는다.
     *
     * @param operationUrl 오퍼레이션 주소(질의 없음). 경로 변수 {이름} 자리는 아무 마디와도 맞는다
     */
    List<String> exampleQueryNames(String operationUrl) {
        Pattern target = Pattern.compile(Pattern.quote(stripScheme(operationUrl))
                .replaceAll("\\{[^}]*}", "\\\\E[^/?]+\\\\Q").replace("\\Q\\E", ""));
        for (String url : urls) {
            int query = url.indexOf('?');
            if (query < 0 || !target.matcher(stripScheme(url.substring(0, query))).matches()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String pair : url.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }

    private static String stripScheme(String url) {
        return url.replaceFirst("^https?://", "").replaceFirst(":(80|443)(?=/|$)", "").replaceAll("/+$", "");
    }

    // ---- 예제 찾기 ----

    /** {@code <루트>…</루트>} 덩어리. 하위 요소가 있는 것만 응답 예제로 본다 */
    private static List<Example> xmlExamples(String text) {
        List<Example> out = new ArrayList<>();
        Matcher m = XML_START.matcher(text);
        int from = 0;
        while (m.find(from)) {
            String name = m.group(1);
            int close = text.indexOf("</" + name + ">", m.end());
            if (close < 0 || text.substring(m.end(), close).indexOf('<') < 0) {
                from = m.end();
                continue;
            }
            int end = close + name.length() + 3;
            parseXml(text.substring(m.start(), end)).ifPresent(root -> out.add(new Example(Example.Format.XML, root)));
            from = end;
        }
        return out;
    }

    private static List<Example> jsonExamples(String text) {
        List<Example> out = new ArrayList<>();
        String t = text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'');
        int i = 0;
        while ((i = t.indexOf('{', i)) >= 0) {
            int end = balancedEnd(t, i);
            if (end < 0) {
                i++;
                continue;
            }
            String candidate = t.substring(i, end + 1);
            Optional<ExNode> root = candidate.indexOf(':') > 0 ? parseJson(candidate) : Optional.empty();
            if (root.isPresent()) {
                out.add(new Example(Example.Format.JSON, root.get()));
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** start의 { 와 짝인 } 의 자리. 따옴표 안의 괄호는 세지 않는다. 짝이 없으면 -1 */
    private static int balancedEnd(String t, int start) {
        int depth = 0;
        boolean inString = false;
        for (int j = start; j < t.length(); j++) {
            char c = t.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return c == '}' ? j : -1;
                }
            }
        }
        return -1;
    }

    private static String clean(String example) {
        return ELLIPSIS_LINE.matcher(FOLD_MARK.matcher(example).replaceAll("$1")).replaceAll("");
    }

    private static Optional<ExNode> parseXml(String raw) {
        String cleaned = clean(raw).replaceAll("&(?![A-Za-z]+;|#\\d+;|#x[0-9A-Fa-f]+;)", "&amp;");
        try {
            var builder = XML.newDocumentBuilder();
            builder.setErrorHandler(QUIET_XML_ERRORS);
            var document = builder.parse(new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8)));
            return Optional.of(fromXml(document.getDocumentElement()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static ExNode fromXml(Element element) {
        List<ExNode> children = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                counts.merge(child.getTagName(), 1, Integer::sum);
            }
        }
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                ExNode node = fromXml(child);
                children.add(counts.get(child.getTagName()) > 1 ? node.asRepeated() : node);
            }
        }
        String text = children.isEmpty() ? element.getTextContent().strip() : null;
        return new ExNode(element.getTagName(), List.copyOf(children), text, false);
    }

    private static Optional<ExNode> parseJson(String raw) {
        try {
            JsonNode tree = JSON.readTree(clean(raw));
            if (tree == null || !tree.isObject() || tree.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(fromJson(null, tree, false));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 배열은 원소마다 같은 이름의 반복 노드로 펼친다. XML의 반복 요소와 같은 꼴이다 */
    private static ExNode fromJson(String name, JsonNode value, boolean repeated) {
        if (!value.isObject()) {
            return new ExNode(name, List.of(), value.isNull() ? "" : value.asText(), repeated);
        }
        List<ExNode> children = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (field.getValue().isArray()) {
                if (field.getValue().isEmpty()) {
                    children.add(new ExNode(field.getKey(), List.of(), "", true));
                }
                for (JsonNode element : field.getValue()) {
                    children.add(fromJson(field.getKey(), element, true));
                }
            } else {
                children.add(fromJson(field.getKey(), field.getValue(), false));
            }
        }
        return new ExNode(name, List.copyOf(children), null, repeated);
    }

    private static DocumentBuilderFactory secureXmlFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 파서의 XXE 방어 설정에 실패했습니다", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /**
     * 읽은 응답 예제 하나.
     *
     * @param root XML은 루트 요소. JSON은 이름 없는 최상위 객체
     */
    record Example(Format format, ExNode root) {

        enum Format { XML, JSON }
    }

    /**
     * 예제의 노드. XML 요소와 JSON 값을 같은 꼴로 둔다.
     *
     * @param name     요소·키 이름. JSON 최상위 객체는 null
     * @param children 하위 노드(문서 순서). 값이면 비어 있다
     * @param text     값. 하위 노드가 있으면 null
     * @param repeated 같은 이름으로 되풀이되는 노드인지(XML의 반복 요소, JSON 배열의 원소)
     */
    record ExNode(String name, List<ExNode> children, String text, boolean repeated) {

        ExNode asRepeated() {
            return new ExNode(name, children, text, true);
        }

        boolean isLeaf() {
            return children.isEmpty();
        }

        /** 이름 후보 순서대로, maxDepth 깊이까지 처음 나오는 값 */
        String find(List<String> names, int maxDepth) {
            for (String name : names) {
                String value = find(name, maxDepth);
                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
            return null;
        }

        private String find(String wanted, int depth) {
            for (ExNode child : children) {
                if (wanted.equals(child.name) && child.isLeaf()) {
                    return child.text;
                }
            }
            if (depth <= 0) {
                return null;
            }
            for (ExNode child : children) {
                String value = child.find(wanted, depth - 1);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        /** 이 노드 아래 값 노드의 이름별 경로. 경로는 이 노드의 자식부터 센다 */
        Map<String, List<List<ExNode>>> leafPaths() {
            Map<String, List<List<ExNode>>> paths = new LinkedHashMap<>();
            collect(new ArrayList<>(), paths);
            return paths;
        }

        private void collect(List<ExNode> prefix, Map<String, List<List<ExNode>>> paths) {
            Set<String> seen = new java.util.HashSet<>();
            for (ExNode child : children) {
                // 되풀이되는 원소는 첫 것만 본다. 원소마다 필드가 다르면 모아서 본다
                String key = child.name + (child.isLeaf() ? "" : "/");
                List<ExNode> path = new ArrayList<>(prefix);
                path.add(child);
                if (child.isLeaf()) {
                    if (seen.add(key)) {
                        List<List<ExNode>> list = paths.computeIfAbsent(child.name, k -> new ArrayList<>());
                        if (list.stream().noneMatch(p -> samePath(p, path))) {
                            list.add(path);
                        }
                    }
                } else {
                    child.collect(path, paths);
                }
            }
        }

        private static boolean samePath(List<ExNode> a, List<ExNode> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).name().equals(b.get(i).name())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 결과코드·결과메시지 이름인지. 대소문자와 _를 가리지 않는다 */
    static boolean isResultName(String name) {
        String key = name.replace("_", "").toLowerCase(Locale.ROOT);
        return key.equals("resultcode") || key.equals("resultmsg") || key.equals("resultmessage");
    }
}
