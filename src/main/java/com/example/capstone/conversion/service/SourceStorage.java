package com.example.capstone.conversion.service;

import java.io.InputStream;
import java.util.UUID;
import java.util.function.Predicate;

/** private 저장 backend 경계. 외부 URL/원본 파일명은 객체 식별자로 받지 않는다. */
public interface SourceStorage {
    record Stored(UUID key,String type,String hash,long bytes,int pages) { }
    boolean configured();
    Stored put(InputStream input,String declaredMediaType);
    byte[] read(UUID key,String expectedHash,long expectedBytes);
    boolean delete(UUID key);
    default void discard(Stored file) { delete(file.key()); }
    default void garbageCollect(Predicate<UUID> referenced) { }
}
