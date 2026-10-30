package com.example.capstone.conversion.worker;

import java.io.InputStream;
import tools.jackson.databind.JsonNode;

/** 신뢰된 서버 adapter 확장점. 원본 경로는 외부 응답·로그에 노출하지 않는다. 기본 엔진은 없다. */
public interface DocumentConverter {
    String engineRevision();
    default boolean configured() { return true; }
    byte[] convert(JsonNode request,InputStream privateSource) throws Exception;
    /** transport/일시 가용성 실패만 같은 논리 작업에서 제한 재시도한다. */
    class Retryable extends Exception { public Retryable() { super("Conversion temporarily unavailable"); } }
}
