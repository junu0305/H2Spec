package kr.go.h2spec.parser.llm.client;

/**
 * 회사 공통 호출 인터페이스. 회사별 어댑터가 구현한다.
 * <p>
 * 어댑터는 요청을 회사 형식으로 바꾸고, 응답에서 JSON 글과 사용량을 꺼내고, 회사별 오류·거절·잘림을
 * {@link LlmException.Kind}와 {@link LlmResponse.Finish}로 바꾸는 일만 한다. 프롬프트·스키마·검증은 회사와 상관없이 하나다.
 */
public interface LlmClient {

    /**
     * 요청 하나를 보내고 응답을 끝까지 받는다. 다시 부르면 나을 수 있는 실패는 설정한 횟수만큼 다시 부른 뒤에 던진다.
     *
     * @param request 보낼 시스템 프롬프트·문서·작업 지시·스키마
     * @return 모델이 낸 글과 사용량. 잘림과 거절은 예외가 아니라 {@link LlmResponse#finish()}로 알린다
     * @throws LlmException 호출이 실패했을 때. 실패 종류는 {@link LlmException#kind()}
     */
    LlmResponse complete(LlmRequest request) throws LlmException;
}
