package kr.go.h2spec.eval.proxy;

import java.util.Map;
import java.util.Set;

/**
 * 응답 예제가 공공데이터포털 표준 구조인지 본다. rule 엔진은 모든 응답을 이 구조로 가정해
 * {@code response.header.*}, {@code response.body.*}, {@code response.body.items.item[].*} 경로를 만든다 (IrAssembler).
 * <ul>
 *   <li>루트는 {@code response} 하나이고 그 아래에는 {@code header}·{@code body}만 온다</li>
 *   <li>{@code header} 아래는 값 필드만 온다</li>
 *   <li>{@code body} 아래는 값 필드와 {@code items}만 온다</li>
 *   <li>{@code items} 아래는 {@code item}만 오고 {@code item} 아래는 값 필드만 온다.
 *       JSON에서 {@code items}가 바로 배열인 모양도 표준이다. 생성 DTO가 표준 경로 하나로 두 모양을 다 받는다</li>
 * </ul>
 * 이름은 대소문자를 가린다. XML 태그와 JSON 키가 대소문자를 가리기 때문이다.
 */
final class StandardStructure {

    private static final String RESPONSE = "response";
    private static final String HEADER = "header";
    private static final String BODY = "body";
    private static final String ITEMS = "items";
    private static final String ITEM = "item";
    private static final Set<String> RESPONSE_CHILDREN = Set.of(HEADER, BODY);

    private StandardStructure() {
    }

    /**
     * 표준 구조와 다른 첫 자리를 찾는다.
     *
     * @param document 예제의 문서 노드
     * @return 다른 점의 설명. 표준 구조면 null
     */
    static String violation(ExampleNode document) {
        Map<String, ExampleNode> roots = document.children();
        if (roots.size() != 1 || !roots.containsKey(RESPONSE)) {
            return "루트가 response 하나가 아님: " + String.join(", ", roots.keySet());
        }
        ExampleNode response = roots.get(RESPONSE);
        if (response.isLeaf()) {
            return "response 아래에 header·body가 없음";
        }
        for (ExampleNode child : response.children().values()) {
            if (!RESPONSE_CHILDREN.contains(child.name())) {
                return "response 아래에 header·body가 아닌 " + child.name() + "이(가) 있음";
            }
        }
        ExampleNode header = response.children().get(HEADER);
        if (header != null) {
            String nested = firstContainer(header);
            if (nested != null) {
                return "header 아래에 하위 항목을 가진 " + nested + "이(가) 있음";
            }
        }
        ExampleNode body = response.children().get(BODY);
        if (body == null) {
            return null;
        }
        for (ExampleNode child : body.children().values()) {
            if (!child.name().equals(ITEMS) && !child.isLeaf()) {
                return "body 아래에 items가 아닌 컨테이너 " + child.name() + "이(가) 있음";
            }
        }
        ExampleNode items = body.children().get(ITEMS);
        return items == null ? null : itemsViolation(items);
    }

    private static String itemsViolation(ExampleNode items) {
        if (items.isLeaf()) {
            // 결과가 없는 예제의 빈 items
            return null;
        }
        if (items.repeated() && !items.children().containsKey(ITEM)) {
            // JSON의 "items": [ {...} ] 모양
            String nested = firstContainer(items);
            return nested == null ? null : "items 배열의 원소 아래에 하위 항목을 가진 " + nested + "이(가) 있음";
        }
        for (ExampleNode child : items.children().values()) {
            if (!child.name().equals(ITEM)) {
                return "items 아래에 item이 아닌 " + child.name() + "이(가) 있음";
            }
        }
        String nested = firstContainer(items.children().get(ITEM));
        return nested == null ? null : "item 아래에 하위 항목을 가진 " + nested + "이(가) 있음";
    }

    /** 값 필드만 와야 하는 자리에서 하위 항목을 가진 첫 자식의 이름 */
    private static String firstContainer(ExampleNode node) {
        for (ExampleNode child : node.children().values()) {
            if (!child.isLeaf()) {
                return child.name();
            }
        }
        return null;
    }
}
