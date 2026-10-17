package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 인증키를 요청의 어디에 넣는지. IR에 없으면 generator가 요청 파라미터의 이름으로 정한다(serviceKey, apiKey 같은 인증키 이름의
 * 질의 파라미터, {your_appkey} 같은 경로 변수 순서. 모두 없으면 키를 보내지 않는다). 이 필드가 생기기 전의 IR과 rule 엔진의 IR이 그렇다.
 *
 * @param kind {@link #QUERY}, {@link #PATH}, {@link #HEADER} 가운데 하나
 * @param name 질의와 경로 변수는 requestParameters에 있는 파라미터 이름, 헤더는 헤더 이름. 문서 표기 그대로다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiAuth(String kind, String name) {

    /** 질의 파라미터(apiKey=…). 이름이 serviceKey가 아니어도 된다 */
    public static final String QUERY = "SERVICE_KEY_QUERY";
    /** 주소 경로의 변수({your_appkey}) */
    public static final String PATH = "PATH_KEY";
    /** 요청 헤더 */
    public static final String HEADER = "HEADER_KEY";
}
