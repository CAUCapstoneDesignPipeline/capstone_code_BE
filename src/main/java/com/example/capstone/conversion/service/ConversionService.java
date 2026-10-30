package com.example.capstone.conversion.service;

import java.io.InputStream;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.*;
import com.example.capstone.conversion.config.ConversionProperties;
import com.example.capstone.conversion.repository.ConversionRepository;
import com.example.capstone.conversion.worker.DocumentConverter;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.note.service.NoteService;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.global.validation.TextValidation;
import com.example.capstone.analysis.client.AiResponseValidator;
import static com.example.capstone.conversion.service.ConversionErrors.*;

@Service
public class ConversionService {
    private final ConversionRepository db;private final AppUserRepository users;private final NoteService notes;private final SourceStorage storage;private final ConversionWire wire;private final ObjectMapper mapper;private final ConversionProperties config;private final ObjectProvider<DocumentConverter> converters;private final TransactionTemplate tx;
    public ConversionService(ConversionRepository db,AppUserRepository users,NoteService notes,SourceStorage storage,ConversionWire wire,ObjectMapper mapper,ConversionProperties config,ObjectProvider<DocumentConverter> converters,PlatformTransactionManager transactions) { this.db=db;this.users=users;this.notes=notes;this.storage=storage;this.wire=wire;this.mapper=mapper;this.config=config;this.converters=converters;tx=new TransactionTemplate(transactions); }
    public boolean configured() { try { var engine=converters.getIfAvailable();return config.enabled() && config.bounded() && storage.configured() && config.budgetMicrounits()>0 && config.currency()!=null && config.currency().matches("[A-Z]{3}") && engine!=null && engine.configured() && engine.engineRevision()!=null && !engine.engineRevision().isBlank() && engine.engineRevision().length()<=200; }catch(RuntimeException invalid){return false;} }
    public JsonNode upload(UUID user,InputStream input,String media) {
        var file=storage.put(input,media);UUID id=UUID.randomUUID();
        try { return tx.execute(status->{lock(user);db.upload(id,user,file);return wire.receipt(db.file(user,id,false));}); }
        catch(RuntimeException failure) { storage.discard(file);throw failure; }
    }
    private void lock(UUID user) { users.lockById(user).orElseThrow(ConversionErrors::missing); }
    private UUID id(JsonNode node,String key) { return UUID.fromString(node.path(key).asString()); }
    private JsonNode source(UUID user,UUID file,boolean locked) {
        JsonNode f=db.file(user,file,locked);if(f==null || !f.path("deleted_at").isNull() || (!Instant.parse(f.path("expires_at").asString()).isAfter(Instant.now()) && !db.retained(user,file)))throw missing();return f;
    }
    private JsonNode ownedJob(UUID user,UUID job,boolean locked) { JsonNode j=db.job(user,job,locked);if(j==null)throw missing();return j; }
    public record Download(byte[] bytes,String media,String hash) { }
    @Transactional(readOnly=true) public Download download(UUID user,UUID file) { var f=source(user,file,false);return new Download(storage.read(id(f,"object_key"),f.path("content_sha256").asString(),f.path("byte_size").asLong()),f.path("media_type").asString(),f.path("content_sha256").asString()); }
    @Transactional public void deleteSource(UUID user,UUID file) { lock(user);var row=db.file(user,file,true);if(row==null)throw missing();db.tombstone(user,file); }
    @Transactional public void expireSourceIfEligible(UUID user,UUID file) {
        lock(user);var row=db.file(user,file,true);
        if(row!=null && row.path("deleted_at").isNull() && !db.retained(user,file)
                && (!Instant.parse(row.path("expires_at").asString()).isAfter(Instant.now()) || db.deletedConfirmedNote(user,file)))db.tombstone(user,file);
    }
    @Transactional public JsonNode start(UUID user,JsonNode request) {
        wire.fields(request,"fileId","idempotencyKey");UUID file=wire.uuid(request.path("fileId"));String key=wire.text(request.path("idempotencyKey"),1,80);
        lock(user);source(user,file,true);if(!configured())throw unavailable();db.expireJobs(user);
        var replay=db.replay(user,key);if(replay!=null) { if(!id(replay,"file_id").equals(file))throw conflict();return wire.job(ownedJob(user,id(replay,"job_id"),false)); }
        var active=db.active(user,file);UUID job;
        if(active!=null)job=id(active,"id");else { job=UUID.randomUUID();db.create(user,file,job,key,converters.getObject().engineRevision()); }
        db.submit(user,file,job,key);return wire.job(ownedJob(user,job,false));
    }
    @Transactional public JsonNode get(UUID user,UUID job) { lock(user);db.expireJobs(user);return wire.job(ownedJob(user,job,false)); }
    @Transactional(readOnly=true) public JsonNode result(UUID user,UUID job) { var j=ownedJob(user,job,false);source(user,id(j,"file_id"),false);if(!j.path("status").asString().equals("succeeded") || j.path("result").isNull())throw conflict();return j.path("result"); }
    @Transactional public void cancel(UUID user,UUID job) { lock(user);var existing=ownedJob(user,job,false);source(user,id(existing,"file_id"),true);ownedJob(user,job,true);if(db.confirmation(user,job)!=null)throw conflict();db.cancel(user,job); }
    @Transactional public JsonNode confirm(UUID user,UUID job,JsonNode request) {
        wire.fields(request,"expectedResultRevision","idempotencyKey","title","topicId","markdown","expectedMarkdownSha256");int revision=wire.integer(request.path("expectedResultRevision"),1,Integer.MAX_VALUE);String key=wire.text(request.path("idempotencyKey"),1,80);
        String title=TextValidation.title(wire.text(request.path("title"),0,Integer.MAX_VALUE),"title","제목",200);UUID topic=request.path("topicId").isNull()?null:wire.uuid(request.path("topicId"));
        String markdown=Normalizer.normalize(wire.text(request.path("markdown"),0,Integer.MAX_VALUE),Normalizer.Form.NFC);if(markdown.codePointCount(0,markdown.length())>50000)throw invalid();String hash=wire.hash(request.path("expectedMarkdownSha256"));if(!AiResponseValidator.hash(markdown).equals(hash))throw invalid();
        var normalized=mapper.createObjectNode().put("expectedResultRevision",revision).put("idempotencyKey",key).put("title",title).put("markdown",markdown).put("expectedMarkdownSha256",hash);if(topic==null)normalized.putNull("topicId");else normalized.put("topicId",topic.toString());String payloadHash=AiResponseValidator.hash(AiResponseValidator.canonicalJson(normalized));
        lock(user);var existing=ownedJob(user,job,false);UUID file=id(existing,"file_id");var f=source(user,file,true);var j=ownedJob(user,job,true);var prior=db.confirmation(user,job);
        if(prior!=null) { if(prior.path("note_id").isNull())throw missing();if(!prior.path("idempotency_key").asString().equals(key) || !prior.path("request_sha256").asString().equals(payloadHash))throw conflict();return confirmationView(user,prior); }
        if(db.confirmationKeyTaken(user,key) || !j.path("status").asString().equals("succeeded") || j.path("result_revision").asInt()!=revision)throw conflict();
        var create=mapper.createObjectNode().put("title",title).put("body",markdown);if(topic==null)create.putNull("topicId");else create.put("topicId",topic.toString());var note=notes.create(user,mapper.treeToValue(create,NoteCreateRequest.class));
        String mapping=hash.equals(j.path("result").path("markdownSha256").asString())?"verified":"unverified";var mappings=j.path("result").path("mappings").deepCopy();
        if(!mapping.equals("verified"))for(var item:mappings)((tools.jackson.databind.node.ObjectNode)item).put("status","unverified");
        else if(mappings.size()==0 || java.util.stream.StreamSupport.stream(mappings.spliterator(),false).anyMatch(n->!n.path("status").asString().equals("verified")))mapping="unverified";
        db.confirm(user,job,file,key,payloadHash,note.id(),note.version(),revision,mapping,f.path("content_sha256").asString(),hash,mappings);return confirmationView(user,db.confirmation(user,job));
    }
    private JsonNode confirmationView(UUID user,JsonNode snapshot) {
        var note=notes.get(user,id(snapshot,"note_id"));var response=(tools.jackson.databind.node.ObjectNode)wire.confirmed(snapshot);
        // 이력의 본문/영역은 보존하되 현재 노트에 대한 verified 의미로 재사용하지 않는다.
        if(note.version()!=snapshot.path("note_version").asInt() || !AiResponseValidator.hash(note.body()).equals(snapshot.path("markdown_sha256").asString()))response.put("mappingStatus","unverified");
        return response;
    }
    @Transactional public JsonNode claim(UUID lease) { if(!configured())return null;return db.claim(lease); }
    @Transactional public JsonNode attempt(UUID user,UUID job,UUID lease,UUID attempt) {
        lock(user);var initial=ownedJob(user,job,false);var f=source(user,id(initial,"file_id"),true);var j=ownedJob(user,job,true);if(!configured() || !db.valid(j,lease) || !j.path("engine_revision").asString().equals(converters.getObject().engineRevision()) || !db.reserve(job,lease,attempt))throw new Lost();
        var request=mapper.createObjectNode().put("contractRevision",ConversionWire.REVISION).put("jobId",job.toString()).put("attemptId",attempt.toString()).put("ownerId",user.toString()).put("conversionRevision",j.path("conversion_revision").asInt()).put("idempotencyKey",j.path("idempotency_key").asString());
        request.putObject("source").put("fileId",f.path("id").asString()).put("contentSha256",f.path("content_sha256").asString()).put("mediaType",f.path("media_type").asString()).put("byteSize",f.path("byte_size").asLong()).put("pageCount",f.path("page_count").asInt()).put("objectRef",f.path("object_key").asString());
        request.putObject("limits").put("maxBytes",PrivateSourceStorage.MAX_BYTES).put("maxPages",20).put("maxPixelsPerPage",25_000_000).put("timeoutSeconds",config.callSeconds()).put("maxCostMicrounits",config.budgetMicrounits()).put("currency",config.currency());return request;
    }
    @Transactional public boolean heartbeat(UUID job,UUID lease) { return configured() && db.heartbeat(job,lease); }
    @Transactional public void finish(UUID user,UUID job,UUID lease,JsonNode request,JsonNode response,String error) {
        lock(user);var initial=ownedJob(user,job,false);JsonNode f=db.file(user,id(initial,"file_id"),true);var j=ownedJob(user,job,true);
        if(!db.valid(j,lease))return;
        if(!configured() || !j.path("engine_revision").asString().equals(converters.getObject().engineRevision()) || f==null || !f.path("deleted_at").isNull() || (!Instant.parse(f.path("expires_at").asString()).isAfter(Instant.now()) && !db.retained(user,id(f,"id")))) { db.cancel(user,job);return; }
        if(response!=null && (request==null || !j.path("attempt_id").asString().equals(request.path("attemptId").asString()) || j.path("conversion_revision").asInt()!=request.path("conversionRevision").asInt()))throw new Lost();
        db.finish(job,lease,response,error);
    }
    public static final class Lost extends RuntimeException { public Lost(){super("Conversion lease changed");} }
}
