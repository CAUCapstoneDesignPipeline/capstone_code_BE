package com.example.capstone.support;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.auth.service.AccessTokenService;
import com.example.capstone.user.domain.AppUser;
import com.example.capstone.user.domain.UserIdentity;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.user.repository.UserIdentityRepository;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class ApiIntegrationSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AppUserRepository users;
    @Autowired protected UserIdentityRepository identities;
    @Autowired protected AccessTokenService tokens;
    protected UUID userId;
    protected UUID otherId;
    protected String bearer;
    protected String otherBearer;
    @BeforeEach
    void actors() {
        userId=actor(); otherId=actor(); bearer=tokens.issue(userId); otherBearer=tokens.issue(otherId);
    }
    private UUID actor() {
        var user=users.saveAndFlush(new AppUser("fixture@example.com","사용자",Instant.now()));
        identities.saveAndFlush(new UserIdentity(user.getId(),"google",UUID.randomUUID().toString(),user.getEmail(),true,Instant.now()));
        return user.getId();
    }
    protected ResultActions call(String method,String path,String body) throws Exception { return call(method,path,body,bearer); }
    protected ResultActions call(String method,String path,String body,String token) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").content(body);
        return mvc.perform(request);
    }
    protected JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
    protected UUID note(String title,UUID topicId,String body) throws Exception {
        var fields=new java.util.LinkedHashMap<String,Object>();
        fields.put("title",title); fields.put("topicId",topicId); fields.put("body",body);
        return UUID.fromString(json(call("POST","/api/notes",mapper.writeValueAsString(fields))
                .andExpect(status().isCreated())).path("id").asString());
    }
    protected UUID topic(String name) throws Exception {
        return UUID.fromString(json(call("POST","/api/topics",mapper.writeValueAsString(java.util.Map.of("name",name)))
                .andExpect(status().isCreated())).path("id").asString());
    }
}
