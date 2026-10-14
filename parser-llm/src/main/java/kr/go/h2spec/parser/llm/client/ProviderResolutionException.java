package kr.go.h2spec.parser.llm.client;

/**
 * llm 엔진에 쓸 회사를 정하지 못했다. 키가 여러 회사 것이거나, 키가 없거나, 회사 이름이 틀렸을 때다.
 * 메시지는 사용자에게 그대로 보여 줄 한 줄이고, 키 값은 담지 않는다.
 */
public class ProviderResolutionException extends IllegalArgumentException {

    ProviderResolutionException(String message) {
        super(message);
    }
}
