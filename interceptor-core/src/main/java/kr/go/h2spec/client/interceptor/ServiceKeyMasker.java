package kr.go.h2spec.client.interceptor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 로그·예외 메시지에 공공데이터 인증키가 남지 않도록 serviceKey 값을 가린다.
 * URI뿐 아니라 에러 바디처럼 URL이 에코된 임의 텍스트에도 사용할 수 있다.
 */
public final class ServiceKeyMasker {

    private static final Pattern SERVICE_KEY_PATTERN =
            Pattern.compile("(serviceKey=)[^&\\s\"'<]*", Pattern.CASE_INSENSITIVE);
    private static final String REPLACEMENT = "$1****";
    private static final String MASK = "****";

    private ServiceKeyMasker() {
    }

    public static String mask(String text) {
        return text == null ? null : SERVICE_KEY_PATTERN.matcher(text).replaceAll(REPLACEMENT);
    }

    /**
     * 인증키를 serviceKey가 아닌 이름의 질의(apiKey 등)나 주소 경로·요청 헤더로 보내는 API에 쓴다. 이름으로는 키를 찾을 수 없어
     * serviceKey 값을 가린 뒤 키 값 자체를 가린다. 키가 URL에 들어가는 두 꼴(질의용 URLEncoder 인코딩과 공백을 %20으로 쓴 경로용 인코딩)도 가린다.
     *
     * @param key 인증키. null이거나 비었으면 serviceKey 값만 가린다
     */
    public static String mask(String text, String key) {
        String masked = mask(text);
        if (masked == null || key == null || key.isEmpty()) {
            return masked;
        }
        String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
        return masked.replace(key, MASK)
                .replace(encoded, MASK)
                .replace(encoded.replace("+", "%20"), MASK);
    }
}
