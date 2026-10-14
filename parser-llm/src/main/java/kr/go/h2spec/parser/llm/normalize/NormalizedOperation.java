package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 정규화한 오퍼레이션 하나. 뒤 단계가 {@code SpecParseResult}의 오퍼레이션 기록으로 옮긴다.
 * <ul>
 *   <li>변환함: {@code ir}이 있고 {@code skipReason}이 null이다.</li>
 *   <li>지원 범위 밖으로 건너뜀: {@code ir}과 {@code skipReason}이 함께 있고 {@code outOfScope}가 true다.
 *       IR은 파일로 쓰지 않고 교차검증과 평가에만 쓴다. 올바르게 건너뛴 오퍼레이션이 손실로 세지지 않게 하기 위해서다.</li>
 *   <li>검증에서 건너뜀: {@code ir}이 null이고 {@code skipReason}만 있다.</li>
 * </ul>
 *
 * @param operationName 정보 표에서 확인한 영문 오퍼레이션명. 없으면 null
 * @param title         국문 기능명 (한 줄로 이은 것). 없으면 빈 문자열
 * @param apiId         주소로 만든 apiId. 주소를 정하지 못해 건너뛰었으면 null
 * @param ir            IR. 검증에서 건너뛰었으면 null
 * @param skipReason    건너뛴 이유. 변환했으면 null
 * @param outOfScope    generator가 지원하지 않아 건너뛰었으면 true
 * @param formatSelectable 문서가 두 응답 형식을 모두 내고, 생성 클라이언트가 포맷 파라미터로 IR의 형식을 골라 부르는지.
 *                         그러면 IR의 형식이 무엇이든 동작하므로 교차검증이 rule 엔진과의 형식 차이를 알리지 않는다
 */
public record NormalizedOperation(String operationName, String title, String apiId, ObjectNode ir,
                                  String skipReason, boolean outOfScope, boolean formatSelectable) {

    /** 형식을 고를 수 없는 오퍼레이션. 검증에서 건너뛴 것처럼 IR이 없거나 형식을 따질 일이 없는 경우다 */
    public NormalizedOperation(String operationName, String title, String apiId, ObjectNode ir,
                               String skipReason, boolean outOfScope) {
        this(operationName, title, apiId, ir, skipReason, outOfScope, false);
    }

    /** @return 파일로 쓸 IR이 있으면 true */
    public boolean converted() {
        return ir != null && skipReason == null;
    }
}
