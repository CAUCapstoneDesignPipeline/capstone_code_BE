package com.example.capstone.analysis.client;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed, bounded schemas exported from AI e270c9e (schema source unchanged from 8737e3e).
 * Implements only the keywords in that pinned schema set; no remote reference resolution. */
@Component
public final class AiSchemaValidator {
    private final JsonNode schemas;
    public AiSchemaValidator(ObjectMapper mapper) {
        try (var in = getClass().getResourceAsStream("/ai/schemas.json")) {
            schemas = mapper.readTree(in);
        } catch (Exception exception) { throw new IllegalStateException("Pinned AI schemas unavailable"); }
    }
    public void validate(String name, JsonNode value) {
        JsonNode schema = schemas.path(name);
        if (schema.isMissingNode() || !matches(schema, value, schema, 0)) {
            throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE);
        }
    }
    private boolean matches(JsonNode schema, JsonNode value, JsonNode root, int depth) {
        if (depth > 80 || value == null || value.isMissingNode()) return false;
        if (schema.has("$ref")) {
            String ref = schema.path("$ref").asString();
            return ref.startsWith("#/$defs/") && matches(root.at(ref.substring(1)), value, root, depth + 1);
        }
        if (schema.has("anyOf")) {
            boolean found = false;
            for (var branch : schema.path("anyOf")) found |= matches(branch, value, root, depth + 1);
            if (!found) return false;
        }
        if (schema.has("const") && !schema.get("const").equals(value)) return false;
        if (schema.has("enum")) {
            boolean found = false;
            for (var choice : schema.get("enum")) found |= choice.equals(value);
            if (!found) return false;
        }
        String type = schema.path("type").asString("");
        if (!type.isEmpty() && !switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isString(); case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber() && Double.isFinite(value.asDouble());
            case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
        }) return false;
        if (value.isObject()) {
            for (var required : schema.path("required")) if (!value.has(required.asString())) return false;
            for (var entry : value.properties()) {
                JsonNode property = schema.path("properties").path(entry.getKey());
                if (!property.isMissingNode()) {
                    if (!matches(property, entry.getValue(), root, depth + 1)) return false;
                } else if (schema.has("additionalProperties")) {
                    var additional = schema.get("additionalProperties");
                    if (additional.isBoolean() && !additional.asBoolean()) return false;
                    if (additional.isObject() && !matches(additional, entry.getValue(), root, depth + 1)) return false;
                }
            }
        }
        if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(10000)) return false;
            if (schema.has("items")) for (var item : value) if (!matches(schema.get("items"), item, root, depth + 1)) return false;
        }
        if (value.isString()) {
            String text = value.asString(); int length = text.codePointCount(0, text.length());
            if (length < schema.path("minLength").asInt(0) || length > schema.path("maxLength").asInt(4_194_304)) return false;
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asString()).matcher(text).find()) return false;
        }
        if (value.isNumber()) {
            double n = value.asDouble();
            if (!Double.isFinite(n) || (schema.has("minimum") && n < schema.get("minimum").asDouble())
                    || (schema.has("maximum") && n > schema.get("maximum").asDouble())) return false;
        }
        return true;
    }
}
