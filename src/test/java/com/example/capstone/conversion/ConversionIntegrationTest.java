package com.example.capstone.conversion;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import com.example.capstone.support.ApiIntegrationSupport;
import com.example.capstone.conversion.worker.*;
import com.example.capstone.conversion.service.*;
import com.example.capstone.analysis.client.AiResponseValidator;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"capstone.conversion.enabled=true","capstone.conversion.worker-enabled=false","capstone.conversion.budget-microunits=1000","capstone.conversion.currency=KRW","capstone.conversion.call-seconds=1","capstone.conversion.cleanup-ms=3600000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class ConversionIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    static final Path root=directory();static Path directory(){try{return Files.createTempDirectory("conversion-private-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry p) { p.add("capstone.conversion.storage-root",root::toString);p.add("capstone.conversion.validator-jar",()->System.getProperty("capstone.test.validator-jar")); }
    @AfterAll static void deleteFiles() throws Exception { try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
    @TestConfiguration static class Fixture { @Bean Stub converter(ObjectMapper mapper){return new Stub(mapper);} }
    static class Stub implements DocumentConverter {
        final ObjectMapper mapper;volatile boolean enabled=true;volatile String mode="normal";final List<JsonNode> requests=new CopyOnWriteArrayList<>();volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        Stub(ObjectMapper mapper){this.mapper=mapper;}public String engineRevision(){return "synthetic-ocr-1";}public boolean configured(){return enabled;}
        public byte[] convert(JsonNode request,java.io.InputStream source) throws Exception {
            requests.add(request.deepCopy());assertTrue(source.read()!=-1);if(mode.equals("retry") && requests.size()==1)throw new Retryable();
            if(mode.equals("blocked")) {entered.countDown();release.await(10,TimeUnit.SECONDS);}if(mode.equals("timeout"))Thread.sleep(10000);
            String markdown="# 합성 🧠\n근거";var out=mapper.createObjectNode();for(String k:List.of("contractRevision","jobId","attemptId","ownerId","conversionRevision"))out.set(k,request.path(k));
            out.put("sourceSha256",request.path("source").path("contentSha256").asString()).put("resultRevision",1).put("engineRevision",engineRevision()).put("markdown",markdown).put("markdownSha256",AiResponseValidator.hash(markdown)).putNull("costMicrounits");
            var pages=out.putArray("pages");for(int i=1;i<=request.path("source").path("pageCount").asInt();i++){var page=pages.addObject().put("page",i).put("status",i==2?"unreadable":"converted");if(i==2)page.put("errorCode","UNREADABLE");else page.putNull("errorCode");}
            var mapping=out.putArray("mappings").addObject().put("page",1).put("startOffset",0).put("endOffset",markdown.length()).put("quote",markdown).put("status","verified");mapping.putArray("region").add(0).add(0).add(1).add(1);out.putArray("unknownRegions");
            switch(mode) {case "owner"->out.put("ownerId",UUID.randomUUID().toString());case "hash"->out.put("sourceSha256","f".repeat(64));case "span"->mapping.put("endOffset",markdown.length()-1);case "revision"->out.put("conversionRevision",2);case "page"->mapping.put("page",20);case "cost"->out.put("costMicrounits",1001);default->{} }
            return mapper.writeValueAsBytes(out);
        }
    }
    @Autowired Stub converter;@Autowired ConversionWorker worker;@Autowired ConversionService service;@Autowired ConversionCleanup cleanup;@Autowired PrivateSourceStorage storage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    ArrayNode captures;
    @BeforeEach void reset(){converter.enabled=true;converter.mode="normal";converter.requests.clear();converter.release.countDown();captures=mapper.createArrayNode();}
    @AfterEach void saveCaptures(TestInfo info)throws Exception {var envelope=mapper.createObjectNode().put("schemaVersion",1).put("syntheticOnly",true);envelope.putObject("producer").put("commit","working-tree").put("suite",getClass().getSimpleName());envelope.set("cases",captures);Path p=Path.of(".local/verification/results/ocr-"+info.getTestMethod().orElseThrow().getName()+".json");Files.createDirectories(p.getParent());Files.writeString(p,envelope.toPrettyString());}
    ResultActions capture(String id,String method,String template,ResultActions response,int code)throws Exception {assertEquals(code,response.andReturn().getResponse().getStatus());var c=captures.addObject().put("caseId",id+"-"+captures.size()).put("origin","mockmvc").put("method",method).put("pathTemplate",template).put("status",code);if(code!=204)c.set("body",json(response));return response;}
    ResultActions upload(byte[] bytes,String type,String token)throws Exception {return mvc.perform(MockMvcRequestBuilders.multipart("/api/source-files").file(new MockMultipartFile("file","ignored/../../source",type,bytes)).header("Authorization","Bearer "+token));}
    UUID file()throws Exception {return UUID.fromString(json(capture("upload","POST","/source-files",upload(UploadValidationTest.png(),"image/png",bearer),201)).path("fileId").asString());}
    UUID start(UUID file,String key)throws Exception {var payload=mapper.createObjectNode().put("fileId",file.toString()).put("idempotencyKey",key);return UUID.fromString(json(capture("start-"+key,"POST","/conversions",call("POST","/api/conversions",payload.toString()),202)).path("id").asString());}
    JsonNode result(UUID job)throws Exception{return json(capture("result","GET","/conversions/{conversionId}/result",call("GET","/api/conversions/"+job+"/result",null),200));}
    ObjectNode confirmPayload(JsonNode result,String key,String title,String body){return mapper.createObjectNode().put("expectedResultRevision",result.path("resultRevision").asInt()).put("idempotencyKey",key).put("title",title).putNull("topicId").put("markdown",body).put("expectedMarkdownSha256",AiResponseValidator.hash(body));}
    @Test void privateUploadConversionsEditedConfirmationReplayAndSourceDeletion()throws Exception {
        UUID file=file();var download=call("GET","/api/source-files/"+file,null).andExpect(status().isOk()).andReturn().getResponse();assertArrayEquals(UploadValidationTest.png(),download.getContentAsByteArray());assertEquals("no-store",download.getHeader("Cache-Control"));assertEquals("application/octet-stream",download.getContentType());assertTrue(download.getHeader("Content-Disposition").startsWith("attachment"));
        capture("foreign-file","GET","/source-files/{fileId}",call("GET","/api/source-files/"+file,null,otherBearer),404);
        UUID job=start(file,"start"),same=start(file,"alias");assertEquals(job,same);assertTrue(worker.runOne());assertEquals(job,start(file,"alias"));
        var converted=result(job);var request=confirmPayload(converted,"confirm","수정 노트","수정된 🧠 원문");
        var confirmed=json(capture("confirm-edited","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),201));assertEquals("unverified",confirmed.path("mappingStatus").asString());UUID note=UUID.fromString(confirmed.path("noteId").asString());
        assertEquals(confirmed,json(capture("confirm-replay","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),201)));
        capture("confirmed-delete","DELETE","/conversions/{conversionId}",call("DELETE","/api/conversions/"+job,null),409);
        request.put("idempotencyKey","different");capture("second-key","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),409);
        capture("delete-source","DELETE","/source-files/{fileId}",call("DELETE","/api/source-files/"+file,null),204);
        assertEquals("invalidated",jdbc.queryForObject("select mapping_status from conversion_confirmation where job_id=?",String.class,job));call("GET","/api/notes/"+note,null).andExpect(status().isOk());
        capture("deleted-file","GET","/source-files/{fileId}",call("GET","/api/source-files/"+file,null),404);assertEquals(0,jdbc.queryForObject("select count(*) from analysis_job where user_id=?",Integer.class,userId));
    }
    @Test void invalidMultipartUnavailableAndMalformedRequestsCreateNoJobs()throws Exception {
        capture("unsupported","POST","/source-files",upload("not image".getBytes(),"image/png",bearer),415);
        capture("oversize","POST","/source-files",upload(new byte[10_485_761],"image/png",bearer),413);
        var duplicate=mvc.perform(MockMvcRequestBuilders.multipart("/api/source-files").file(new MockMultipartFile("file","a","image/png",UploadValidationTest.png())).file(new MockMultipartFile("file","b","image/png",UploadValidationTest.png())).header("Authorization","Bearer "+bearer));capture("duplicate","POST","/source-files",duplicate,400);
        var extra=mvc.perform(MockMvcRequestBuilders.multipart("/api/source-files").file(new MockMultipartFile("file","a","image/png",UploadValidationTest.png())).param("url","https://untrusted.invalid/source").header("Authorization","Bearer "+bearer));capture("extra-url","POST","/source-files",extra,400);
        capture("unauth","POST","/source-files",mvc.perform(MockMvcRequestBuilders.multipart("/api/source-files").file(new MockMultipartFile("file","a","image/png",UploadValidationTest.png()))),401);
        UUID file=file();converter.enabled=false;capture("unconfigured","POST","/conversions",call("POST","/api/conversions","{\"fileId\":\""+file+"\",\"idempotencyKey\":\"none\"}"),503);
        assertEquals(0,jdbc.queryForObject("select count(*) from conversion_job where user_id=?",Integer.class,userId));converter.enabled=true;
        capture("extra-key","POST","/conversions",call("POST","/api/conversions","{\"fileId\":\""+file+"\",\"idempotencyKey\":\"a\",\"url\":\"bad\"}"),400);
    }
    @Test void outputOwnershipHashesSpansPagesRevisionsAndBudgetFailClosed()throws Exception {
        for(String mode:List.of("owner","hash","span","revision","page","cost")) {
            converter.mode=mode;UUID job=start(file(),mode);assertTrue(worker.runOne());var state=json(capture(mode,"GET","/conversions/{conversionId}",call("GET","/api/conversions/"+job,null),200));assertEquals("failed",state.path("status").asString());assertEquals("INVALID_OUTPUT",state.path("errorCode").asString());assertTrue(state.path("resultRevision").isNull());
            capture(mode+"-no-result","GET","/conversions/{conversionId}/result",call("GET","/api/conversions/"+job+"/result",null),409);
        }
    }
    @Test void retryUsesSameJobNewAttemptsAndCancellationFencesLateOutput()throws Exception {
        converter.mode="retry";UUID retry=start(file(),"retry");assertTrue(worker.runOne());assertEquals(2,converter.requests.size());assertEquals(converter.requests.get(0).path("jobId"),converter.requests.get(1).path("jobId"));assertNotEquals(converter.requests.get(0).path("attemptId"),converter.requests.get(1).path("attemptId"));result(retry);
        converter.mode="blocked";converter.entered=new CountDownLatch(1);converter.release=new CountDownLatch(1);UUID late=start(file(),"late");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {var running=executor.submit(worker::runOne);assertTrue(converter.entered.await(5,TimeUnit.SECONDS));capture("cancel-running","DELETE","/conversions/{conversionId}",call("DELETE","/api/conversions/"+late,null),204);converter.release.countDown();assertTrue(running.get(5,TimeUnit.SECONDS));}
        var cancelled=json(capture("cancelled","GET","/conversions/{conversionId}",call("GET","/api/conversions/"+late,null),200));assertEquals("cancelled",cancelled.path("status").asString());assertTrue(cancelled.path("resultRevision").isNull());
    }
    @Test void confirmationTitleConflictRollbackConcurrentReplayAndDeletedNoteNoRecreation()throws Exception {
        UUID job=start(file(),"confirm" );assertTrue(worker.runOne());var result=result(job);var request=confirmPayload(result,"confirm","기존",result.path("markdown").asString());UUID existing=note("기존",null,"원래");
        capture("title-conflict","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),409);assertEquals(0,jdbc.queryForObject("select count(*) from conversion_confirmation where job_id=?",Integer.class,job));
        request.put("title","확정");JsonNode a,b;try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var first=executor.submit(()->service.confirm(userId,job,request));var second=executor.submit(()->service.confirm(userId,job,request));a=first.get(5,TimeUnit.SECONDS);b=second.get(5,TimeUnit.SECONDS);}assertEquals(a,b);assertEquals("verified",a.path("mappingStatus").asString());
        UUID note=UUID.fromString(a.path("noteId").asString());
        call("PUT","/api/notes/"+note,"{\"title\":\"확정\",\"body\":\"수정 본문\",\"version\":0}").andExpect(status().isOk());
        var replay=json(capture("edited-note-replay","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),201));assertEquals("unverified",replay.path("mappingStatus").asString());assertEquals(0,replay.path("noteVersion").asInt());assertEquals("verified",jdbc.queryForObject("select mapping_status from conversion_confirmation where job_id=?",String.class,job));
        call("DELETE","/api/notes/"+note,null).andExpect(status().isNoContent());
        capture("deleted-note-no-recreate","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),404);assertEquals(1,jdbc.queryForObject("select count(*) from note where user_id=?",Integer.class,userId));
    }
    @Test void retentionExpiryAndDeletionRetriesPreserveTombstone()throws Exception {
        UUID file=file();UUID key=jdbc.queryForObject("select object_key from source_file where id=?",UUID.class,file);Path original=storage.path(key);Files.delete(original);Files.createDirectory(original);Files.writeString(original.resolve("block"),"synthetic");
        capture("tombstone","DELETE","/source-files/{fileId}",call("DELETE","/api/source-files/"+file,null),204);assertNull(jdbc.queryForObject("select physical_deleted_at from source_file where id=?",Object.class,file));
        Files.delete(original.resolve("block"));Files.delete(original);cleanup.deletePending();assertNotNull(jdbc.queryForObject("select physical_deleted_at from source_file where id=?",Object.class,file));
        UUID expired=file();jdbc.update("update source_file set expires_at=clock_timestamp()-interval '1 second' where id=?",expired);capture("expired","GET","/source-files/{fileId}",call("GET","/api/source-files/"+expired,null),404);cleanup.sweep();assertNotNull(jdbc.queryForObject("select deleted_at from source_file where id=?",Object.class,expired));
    }
    @Test void sourceDeletionDuringCallAndExpiredLeaseNeverRecreateDeletedDrafts() throws Exception {
        UUID source=file(),job=start(source,"source-late");converter.mode="blocked";converter.entered=new CountDownLatch(1);converter.release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var future=executor.submit(worker::runOne);assertTrue(converter.entered.await(5,TimeUnit.SECONDS));
            capture("delete-during-call","DELETE","/source-files/{fileId}",call("DELETE","/api/source-files/"+source,null),204);converter.release.countDown();assertTrue(future.get(5,TimeUnit.SECONDS));
        } finally {converter.release.countDown();}
        assertEquals("cancelled",json(call("GET","/api/conversions/"+job,null)).path("status").asString());assertNull(jdbc.queryForObject("select result from conversion_job where id=?",Object.class,job));
        converter.mode="normal";UUID resume=start(file(),"resume");UUID lease=UUID.randomUUID();var claimed=service.claim(lease);assertEquals(resume.toString(),claimed.path("id").asString());service.attempt(userId,resume,lease,UUID.randomUUID());
        jdbc.update("update conversion_job set lease_until=clock_timestamp()-interval '1 second' where id=?",resume);assertTrue(worker.runOne());assertEquals(2,jdbc.queryForObject("select attempt from conversion_job where id=?",Integer.class,resume));result(resume);
        service.finish(userId,resume,lease,null,null,"LATE_FAILURE");assertEquals("succeeded",json(call("GET","/api/conversions/"+resume,null)).path("status").asString());
    }
    @Test void timeoutBudgetPartialPagesAndConfirmationValidation() throws Exception {
        converter.mode="timeout";UUID timeout=start(file(),"timeout");long started=System.nanoTime();assertTrue(worker.runOne());assertTrue(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(5));
        var state=json(capture("timeout-failed","GET","/conversions/{conversionId}",call("GET","/api/conversions/"+timeout,null),200));assertEquals("failed",state.path("status").asString());assertTrue(jdbc.queryForObject("select attempt from conversion_job where id=?",Integer.class,timeout)<=2);
        converter.mode="normal";byte[] pdf=UploadValidationTest.pdf(d->{d.getPage(0).setMediaBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10,10));d.addPage(new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(10,10)));});
        UUID file=UUID.fromString(json(capture("pdf-upload","POST","/source-files",upload(pdf,"application/pdf",bearer),201)).path("fileId").asString());UUID job=start(file,"partial");assertTrue(worker.runOne());var output=result(job);assertEquals("unreadable",output.path("pages").get(1).path("status").asString());
        var request=confirmPayload(output,"partial-confirm","부분 문서",output.path("markdown").asString());request.put("expectedResultRevision",2);capture("stale-revision","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),409);
        request.put("expectedResultRevision",1).put("expectedMarkdownSha256","f".repeat(64));capture("wrong-submitted-hash","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),400);
        capture("foreign-job","GET","/conversions/{conversionId}",call("GET","/api/conversions/"+job,null,otherBearer),404);
        request.put("expectedMarkdownSha256",output.path("markdownSha256").asString());capture("partial-confirm","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),201);
        jdbc.update("update source_file set expires_at=clock_timestamp()-interval '1 day' where id=?",file);cleanup.sweep();call("GET","/api/source-files/"+file,null).andExpect(status().isOk());
    }
    @Test void uploadTransactionFailureAndUnreferencedStagingAreCollected() throws Exception {
        long before;try(var files=Files.list(root)){before=files.count();}
        assertThrows(com.example.capstone.global.exception.ApiException.class,()->service.upload(UUID.randomUUID(),new java.io.ByteArrayInputStream(UploadValidationTest.png()),"image/png"));
        try(var files=Files.list(root)){assertEquals(before,files.count());}
        Path orphan=root.resolve(UUID.randomUUID()+".part");Files.writeString(orphan,"synthetic abandoned source");Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(7200)));cleanup.sweep();assertFalse(Files.exists(orphan));
    }

    @Test void confirmationLimitsApplyAfterNfcNormalization() throws Exception {
        UUID job=start(file(),"nfc");assertTrue(worker.runOne());var output=result(job);
        String title="각".repeat(200),body="각".repeat(50000);
        var request=confirmPayload(output,"nfc-confirm",java.text.Normalizer.normalize(title,java.text.Normalizer.Form.NFD),java.text.Normalizer.normalize(body,java.text.Normalizer.Form.NFD));request.put("expectedMarkdownSha256",AiResponseValidator.hash(body));
        var confirmed=json(capture("nfd-boundary","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),201));
        assertEquals(body,json(call("GET","/api/notes/"+confirmed.path("noteId").asString(),null)).path("body").asString());
        request.put("markdown","각".repeat(50001)).put("expectedMarkdownSha256",AiResponseValidator.hash("각".repeat(50001)));capture("nfc-overlimit","POST","/conversions/{conversionId}/confirm",call("POST","/api/conversions/"+job+"/confirm",request.toString()),400);
    }

    @Test void cleanupRechecksRetentionAfterWaitingForConcurrentConfirmation() throws Exception {
        UUID source=file(),job=start(source,"retention-race");assertTrue(worker.runOne());var output=result(job);var request=confirmPayload(output,"retention-confirm","보존 경합",output.path("markdown").asString());
        jdbc.update("update source_file set expires_at=clock_timestamp()+interval '1 second' where id=?",source);
        CountDownLatch confirmed=new CountDownLatch(1),commit=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var confirmation=executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{var result=service.confirm(userId,job,request);confirmed.countDown();try{if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}return result;}));
            assertTrue(confirmed.await(5,TimeUnit.SECONDS));Thread.sleep(1100);var gc=executor.submit(cleanup::sweep);assertThrows(TimeoutException.class,()->gc.get(150,TimeUnit.MILLISECONDS));
            commit.countDown();assertNotNull(confirmation.get(5,TimeUnit.SECONDS));gc.get(5,TimeUnit.SECONDS);
            assertNull(jdbc.queryForObject("select deleted_at from source_file where id=?",Object.class,source));call("GET","/api/source-files/"+source,null).andExpect(status().isOk());
        } finally {commit.countDown();}
    }

}
