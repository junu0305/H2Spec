package kr.go.h2spec.parser.llm;

import kr.go.h2spec.parser.llm.schema.LlmAuth;
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmOperation.DefaultResponseFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.HttpMethod;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseExampleFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseFormat;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseStructure;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Location;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter.Requirement;
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import kr.go.h2spec.parser.llm.schema.LlmResponseField.PathSource;
import kr.go.h2spec.parser.llm.schema.LlmResultCode;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.schema.LlmUrlCandidates;
import kr.go.h2spec.parser.llm.schema.LlmValueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 규칙마다의 테스트가 쓰는 LLM 출력을 코드로 만든다. 중간 스키마 record는 필드가 많아 테스트가 보려는 값만 바꿔 쓰게 한다.
 * 기본값은 LLM이 아무것도 찾지 못했을 때 내는 값(빈 문자열, 빈 배열, {@code UNKNOWN})이다. 응답 형식·응답 구조·예제 형식·인증만
 * 표준 기술문서의 값(XML, 예제로 정한 구조, 쿼리의 serviceKey)으로 둔다.
 */
public final class LlmFixtures {

    private LlmFixtures() {
    }

    public static OperationBuilder operation() {
        return new OperationBuilder();
    }

    public static LlmExtraction extraction(OperationBuilder... operations) {
        return extraction("", List.of(), operations);
    }

    public static LlmExtraction extraction(String provider, List<String> documentNotes, OperationBuilder... operations) {
        return extraction(provider, List.of(), documentNotes, operations);
    }

    /** 문서 공통 에러코드 표가 있는 문서 */
    public static LlmExtraction extraction(String provider, List<LlmErrorCode> errorCodes, List<String> documentNotes,
                                           OperationBuilder... operations) {
        return new LlmExtraction("", provider, errorCodes, Arrays.stream(operations).map(OperationBuilder::build).toList(),
                documentNotes);
    }

    /** 표에 이름만 적힌 선택 쿼리 파라미터 */
    public static LlmRequestParameter query(String name) {
        return parameter(name, Location.QUERY, LlmValueType.STRING, Requirement.OPTIONAL, "", "", false);
    }

    public static LlmRequestParameter parameter(String name, Location location, LlmValueType type,
                                                Requirement required, String example, String defaultValue,
                                                boolean common) {
        return new LlmRequestParameter(name, location, type, required, name + " 설명", example, defaultValue, common);
    }

    /** 문자열 값 필드 */
    public static LlmResponseField field(String path, PathSource source) {
        return field(path, LlmValueType.STRING, "", source, false);
    }

    public static LlmResponseField field(String path, LlmValueType type, String sample, PathSource source,
                                         boolean common) {
        String leaf = path.substring(path.lastIndexOf('.') + 1).replace("[]", "");
        return new LlmResponseField(path, type, leaf + " 설명", sample, source, common);
    }

    public static LlmSuccessCode success(String code, LlmSuccessCode.Source source) {
        return new LlmSuccessCode(code, source);
    }

    public static LlmErrorCode error(String code, String message) {
        return new LlmErrorCode(code, message);
    }

    /** 오퍼레이션 하나를 만든다. 바꾸지 않은 필드는 클래스 설명의 기본값이다 */
    public static final class OperationBuilder {
        private String operationName = "";
        private String title = "";
        private String description = "";
        private String callBackUrl = "";
        private String tableUrl = "";
        private String urlLine = "";
        private String requestExampleUrl = "";
        private String serviceUrl = "";
        private HttpMethod httpMethod = HttpMethod.UNKNOWN;
        private ResponseFormat responseFormat = ResponseFormat.XML;
        private DefaultResponseFormat defaultResponseFormat = DefaultResponseFormat.UNKNOWN;
        private String formatParameter = "";
        private LlmAuth auth = new LlmAuth(LlmAuth.Kind.SERVICE_KEY_QUERY, "serviceKey");
        private List<LlmRequestParameter> requestParameters = new ArrayList<>();
        private ResponseStructure responseStructure = ResponseStructure.EXAMPLE;
        private ResponseExampleFormat responseExampleFormat = ResponseExampleFormat.XML;
        private List<LlmResponseField> responseFields = new ArrayList<>();
        private String resultCodeField = "";
        private List<LlmSuccessCode> successCodes = new ArrayList<>();
        private List<LlmErrorCode> errorCodes = new ArrayList<>();
        private List<String> evidence = new ArrayList<>();
        private List<String> uncertain = new ArrayList<>();
        private List<String> unsupported = new ArrayList<>();

