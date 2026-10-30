package com.example.capstone.conversion.service;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.capstone.conversion.repository.ConversionRepository;

@Component
public class ConversionCleanup {
    private final ConversionRepository db;private final ConversionService service;private final SourceStorage storage;private final TransactionTemplate tx;
    public ConversionCleanup(ConversionRepository db,ConversionService service,SourceStorage storage,PlatformTransactionManager manager) { this.db=db;this.service=service;this.storage=storage;tx=new TransactionTemplate(manager); }
    @Scheduled(fixedDelayString="${capstone.conversion.cleanup-ms:60000}",initialDelayString="${capstone.conversion.cleanup-ms:60000}")
    public void sweep() {
        if(!storage.configured())return;
        for(var f:tx.execute(status->db.dueFiles()))service.deleteSource(UUID.fromString(f.path("user_id").asString()),UUID.fromString(f.path("id").asString()));
        deletePending();
        storage.garbageCollect(key->tx.execute(status->db.referenced(key)));
    }
    public void deletePending() {
        if(!storage.configured())return;
        for(var f:tx.execute(status->db.pendingDeletes()))if(storage.delete(UUID.fromString(f.path("object_key").asString())))tx.executeWithoutResult(status->db.deleted(UUID.fromString(f.path("id").asString())));
    }
}
