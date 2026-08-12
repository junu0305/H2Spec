package kr.go.h2spec.generator.model;

import kr.go.h2spec.generator.ir.ResponseField;

import java.util.List;

/** responseFields의 점(.) 구분 경로 목록을 단일 루트 트리로 변환한다. [] 접미사는 List를 뜻한다. */
public class DtoTreeBuilder {

    private static final String LIST_SUFFIX = "[]";

    public DtoNode build(List<ResponseField> fields) {
        DtoNode root = null;
        for (ResponseField field : fields) {
            String[] segments = field.path().split("\\.");
            String rootName = stripListSuffix(segments[0]);
            if (root == null) {
                root = new DtoNode(rootName, isListSegment(segments[0]));
            } else if (!root.name().equals(rootName)) {
                throw new IllegalArgumentException(
                        "응답 경로의 루트가 하나가 아님: " + root.name() + " vs " + rootName);
            }
            DtoNode current = root;
            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i];
                current = current.getOrCreateChild(
                        stripListSuffix(segment), isListSegment(segment), field.path());
            }
            current.markLeaf(field.type(), field.description(), field.path());
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
