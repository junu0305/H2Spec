package kr.go.h2spec.generator.emit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.model.DtoNode;

import java.util.Map;

/**
 * IR을 OpenAPI 3.0 문서(JSON)로 방출한다.
 * 문자열 템플릿 대신 Jackson 노드 트리로 조립해 이스케이프 문제를 원천 차단한다.
 */
public class OpenApiEmitter {

    private static final String OPENAPI_VERSION = "3.0.3";
    private static final String DOC_VERSION = "1.0.0";
    private static final String SERVICE_KEY_PARAM = "serviceKey";
    private static final String XML_FORMAT = "XML";
    private static final String INDENT = "  ";
    private static final Map<String, String> TYPE_MAP = Map.of(
            "string", "string",
            "integer", "integer",
            "number", "number",
            "boolean", "boolean");
    private static final String DEFAULT_TYPE = "string";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String emit(IrSpec ir, DtoNode root) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("openapi", OPENAPI_VERSION);
        doc.set("info", info(ir));
        doc.set("servers", servers(ir));
        doc.set("paths", paths(ir, root));
        doc.set("components", components());
        doc.set("security", security());
        return serialize(doc);
    }

    private ObjectNode info(IrSpec ir) {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("title", ir.api().apiName());
        info.put("description", ir.api().description());
        info.put("version", DOC_VERSION);
        return info;
    }

    private ArrayNode servers(IrSpec ir) {
        ArrayNode servers = objectMapper.createArrayNode();
        servers.addObject().put("url", ir.api().baseUrl());
        return servers;
    }

    private ObjectNode paths(IrSpec ir, DtoNode root) {
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("operationId", ir.api().endpoint().substring(1));
        operation.put("summary", ir.api().apiName());
        operation.put("description", ir.api().description());
        operation.set("parameters", parameters(ir));
        operation.set("responses", responses(ir, root));

        ObjectNode paths = objectMapper.createObjectNode();
        paths.putObject(ir.api().endpoint()).set("get", operation);
        return paths;
    }

    private ArrayNode parameters(IrSpec ir) {
        ArrayNode parameters = objectMapper.createArrayNode();
        for (RequestParameter param : ir.api().requestParameters()) {
            // serviceKey는 파라미터가 아니라 securityScheme(apiKey)으로 문서화한다
            if (SERVICE_KEY_PARAM.equalsIgnoreCase(param.name())) {
                continue;
            }
            ObjectNode node = parameters.addObject();
            node.put("name", param.name());
            node.put("in", param.in());
            node.put("required", param.required());
            if (param.description() != null) {
                node.put("description", param.description());
            }
            node.set("schema", parameterSchema(param));
            if (param.example() != null) {
                node.put("example", param.example());
            }
        }
        return parameters;
    }

    private ObjectNode parameterSchema(RequestParameter param) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", TYPE_MAP.getOrDefault(param.type(), DEFAULT_TYPE));
        if (param.pattern() != null) {
            schema.put("pattern", param.pattern());
        }
        if (param.defaultValue() != null) {
            putTypedDefault(schema, param);
        }
        return schema;
    }

    private void putTypedDefault(ObjectNode schema, RequestParameter param) {
        if ("integer".equals(param.type())) {
            try {
                schema.put("default", Long.parseLong(param.defaultValue()));
                return;
            } catch (NumberFormatException e) {
                // 숫자가 아니면 문자열로 남긴다
            }
        }
        schema.put("default", param.defaultValue());
    }

    private ObjectNode responses(IrSpec ir, DtoNode root) {
        String mediaType = XML_FORMAT.equals(ir.api().responseFormat())
                ? "application/xml"
                : "application/json";

        ObjectNode ok = objectMapper.createObjectNode();
        ok.put("description", "정상 응답 (성공 resultCode: " + ir.api().errorSpec().successResultCode() + ")");
        ok.putObject("content").putObject(mediaType).set("schema", schemaFor(root));

        ObjectNode responses = objectMapper.createObjectNode();
        responses.set("200", ok);
        return responses;
    }

    private ObjectNode schemaFor(DtoNode node) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (node.isLeaf()) {
            schema.put("type", TYPE_MAP.getOrDefault(node.leafType(), DEFAULT_TYPE));
            if (node.description() != null) {
                schema.put("description", node.description());
            }
            return schema;
        }
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (DtoNode child : node.children().values()) {
            if (child.isList()) {
                ObjectNode array = properties.putObject(child.name());
                array.put("type", "array");
                array.set("items", schemaFor(child));
            } else {
                properties.set(child.name(), schemaFor(child));
            }
        }
        return schema;
    }

    private ObjectNode components() {
        ObjectNode scheme = objectMapper.createObjectNode();
        scheme.put("type", "apiKey");
        scheme.put("name", SERVICE_KEY_PARAM);
        scheme.put("in", "query");

        ObjectNode components = objectMapper.createObjectNode();
        components.putObject("securitySchemes").set(SERVICE_KEY_PARAM, scheme);
        return components;
    }

    private ArrayNode security() {
        ArrayNode security = objectMapper.createArrayNode();
        security.addObject().putArray(SERVICE_KEY_PARAM);
        return security;
    }

    private String serialize(ObjectNode doc) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter(INDENT, "\n"))
                .withArrayIndenter(new DefaultIndenter(INDENT, "\n"));
        try {
            return objectMapper.writer(printer).writeValueAsString(doc) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenAPI 문서 직렬화 실패", e);
        }
    }
}
