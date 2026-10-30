package com.example.capstone.analysis.client;

public enum AiOperation {
    EXTRACT("extract", "ExtractRequest", "ExtractResponse"),
    EMBEDDINGS("embeddings", "EmbeddingsRequest", "EmbeddingsResponse"),
    MERGE("merge-decisions", "MergeRequest", "MergeResponse"),
    BRIDGES("verifications/bridges", "VerifyBridgesRequest", "VerifyBridgesResponse"),
    EDGES("verifications/edges", "VerifyEdgesRequest", "VerifyEdgesResponse"),
    EXPLAIN("explanations", "ExplainRequest", "ExplainResponse"),
    VERIFY_EXPLANATION("verifications/explanations", "VerifyExplanationRequest", "VerifyExplanationResponse"),
    LINKS("note-links", "LinksRequest", "LinksResponse"),
    FACTS("facts", "FactsRequest", "FactsResponse"),
    DERIVE("derivations", "DeriveRequest", "DeriveResponse");

    private final String path;
    private final String requestSchema;
    private final String responseSchema;
    AiOperation(String path, String requestSchema, String responseSchema) {
        this.path = path; this.requestSchema = requestSchema; this.responseSchema = responseSchema;
    }
    public String path() { return path; }
    public String requestSchema() { return requestSchema; }
    public String responseSchema() { return responseSchema; }
}
