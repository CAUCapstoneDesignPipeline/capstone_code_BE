package com.example.capstone.analysis;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.support.ApiIntegrationSupport;
import com.example.capstone.analysis.client.AiResponseValidator;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("test")
@Testcontainers
class AnalysisPersistenceIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test void normalizedMultipleEvidenceAndOwnershipConstraintsPreserveAppendOnlyReviews() throws Exception {
        UUID a=note("A",null,"가 🧠"), b=note("B",null,"나"), job=UUID.randomUUID();
        jdbc.update("insert into analysis_job(id,note_id,note_version,status,extractor,user_id,lifecycle_state) values(?,?,0,'succeeded','fixture',?,'succeeded')",job,a,userId);
        UUID sa=snapshot(job,a,"가 🧠"), sb=snapshot(job,b,"나"), result=UUID.randomUUID();
        jdbc.update("""
          insert into analysis_result(id,user_id,job_id,external_ref,identity_sha256,kind,conclusion,conditions,trace,detail,
           derivation_status,soundness,novelty_note_ids) values(?,?,?,'r1',?,'derivation','결론','[]','[]','{}','conclusion','sound','[]')
          """,result,userId,job,AiResponseValidator.hash("identity"));
        for(UUID snapshot:new UUID[]{sa,sb}) jdbc.update("insert into analysis_result_evidence(id,user_id,result_id,snapshot_id,claim_ref,start_offset,end_offset,quote) values(?,?,?,?,'claim',0,1,'합성')",UUID.randomUUID(),userId,result,snapshot);
        assertEquals(2,jdbc.queryForObject("select count(*) from analysis_result_evidence where result_id=?",Integer.class,result));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("insert into analysis_result_evidence(id,user_id,result_id,snapshot_id,claim_ref,start_offset,end_offset,quote) values(?,?,?,?,'foreign',0,1,'합성')",UUID.randomUUID(),otherId,result,sa));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("insert into analysis_dependency(user_id,result_id,depends_on_id) values(?,?,?)",userId,result,result));
        UUID event=UUID.randomUUID();
        jdbc.update("insert into analysis_review_event(id,user_id,result_id,revision,action,idempotency_key,request_sha256) values(?,?,?,1,'accept','key',?)",event,userId,result,AiResponseValidator.hash("request"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update analysis_review_event set action='reject' where id=?",event));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("delete from analysis_review_event where id=?",event));
        assertEquals("accept",jdbc.queryForObject("select action from analysis_review_event where id=?",String.class,event));
    }
    private UUID snapshot(UUID job,UUID note,String body) {
        UUID id=UUID.randomUUID(); jdbc.update("""
          insert into analysis_snapshot(id,user_id,job_id,note_id,original_note_id,note_version,input_revision,title,body,body_sha256)
          values(?,?,?,?,?,0,0,'합성',?,?)
          """,id,userId,job,note,note,body,AiResponseValidator.hash(body)); return id;
    }
}
