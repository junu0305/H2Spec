package kr.go.h2spec.parser;

import java.util.List;

/**
 * rule 엔진의 공개 진입점. Reader가 만든 {@link Block} 목록을 오퍼레이션별 IR로 조립한다.
 * llm 엔진의 교차검증은 같은 Block 목록으로 이 엔진을 돌려 결과를 견준다.
 * <p>
 * 기존 파서(DocxSpecParser 등)와 달리 오퍼레이션을 하나도 찾지 못해도 예외를 던지지 않고 빈 결과를 돌려준다.
 * 교차검증에서는 rule 엔진이 찾지 못했다는 것 자체가 비교할 결과이기 때문이다.
 */
public final class RuleEngine {

    /**
     * Block 목록을 오퍼레이션별 IR로 조립한다.
     *
     * @param blocks       Reader가 만든 문서의 블록 목록
     * @param sourceFile   IR metadata에 적을 원본 파일 이름
     * @param sourceFormat IR metadata에 적을 원본 포맷 (DOCX, HWP, HWPX)
     * @return 조립 결과. 찾은 오퍼레이션이 없으면 빈 결과
     */
    public Result assemble(List<Block> blocks, String sourceFile, String sourceFormat) {
        SpecBlockAssembler assembler = new SpecBlockAssembler();
        List<ParsedApi> apis = assembler.parse(blocks, sourceFile, sourceFormat);
        return new Result(apis, assembler.skipped(), assembler.operations());
    }

    /**
     * 조립 결과.
     *
     * @param apis       변환한 오퍼레이션의 IR (문서 순서). 기존 파서의 parse 결과와 같다
     * @param skipped    건너뛴 오퍼레이션과 이유 ("오퍼레이션 2: 응답 필드 표를 찾지 못했습니다"). 기존 파서의 skipped()와 같다
     * @param operations 찾은 오퍼레이션마다 하나씩 (문서 순서). 변환한 것과 건너뛴 것을 모두 담는다.
     *                   뒤 오퍼레이션에 붙는 공통 사항 구간은 오퍼레이션이 아니라 담지 않는다
     */
    public record Result(List<ParsedApi> apis, List<String> skipped, List<OperationResult> operations) {

        /** 목록은 바꿀 수 없게 복사해 둔다 */
        public Result {
            apis = List.copyOf(apis);
            skipped = List.copyOf(skipped);
            operations = List.copyOf(operations);
        }
    }

    /**
     * 찾은 오퍼레이션 하나. 교차검증은 영문 오퍼레이션명, 국문 기능명, 문서 안 순서로 두 엔진의 오퍼레이션을 짝짓는다.
     * 주소로 짝짓지 않는 것은 주소가 틀리면 짝이 지어지지 않아 정작 주소 차이를 놓치기 때문이다.
     *
     * @param operationName 정보 표의 영문 오퍼레이션명. 없으면 null
     * @param title         정보 표의 국문 기능명(한 줄로 이은 것). 없으면 null
     * @param api           변환한 IR. 건너뛰었으면 null
     * @param skipReason    건너뛴 이유. 변환했으면 null
     */
    public record OperationResult(String operationName, String title, ParsedApi api, String skipReason) {
    }
}
