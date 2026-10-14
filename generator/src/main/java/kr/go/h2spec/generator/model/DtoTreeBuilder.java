package kr.go.h2spec.generator.model;

import kr.go.h2spec.generator.ir.ResponseField;

import java.util.List;

/** responseFields의 점(.) 구분 경로 목록을 단일 루트 트리로 변환한다. [] 접미사는 List를 뜻한다. */
public class DtoTreeBuilder {

    private static final String LIST_SUFFIX = "[]";

    // TODO(루트 여럿): 응답 경로의 첫 칸(루트)이 하나여야 해서, 맨 바깥에 키가 여럿인 JSON 응답은 받지 못한다(아래 예외).
    //   샘플 문서: kosha-certificate-check({"header": …, "body": …}), jejudatahub-wifi-usage({"totCnt", "hasMore", "data"}).
    //   지금 두 엔진의 처리: llm 엔진은 이런 오퍼레이션을 지원 범위 밖으로 건너뛴다(IrNormalizer.responseFields).
    //   rule 엔진은 응답을 늘 표준 구조(response.header·response.body)로 가정해 경로를 만들어(IrAssembler.pathFor),
    //   컴파일은 되지만 실제 응답을 읽지 못하는 DTO가 나온다.
    //   고칠 것: 루트 없는 DTO(최상위 필드를 그대로 갖는 클래스)를 만들고, 그때는 ClientEmitter의 JSON 루트 풀기(UNWRAP_ROOT_VALUE)와
    //   DtoEmitter의 @JsonRootName·@JacksonXmlRootElement를 쓰지 않는다. rule 엔진이 이런 응답을 알아보는 방법도 정한다.
    //   관련 자리는 "TODO(루트 여럿)"으로 찾는다.
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
