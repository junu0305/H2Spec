package kr.go.h2spec.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * rule 엔진의 {@link SpecParser}. 확장자로 고른 Reader({@link DocumentReaders})가 만든 Block 목록을
 * {@link RuleEngine}으로 조립한다.
 * <p>
 * 기존 파서 3종(DocxSpecParser, HwpSpecParser, HwpxSpecParser)은 Reader와 sourceFormat만 다른 같은 코드라
 * 이것으로 합쳤다. 세 클래스는 지금 동작을 지키는 래퍼로 남아 같은 조립 코드를 부른다.
 */
public final class RuleSpecParser implements SpecParser {

    /**
     * {@inheritDoc}
     * <p>
     * 변환한 오퍼레이션이 없으면 기존 파서와 같은 메시지("문서에서 상세기능 명세를 찾지 못했습니다: 파일명")로
     * {@link SpecParseException.Kind#FAILED}를 던진다. 건너뛴 오퍼레이션이 있으면 그 이유를 메시지에 붙인다.
     */
    @Override
    public SpecParseResult parse(Path document) throws IOException, SpecParseException {
        DocumentReaders.Format format = DocumentReaders.find(document).orElseThrow(
                () -> new IllegalArgumentException("명세 문서(.docx, .hwp, .hwpx)가 아닙니다: " + document.getFileName()));
        SpecParseResult result = assemble(format.read(document), document.getFileName().toString(), format.sourceFormat());
        if (result.apis().isEmpty()) {
            throw new SpecParseException(SpecParseException.Kind.FAILED,
                    SpecBlockAssembler.notFoundMessage(document.getFileName().toString(), result.skipped()));
        }
        return result;
    }

    /**
     * 주어진 Reader로 읽어 조립한다. 변환한 오퍼레이션이 없어도 던지지 않는다.
     * 기존 파서 3종은 확장자와 상관없이 자기 Reader로 읽고, 실패한 뒤에도 skipped()로 건너뛴 이유를 알려 왔으므로
     * 확장자로 고르지 않는 이 진입점을 부른다.
     *
     * @param document     읽을 문서. 파일 이름이 IR metadata의 sourceFile이 된다
     * @param reader       문서를 읽을 Reader
     * @param sourceFormat IR metadata의 sourceFormat
     * @return 조립 결과. rule 엔진은 지원 범위를 판정하지 않으므로 지원 범위 밖으로 건너뛴 오퍼레이션이 없다
     * @throws IOException 문서를 읽지 못했을 때
     */
    static SpecParseResult assemble(Path document, DocumentReader reader, String sourceFormat) throws IOException {
        return assemble(reader.read(document), document.getFileName().toString(), sourceFormat);
    }

    private static SpecParseResult assemble(List<Block> blocks, String sourceFile, String sourceFormat) {
        RuleEngine.Result result = new RuleEngine().assemble(blocks, sourceFile, sourceFormat);
        List<SpecParseResult.Operation> operations = result.operations().stream()
                .map(operation -> new SpecParseResult.Operation(operation.operationName(), operation.title(),
                        operation.api(), operation.skipReason(), false))
                .toList();
        return new SpecParseResult(operations, result.skipped(), SpecParseResult.Engine.RULE);
    }
}
