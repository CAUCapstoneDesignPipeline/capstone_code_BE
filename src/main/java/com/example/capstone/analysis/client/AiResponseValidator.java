package com.example.capstone.analysis.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class AiResponseValidator {
    private final AiSchemaValidator schemas;
    public AiResponseValidator(AiSchemaValidator schemas) { this.schemas = schemas; }
    public void validate(AiOperation operation, JsonNode request, JsonNode response) {
        schemas.validate(operation.responseSchema(), response);
        require(sameJson(response.path("context"),request.path("context")));
        require(response.path("meta").path("profileVersion").equals(request.path("profileVersion")));
        Map<String, JsonNode> documents = new HashMap<>();
        if (request.has("note")) add(documents, request.get("note"));
        for (var doc : request.path("documents")) add(documents, doc);
        // note-links includes the source note and candidate note snapshots.
        for (var doc : request.path("candidates")) if (doc.has("body")) add(documents, doc);
        for (var doc : request.path("notes")) if (doc.has("body")) add(documents, doc);
        walk(request, documents, request.path("note"), 0);
        walk(response, documents, request.path("note"), 0);
        AiReferenceValidator.validate(operation, request, response);
        if (operation == AiOperation.EMBEDDINGS) {
            require(response.path("dimensions").equals(request.path("expectedDimensions")));
            require(response.path("items").size() == request.path("items").size());
            var ids = new java.util.HashSet<String>();
            for (var item : request.path("items")) ids.add(item.path("itemId").asString());
            for (var item : response.path("items")) {
                require(ids.remove(item.path("itemId").asString()));
                require(item.path("vector").size() == response.path("dimensions").asInt());
            }
        }
    }
    public void validateRequest(JsonNode request) {
        Map<String,JsonNode> documents=new HashMap<>();
        if(request.has("note")) add(documents,request.path("note"));
        for(String field:new String[]{"documents","candidates","notes"}) for(var doc:request.path(field)) if(doc.has("body")) add(documents,doc);
        walk(request,documents,request.path("note"),0);
        AiReferenceValidator.request(request);
    }
    private void add(Map<String, JsonNode> documents, JsonNode doc) {
        String body = doc.path("body").asString();
        require(Normalizer.isNormalized(body, Normalizer.Form.NFC));
        require(hash(body).equals(doc.path("bodySha256").asString()));
        JsonNode previous = documents.put(doc.path("noteId").asString(), doc);
        require(previous == null || previous.equals(doc));
    }
    private void walk(JsonNode node, Map<String, JsonNode> documents, JsonNode fallback, int depth) {
        require(depth < 80);
        if (node.isObject()) {
            JsonNode doc = fallback;
            if (node.has("noteId") && !node.has("analysisJobId")) {
                doc = documents.get(node.path("noteId").asString());
                require(doc != null);
                for (String key : new String[]{"noteVersion", "inputRevision", "bodySha256"}) {
                    if (node.has(key)) require(sameJson(node.get(key),doc.get(key)));
                }
            }
            if (node.has("startOffset") || node.has("endOffset")) {
                require(doc != null && !doc.isMissingNode());
                span(doc.path("body").asString(), node.path("startOffset").asInt(-1),
                        node.path("endOffset").asInt(-1), node.path("quote").asString(""));
            }
            for (var entry : node.properties()) walk(entry.getValue(), documents, doc, depth + 1);
        } else if (node.isArray()) for (var item : node) walk(item, documents, fallback, depth + 1);
    }
    public static void span(String body, int start, int end, String quote) {
        require(start >= 0 && end > start && end <= body.length());
        require(!(start > 0 && start < body.length() && Character.isLowSurrogate(body.charAt(start))
                && Character.isHighSurrogate(body.charAt(start - 1))));
        require(!(end < body.length() && Character.isLowSurrogate(body.charAt(end))
                && Character.isHighSurrogate(body.charAt(end - 1))));
        require(body.substring(start, end).equals(quote));
    }
    public static boolean sameJson(JsonNode left,JsonNode right) {
        if(left==null || right==null) return left==right;
        if(left.isIntegralNumber() && right.isIntegralNumber()) return left.bigIntegerValue().equals(right.bigIntegerValue());
        if(left.isObject() && right.isObject()) {
            if(left.size()!=right.size()) return false;
            for(var field:left.properties()) if(!right.has(field.getKey()) || !sameJson(field.getValue(),right.get(field.getKey()))) return false;
            return true;
        }
        if(left.isArray() && right.isArray()) { if(left.size()!=right.size())return false;for(int i=0;i<left.size();i++)if(!sameJson(left.get(i),right.get(i)))return false;return true; }
        return left.equals(right);
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static void require(boolean condition) {
        if (!condition) throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE);
    }
}
