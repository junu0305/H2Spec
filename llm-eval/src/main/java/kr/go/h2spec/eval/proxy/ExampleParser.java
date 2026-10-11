package kr.go.h2spec.eval.proxy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서에 적힌 응답 예제를 구조({@link ExampleNode})로 읽는다. 예제는 말줄임(…, ......), 둥근 따옴표, 닫히지 않은 태그,
 * 예제 앞의 설명("1.1 이전 버전"), 접힌 트리 표시(-&lt;response&gt;)가 섞여 XML·JSON 파서로는 읽히지 않는다.
 * 그래서 이름과 중첩만 느슨하게 읽고 나머지는 건너뛴다. 대리 지표용 휴리스틱이라 틀릴 수 있다.
 */
final class ExampleParser {

    /** 예제 형식 */
    enum Format { XML, JSON }

    /**
     * 읽은 예제.
     *
     * @param format   형식
     * @param document 문서 노드. 자식이 루트(XML 루트 요소, JSON 최상위 키)다
     */
    record Parsed(Format format, ExampleNode document) {
    }

    /** XML 선언이나 여는 태그의 시작 */
    private static final Pattern XML_START = Pattern.compile("<\\?xml|<[A-Za-z_][\\w.:-]*[\\s/>]");
    /** 키를 따옴표로 감싼 JSON 객체의 시작. 주소의 {your_appkey}·{params(key=value)}는 걸리지 않는다 */
    private static final Pattern JSON_START = Pattern.compile("\\[\\s*\\{\\s*[\"“”']|\\{\\s*[\"“”']");
    private static final Pattern XML_TOKEN = Pattern.compile(
            "<!--.*?-->|<!\\[CDATA\\[(.*?)]]>|<\\?.*?\\?>|<![^>]*>"
                    + "|</\\s*([A-Za-z_][\\w.:-]*)\\s*>"
                    + "|<([A-Za-z_][\\w.:-]*)(\\s[^<>]*?)?(/?)>",
            Pattern.DOTALL);
    /** 응답이 아니라 HTML 조각(AJAX 샘플 소스 등)의 루트 */
    private static final Set<String> HTML_ROOTS = Set.of("html", "head", "body", "link", "script", "meta", "style",
            "div", "span", "p", "a", "table", "tr", "td", "br", "img", "form", "input", "iframe", "ul", "li", "font");

    private ExampleParser() {
    }

    /**
     * 글에서 응답 예제를 찾아 읽는다. XML과 JSON 중 글에서 먼저 시작하는 쪽으로 읽고, 예제가 아니면 다른 쪽을 본다.
     *
     * @param text 칸이나 문단(들)의 글
     * @return 읽은 예제. XML 루트 아래 요소가 없거나 JSON 키가 없으면(예제가 아니면) null
     */
    static Parsed parse(String text) {
        Matcher xml = XML_START.matcher(text);
        Matcher json = JSON_START.matcher(text);
        int xmlAt = xml.find() ? xml.start() : -1;
        int jsonAt = json.find() ? json.start() : -1;
        Parsed first = null;
        if (xmlAt >= 0 && (jsonAt < 0 || xmlAt < jsonAt)) {
            first = xml(text, xmlAt);
            if (first == null && jsonAt >= 0) {
                first = json(text, jsonAt);
            }
        } else if (jsonAt >= 0) {
            first = json(text, jsonAt);
            if (first == null && xmlAt >= 0) {
                first = xml(text, xmlAt);
            }
        }
        return first;
    }

    /** 칸이나 문단이 예제를 시작하는 줄인지. 여러 문단·칸에 나눠 적은 예제를 이을 때 쓴다 */
    static boolean startsExample(String text) {
        String stripped = text.strip();
        return stripped.matches("(?s)(-?<[A-Za-z?!]|[{\\[]).*");
    }

    /** 앞 줄에 이어지는 예제 줄인지 */
    static boolean continuesExample(String text) {
        String stripped = text.strip();
        return stripped.matches("(?s)(-?<|[{}\\[\\]\"“”'…]|\\.{2,}).*");
    }

