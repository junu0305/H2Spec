package kr.go.h2spec.eval.proxy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 응답 예제의 구조. XML 요소나 JSON 키 하나가 노드 하나이고, 같은 부모 아래 같은 이름(반복되는 item, 배열 원소)은 한 노드로 합친다.
 * 구조만 보므로 값은 처음 본 것 하나만 남긴다. 예제 전체는 이름 없는 문서 노드 아래에 두어, JSON의 최상위 키 여럿과
 * XML의 루트 요소 하나를 같은 모양으로 다룬다.
 */
final class ExampleNode {

    private final String name;
    private final Map<String, ExampleNode> children = new LinkedHashMap<>();
    private boolean repeated;
    private String text;
    /** 글 안에 마크업을 그대로 적은 값이라 하위 태그를 구조로 보지 않는다 */
    private boolean textOnly;

    ExampleNode(String name) {
        this.name = name;
    }

    /** @return XML 요소 이름이나 JSON 키. 문서 노드는 빈 문자열 */
    String name() {
        return name;
    }

    /** @return 자식 노드 (처음 나온 순서) */
    Map<String, ExampleNode> children() {
        return Collections.unmodifiableMap(children);
    }

    /** @return 반복되는지 (XML의 같은 이름 형제, JSON 배열) */
    boolean repeated() {
        return repeated;
    }

    /** @return 처음 본 값. 값이 없으면 null */
    String text() {
        return text;
    }

    /** @return 자식이 없는 노드 (값을 담는 필드)인지 */
    boolean isLeaf() {
        return children.isEmpty();
    }

    /** 이름이 같은 자식이 있으면 그것을, 없으면 새로 만들어 돌려준다. 글 필드 안의 태그는 구조에 넣지 않는다 */
    ExampleNode child(String childName) {
        return textOnly ? new ExampleNode(childName) : children.computeIfAbsent(childName, ExampleNode::new);
    }

    void markRepeated() {
        repeated = true;
    }

    /** 글 필드로 바꾼다. 반복되는 요소의 다른 인스턴스에서 같은 태그가 다시 나와도 구조에 넣지 않는다 */
    void markTextOnly() {
        textOnly = true;
        children.clear();
    }

    void addText(String value) {
        String stripped = value.strip();
        if (text == null && !stripped.isEmpty()) {
            text = stripped;
        }
    }

    /**
     * 깊이 우선으로 처음 만나는, 이름이 맞는 노드를 찾는다.
     *
     * @param matches 이름 조건
     * @return 노드. 없으면 null
     */
    ExampleNode find(Predicate<String> matches) {
        for (ExampleNode child : children.values()) {
            if (matches.test(child.name)) {
                return child;
            }
            ExampleNode found = child.find(matches);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
