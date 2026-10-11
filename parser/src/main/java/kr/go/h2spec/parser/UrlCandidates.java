package kr.go.h2spec.parser;

/**
 * 오퍼레이션 하나의 주소 후보. {@link IrConventions#endpointUrl}이 이 묶음에서 8단계 규칙으로 주소를 고른다.
 * <p>
 * 각 후보는 칸(문단)의 원래 글에서 {@link IrConventions}의 함수로 꺼낸 값이다. 두 엔진이 같은 칸에서 같은 함수로
 * 꺼내야 같은 주소가 나온다. 없는 후보는 null이고, 빈 문자열도 없는 것으로 본다
 * (LLM 중간 스키마는 값이 없을 때 빈 문자열을 쓴다).
 *
 * @param callBackUrl           정보 표의 주소 칸(Call Back URL, 요청주소 등). {@link IrConventions#callBackUrl}
 * @param schemelessCallBackUrl 같은 칸에 스킴 없이 적은 주소. {@link IrConventions#schemelessUrl}
 * @param tableUrl              요청 표 안의 END POINT URL 같은 주소 행. {@link IrConventions#firstUrl}
 * @param lineUrl               "요청 URL :"처럼 이름표가 붙은 문단, 또는 그 다음 줄. {@link IrConventions#firstUrl}
 * @param exampleUrl            요청 예제 구간의 첫 주소. {@link IrConventions#firstUrl}
 * @param serviceUrl            이 오퍼레이션이 속한 서비스 개요 표의 서비스 URL(운영 우선). {@link IrConventions#serviceUrl}
 * @param operationName         정보 표의 영문 오퍼레이션명. {@link IrConventions#operationName}
 * @param common                "데이터 공통 사항"처럼 뒤 오퍼레이션에 붙는 공통 구간인지. llm 엔진은 공통 구간을
 *                              오퍼레이션으로 내지 않으므로 늘 false다
 */
public record UrlCandidates(String callBackUrl, String schemelessCallBackUrl, String tableUrl, String lineUrl,
                            String exampleUrl, String serviceUrl, String operationName, boolean common) {

    /** 빈 문자열 후보는 없는 것(null)으로 바꾼다 */
    public UrlCandidates {
        callBackUrl = absentIfBlank(callBackUrl);
        schemelessCallBackUrl = absentIfBlank(schemelessCallBackUrl);
        tableUrl = absentIfBlank(tableUrl);
        lineUrl = absentIfBlank(lineUrl);
        exampleUrl = absentIfBlank(exampleUrl);
        serviceUrl = absentIfBlank(serviceUrl);
        operationName = absentIfBlank(operationName);
    }

    private static String absentIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