        private OperationBuilder() {
        }

        public OperationBuilder name(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public OperationBuilder title(String title) {
            this.title = title;
            return this;
        }

        public OperationBuilder description(String description) {
            this.description = description;
            return this;
        }

        public OperationBuilder callBackUrl(String callBackUrl) {
            this.callBackUrl = callBackUrl;
            return this;
        }

        public OperationBuilder tableUrl(String tableUrl) {
            this.tableUrl = tableUrl;
            return this;
        }

        public OperationBuilder urlLine(String urlLine) {
            this.urlLine = urlLine;
            return this;
        }

        public OperationBuilder requestExampleUrl(String requestExampleUrl) {
            this.requestExampleUrl = requestExampleUrl;
            return this;
        }

        public OperationBuilder serviceUrl(String serviceUrl) {
            this.serviceUrl = serviceUrl;
            return this;
        }

        public OperationBuilder format(ResponseFormat format, DefaultResponseFormat defaultFormat, String parameter) {
            this.responseFormat = format;
            this.defaultResponseFormat = defaultFormat;
            this.formatParameter = parameter;
            return this;
        }

        public OperationBuilder auth(LlmAuth.Kind kind, String parameterName) {
            this.auth = new LlmAuth(kind, parameterName);
            return this;
        }

        public OperationBuilder parameters(LlmRequestParameter... parameters) {
            this.requestParameters = new ArrayList<>(List.of(parameters));
            return this;
        }

        public OperationBuilder addParameter(LlmRequestParameter parameter) {
            this.requestParameters.add(parameter);
            return this;
        }

        /** 예제로 구조를 정하지 않았으면 경로를 정한 예제도 없다. 다른 형식을 보려면 뒤에 {@link #exampleFormat}을 부른다 */
        public OperationBuilder structure(ResponseStructure structure) {
            this.responseStructure = structure;
            if (structure != ResponseStructure.EXAMPLE) {
                this.responseExampleFormat = ResponseExampleFormat.NONE;
            }
            return this;
        }

        public OperationBuilder exampleFormat(ResponseExampleFormat format) {
            this.responseExampleFormat = format;
            return this;
        }

        public OperationBuilder fields(LlmResponseField... fields) {
            this.responseFields = new ArrayList<>(List.of(fields));
            return this;
        }

        public OperationBuilder resultCode(String fieldName, LlmSuccessCode... codes) {
            this.resultCodeField = fieldName;
            this.successCodes = new ArrayList<>(List.of(codes));
            return this;
        }

        public OperationBuilder errors(LlmErrorCode... errors) {
            this.errorCodes = new ArrayList<>(List.of(errors));
            return this;
        }

        public OperationBuilder evidence(String... ids) {
            this.evidence = new ArrayList<>(List.of(ids));
            return this;
        }

        public OperationBuilder uncertain(String... notes) {
            this.uncertain = new ArrayList<>(List.of(notes));
            return this;
        }

        public OperationBuilder unsupported(String... notes) {
            this.unsupported = new ArrayList<>(List.of(notes));
            return this;
        }

        public LlmOperation build() {
            return new LlmOperation(operationName, title, description,
                    new LlmUrlCandidates(callBackUrl, tableUrl, urlLine, requestExampleUrl, serviceUrl),
                    httpMethod, responseFormat, defaultResponseFormat, formatParameter, auth,
                    List.copyOf(requestParameters), responseStructure, responseExampleFormat, List.copyOf(responseFields),
                    new LlmResultCode(resultCodeField, List.copyOf(successCodes)), List.copyOf(errorCodes),
                    List.copyOf(evidence), List.copyOf(uncertain), List.copyOf(unsupported));
        }
    }
}