    private static Parsed xml(String text, int start) {
        ExampleNode document = new ExampleNode("");
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(document));
        Matcher token = XML_TOKEN.matcher(text);
        token.region(start, text.length());
        int last = start;
        int opened = 0;
        int closed = 0;
        while (token.find()) {
            stack.peek().addText(text.substring(last, token.start()));
            last = token.end();
            if (token.group(2) != null) {
                closed += close(stack, token.group(2)) ? 1 : 0;
                if (stack.size() == 1 && !document.isLeaf()) {
                    // 루트가 닫혔다. 뒤의 글은 예제 밖이다
                    break;
                }
            } else if (token.group(3) != null) {
                if (stack.size() == 1 && !document.isLeaf()) {
                    break;
                }
                Frame parent = stack.peek();
                String name = token.group(3);
                ExampleNode node = parent.node.child(name);
                if (!parent.seen.add(name)) {
                    node.markRepeated();
                }
                if (token.group(5).isEmpty()) {
                    stack.push(new Frame(node));
                    opened++;
                }
            } else if (token.group(1) != null) {
                stack.peek().addText(token.group(1));
            }
        }
        // 실제 예제는 요소를 닫는다. 설명 문장 속 태그 이름("응답 메세지 중 <dataTime>이 …")은 닫히지 않는다
        if (document.isLeaf() || closed == 0 || closed * 2 < opened) {
            return null;
        }
        ExampleNode root = document.children().values().iterator().next();
        if (root.isLeaf() || HTML_ROOTS.contains(root.name().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return new Parsed(Format.XML, document);
    }

    /**
     * 닫는 태그와 같은 이름의 열린 요소까지 닫는다. 열린 적 없는 이름이면 무시한다 (예제의 오타).
     * 글과 함께 닫히지 않은 자식을 담은 요소는 글 필드로 본다. 글 안에 마크업을 그대로 적은 값
     * ({@code <TITLE>01"> <label>1</label> <element-citation …> …</TITLE>})이나 {@code <br>}은 데이터 구조가 아니다.
     * 글 없이 자식만 담은 요소는 자식 하나를 닫지 않은 오타일 수 있어 구조를 그대로 둔다.
     *
     * @return 열린 요소를 닫았으면 true
     */
    private static boolean close(Deque<Frame> stack, String name) {
        boolean open = stack.stream().anyMatch(frame -> frame.node.name().equals(name));
        if (!open) {
            return false;
        }
        boolean implicit = false;
        while (stack.size() > 1) {
            Frame frame = stack.pop();
            if (frame.node.name().equals(name)) {
                if (implicit && frame.hasText) {
                    frame.node.markTextOnly();
                }
                return true;
            }
            implicit = true;
        }
        return true;
    }

    /** 열린 요소 하나. 이번에 열린 요소 안에서 같은 이름의 자식이 다시 나오면 반복으로 본다 */
    private static final class Frame {
        private final ExampleNode node;
        private final Set<String> seen = new HashSet<>();
        /** 이번에 열린 요소 바로 아래에 글이 있었는지 */
        private boolean hasText;

        private Frame(ExampleNode node) {
            this.node = node;
        }

        private void addText(String value) {
            hasText |= !value.isBlank();
            node.addText(value);
        }
    }

    private static Parsed json(String text, int start) {
        ExampleNode document = new ExampleNode("");
        JsonReader reader = new JsonReader(text, start);
        if (text.charAt(start) == '[') {
            reader.pos++;
            document.markRepeated();
            reader.array(document);
        } else {
            reader.pos++;
            reader.object(document);
        }
        return document.isLeaf() ? null : new Parsed(Format.JSON, document);
    }

    /** 느슨한 JSON 읽기. 키·중첩·배열만 따라가고 말줄임과 깨진 따옴표는 건너뛴다 */
    private static final class JsonReader {
        private final String text;
        private int pos;

        private JsonReader(String text, int pos) {
            this.text = text;
            this.pos = pos;
        }

        /** '{' 다음부터 짝이 맞는 '}'까지 읽어 키를 node의 자식으로 더한다 */
        void object(ExampleNode node) {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '}') {
                    pos++;
                    return;
                }
                if (isQuote(c)) {
                    String key = string();
                    skipSpace();
                    if (pos < text.length() && text.charAt(pos) == ':') {
                        pos++;
                        value(node.child(key));
                    }
                } else {
                    // 공백·쉼표·말줄임(…, ......) 같은 군더더기
                    pos++;
                }
            }
        }

        private void value(ExampleNode node) {
            skipSpace();
            if (pos >= text.length()) {
                return;
            }
            char c = text.charAt(pos);
            if (c == '{') {
                pos++;
                object(node);
            } else if (c == '[') {
                pos++;
                node.markRepeated();
                array(node);
            } else if (isQuote(c)) {
                node.addText(string());
            } else {
                node.addText(literal());
            }
        }

        /** '[' 다음부터 ']'까지 읽는다. 객체 원소의 키는 모두 node의 자식으로 합친다 */
        void array(ExampleNode node) {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ']') {
                    pos++;
                    return;
                }
                if (c == '{') {
                    pos++;
                    object(node);
                } else if (c == '[') {
                    pos++;
                    array(node);
                } else if (isQuote(c)) {
                    node.addText(string());
                } else if (Character.isLetterOrDigit(c) || c == '-') {
                    node.addText(literal());
                } else {
                    pos++;
                }
            }
        }

        /**
         * 따옴표로 감싼 글. 여는 따옴표와 닫는 따옴표가 다른 예제("Y“)가 있어 ", “, ” 중 하나면 닫힌 것으로 본다.
         * 줄이 바뀌면 따옴표를 빠뜨린 것으로 보고 거기서 끊는다
         */
        private String string() {
            char open = text.charAt(pos++);
            StringBuilder value = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\n' || c == '\r') {
                    break;
                }
                pos++;
                if (c == '\\' && open != '\'' && pos < text.length()) {
                    value.append(text.charAt(pos++));
                } else if (open == '\'' ? c == '\'' : (c == '"' || c == '“' || c == '”')) {
                    break;
                } else {
                    value.append(c);
                }
            }
            return value.toString();
        }

        private String literal() {
            int begin = pos;
            while (pos < text.length() && ",}]\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
            return text.substring(begin, pos);
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isQuote(char c) {
            return c == '"' || c == '“' || c == '”' || c == '\'';
        }
    }
}
