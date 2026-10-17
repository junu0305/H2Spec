package kr.go.h2spec.generator.model;

import kr.go.h2spec.generator.ir.ResponseField;

import java.util.List;

/** responseFields의 점(.) 구분 경로 목록을 단일 루트 트리로 변환한다. [] 접미사는 List를 뜻한다. */
public class DtoTreeBuilder {

    private static final String LIST_SUFFIX = "[]";

    /** 경로의 첫 칸이 응답을 감싼 루트인 응답({@code {"response": {…}}}, XML의 루트 요소) */
    public DtoNode build(List<ResponseField> fields) {
        return build(fields, false);
    }

    /**
     * @param rootless 응답이 루트 하나로 감싸이지 않고 맨 바깥에 필드가 바로 오는지({@code {"header": …, "body": …}}).
     *                 그러면 경로의 첫 칸이 맨 바깥 키이고, 이름 없는 루트 노드(응답 전체)가 그 칸들을 자식으로 갖는다.
     *                 아니면 첫 칸이 루트이고 모든 경로가 같은 루트 아래에 있어야 한다
     */
    public DtoNode build(List<ResponseField> fields, boolean rootless) {
        DtoNode root = rootless ? new DtoNode(null, false) : null;
        for (ResponseField field : fields) {
            String[] segments = field.path().split("\\.");
            int first = 0;
            if (!rootless) {
                root = rootOf(root, segments, field.path());
                first = 1;
            }
            DtoNode current = root;
            for (int i = first; i < segments.length; i++) {
                String segment = segments[i];
                current = current.getOrCreateChild(
                        stripListSuffix(segment), isListSegment(segment), field.path());
            }
            current.markLeaf(field.type(), field.description(), field.path());
        }
        return root;
    }

    /**
     * 경로의 첫 칸으로 루트를 정한다. 루트는 응답을 감싼 객체라 리스트나 값일 수 없다. 그런 경로로 만들면 응답을 읽지 못하는 DTO가
     * 조용히 나오므로 거부한다. 맨 바깥 키가 리스트이거나 값인 JSON 응답({@code {"totCnt": 1, "data": […]}})은 루트 없는 응답이다
     */
    private DtoNode rootOf(DtoNode root, String[] segments, String path) {
        String rootName = stripListSuffix(segments[0]);
        if (isListSegment(segments[0])) {
            throw new IllegalArgumentException("응답 경로의 루트가 리스트임: " + path
                    + " — 맨 바깥 키가 배열인 JSON 응답이면 api.responseRootless를 true로 적어 주세요.");
        }
        if (segments.length == 1) {
            throw new IllegalArgumentException("응답 경로에 루트 아래 필드가 없음: " + path
                    + " — 맨 바깥에 값이 바로 오는 JSON 응답이면 api.responseRootless를 true로 적어 주세요.");
        }
        if (root == null) {
            return new DtoNode(rootName, false);
        }
        if (!root.name().equals(rootName)) {
            throw new IllegalArgumentException(
                    "응답 경로의 루트가 하나가 아님: " + root.name() + " vs " + rootName
                    + " — 맨 바깥에 키가 여럿인 JSON 응답이면 api.responseRootless를 true로 적어 주세요.");
        }
        return root;
    }

    private boolean isListSegment(String segment) {
        return segment.endsWith(LIST_SUFFIX);
    }

    private String stripListSuffix(String segment) {
        return isListSegment(segment)
                ? segment.substring(0, segment.length() - LIST_SUFFIX.length())
                : segment;
    }
}
