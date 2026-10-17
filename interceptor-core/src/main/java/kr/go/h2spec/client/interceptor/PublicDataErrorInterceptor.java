package kr.go.h2spec.client.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 공공데이터포털 계열 API의 HTTP 200 위장 에러를 감지하는 RestTemplate 인터셉터.
 * <p>
 * 응답 바디의 resultCode(또는 유사 필드)가 성공 코드가 아니거나 알려진 에러
 * 키워드를 포함하면 {@link PublicDataApiException}을 던집니다.
 * 숫자로만 된 결과코드는 앞자리 0을 무시하고 비교하므로 0, 00, 000을 같은 코드로 봅니다.
 * <p>
 * <b>주의:</b> InputStream은 한 번만 읽을 수 있으므로, 이 인터셉터를 사용하는
 * RestTemplate의 ClientHttpRequestFactory는 반드시
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory}로 감싸야
 * 이후 메시지 컨버터가 응답 바디를 다시 읽을 수 있습니다.
 *
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate(
 *     new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
 * );
 * restTemplate.getInterceptors().add(new PublicDataErrorInterceptor());
 * }</pre>
 */
public class PublicDataErrorInterceptor implements ClientHttpRequestInterceptor {
    private final PublicDataErrorDetector detector;
    /** 예외에서 값으로 가릴 인증키. 없으면 serviceKey 값만 가린다 */
    private final String serviceKey;

    public PublicDataErrorInterceptor() { this("00"); }
    public PublicDataErrorInterceptor(String successCode) {
        this.detector = new PublicDataErrorDetector(successCode);
        this.serviceKey = null;
    }

    // TODO(interceptor-core 배포): 아래 두 생성자는 배포된 v0.1.0 뒤에 더했고 generator가 만드는 코드가 부른다. 새 판을 배포해야 한다.
    //   자세한 내용은 generator의 ClientEmitter.appendDefaultRestTemplateFactory

    /**
     * 기본으로 찾는 이름(resultCode, RESULT_CODE, returnReasonCode, errorCode, ERROR_CODE)이 아닌 이름으로
     * 결과코드를 주는 API에 쓴다. 주어진 이름을 기본 이름보다 먼저 찾고, 없으면 기본 이름으로 찾는다.
     *
     * @param successCode 정상 처리를 뜻하는 결과코드
     * @param resultCodeKeys 결과코드가 담기는 XML 요소나 JSON 키의 이름(예: {@code resultCd}). 경로가 아니라 이름만 준다
     */
    public PublicDataErrorInterceptor(String successCode, List<String> resultCodeKeys) {
        this(successCode, resultCodeKeys, null);
    }

    /**
     * 인증키를 serviceKey가 아닌 이름의 질의(apiKey 등)나 주소 경로·요청 헤더로 보내는 API에 쓴다.
     * 예외의 요청 URI·결과메시지·바디 스니펫에서 키 값을 가린다({@link ServiceKeyMasker#mask(String, String)}).
     *
     * @param successCode 정상 처리를 뜻하는 결과코드
     * @param resultCodeKeys 결과코드가 담기는 XML 요소나 JSON 키의 이름. 기본 이름만 찾으면 빈 목록
     * @param serviceKey 요청에 넣는 인증키
     */
    public PublicDataErrorInterceptor(String successCode, List<String> resultCodeKeys, String serviceKey) {
        this.detector = new PublicDataErrorDetector(successCode, resultCodeKeys);
        this.serviceKey = serviceKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        byte[] rawBody = StreamUtils.copyToByteArray(response.getBody());
        String content = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) return response;

        PublicDataErrorDetector.DetectionResult result = detector.detect(content);
        if (result.isFailure()) {
            // 키가 잘린 채 남지 않도록 자르기 전에 가린다
            throw new PublicDataApiException(mask(request.getURI().toString()), response.getStatusCode().value(),
                    result.resultCode(), mask(result.resultMsg()), truncate(mask(content), 500));
        }
        return response;
    }

    private String mask(String text) {
        return ServiceKeyMasker.mask(text, serviceKey);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }
}
