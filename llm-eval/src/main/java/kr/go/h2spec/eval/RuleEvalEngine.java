package kr.go.h2spec.eval;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.DocumentReaders;
import kr.go.h2spec.parser.RuleEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * rule 엔진. 기존 파서와 달리 오퍼레이션을 찾지 못해도 예외 없이 빈 목록을 돌려주는 {@link RuleEngine}을 쓴다.
 * 평가에서는 찾지 못했다는 것 자체가 셀 결과이기 때문이다.
 */
public final class RuleEvalEngine implements EvalEngine {

    /** 리포트에 적는 엔진 이름 */
    public static final String NAME = "rule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<EngineOperation> extract(Path document) throws IOException {
        return operations(DocumentReaders.read(document), document);
    }

    /**
     * 이미 읽은 블록으로 rule 엔진을 돌린다. 대리 지표처럼 블록도 따로 보는 쪽이 문서를 두 번 읽지 않게 한다.
     *
     * @param blocks   문서의 블록
     * @param document 문서 경로. IR metadata의 파일 이름과 포맷을 정한다
     * @return 찾은 오퍼레이션 (문서 순서)
     */
    public static List<EngineOperation> operations(List<Block> blocks, Path document) {
        RuleEngine.Result result = new RuleEngine()
                .assemble(blocks, document.getFileName().toString(),
                        DocumentReaders.find(document).map(DocumentReaders.Format::sourceFormat).orElse(null));
        return result.operations().stream()
                .map(op -> new EngineOperation(op.operationName(), op.title(),
                        op.api() == null ? null : op.api().ir(), op.skipReason(), false))
                .toList();
    }
}
