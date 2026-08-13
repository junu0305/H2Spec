package kr.go.h2spec.client.interceptor;

import java.util.regex.Pattern;

/**
 * 로그·예외 메시지에 공공데이터 인증키가 남지 않도록 serviceKey 값을 가린다.
 * URI뿐 아니라 에러 바디처럼 URL이 에코된 임의 텍스트에도 사용할 수 있다.
 */
public final class ServiceKeyMasker {

    private static final Pattern SERVICE_KEY_PATTERN =
            Pattern.compile("(serviceKey=)[^&\\s\"'<]*", Pattern.CASE_INSENSITIVE);
    private static final String REPLACEMENT = "$1****";

    private ServiceKeyMasker() {
    }

    public static String mask(String text) {
        return text == null ? null : SERVICE_KEY_PATTERN.matcher(text).replaceAll(REPLACEMENT);
    }
}
