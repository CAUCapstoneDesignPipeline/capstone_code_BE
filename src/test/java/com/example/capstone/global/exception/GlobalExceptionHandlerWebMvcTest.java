package com.example.capstone.global.exception;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.capstone.auth.config.AuthenticationErrorHandler;
import com.example.capstone.auth.config.JwtConfig;
import com.example.capstone.auth.config.SecurityConfig;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerWebMvcTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerWebMvcTest.TestController.class,
        SecurityConfig.class, JwtConfig.class, AuthenticationErrorHandler.class})
@ActiveProfiles("test")
@WithMockUser
class GlobalExceptionHandlerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({
            "VALIDATION_FAILED,400", "UNAUTHENTICATED,401", "NOT_FOUND,404",
            "TOPIC_NAME_TAKEN,409", "TOPIC_ORDER_CONFLICT,409", "NOTE_TITLE_TAKEN,409",
            "NOTE_CONFLICT,409", "CANDIDATE_CLOSED,409", "INTERNAL,500"
    })
    void mapsApiErrorCodesToHttpStatusAndOmitsEmptyDetails(String code, int httpStatus) throws Exception {
        mockMvc.perform(get("/test/errors/{code}", code))
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"error":{"code":"%s","message":"사용자용 오류 문구"}}
                        """.formatted(code)))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    void preservesConflictDetailsIncludingNestedNull() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"error":{"code":"NOTE_CONFLICT","message":"다른 곳에서 이 노트가 먼저 수정되었습니다.",
                          "details":{"current":{"topicId":null,"title":"현재 제목"}}}}
                        """))
                .andExpect(jsonPath("$.error.details.current").value(org.hamcrest.Matchers.hasKey("topicId")));
    }

    @Test
    void returnsFieldValidationWithoutRejectedValueOrValidatorMessage() throws Exception {
        mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":{"code":"VALIDATION_FAILED","message":"요청 값이 올바르지 않습니다.",
                          "details":{"fields":[{"field":"title","reason":"empty"}]}}}
                        """))
                .andExpect(content().string(not(containsString("validator-internal-message"))))
                .andExpect(jsonPath("$.error.details.fields[0].rejectedValue").doesNotExist());
    }

    @Test
    void treatsMissingRequiredFieldAsInvalidRatherThanAnEmptyString() throws Exception {
        mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("title"))
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("invalid"));
    }

    @Test
    void returnsMalformedJsonAsBadRequestWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":secret-invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("secret-invalid-json"))));
    }

    @Test
    void returnsInvalidPathValueAsBadRequest() throws Exception {
        mockMvc.perform(get("/test/ids/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsMissingQueryParameterAsBadRequest() throws Exception {
        mockMvc.perform(get("/test/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returnsUnmappedPathAsNotFound() throws Exception {
        mockMvc.perform(get("/test/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void preservesMethodNotAllowedStatusAndAllowHeader() throws Exception {
        mockMvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void preservesUnsupportedMediaTypeStatus() throws Exception {
        mockMvc.perform(post("/test/validated").contentType(MediaType.TEXT_PLAIN).content("private note"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("private note"))));
    }

    @Test
    void returnsSafeInternalErrorForUnexpectedException() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"error":{"code":"INTERNAL","message":"일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요."}}
                        """))
                .andExpect(content().string(not(containsString("private SQL or credentials"))));
    }

    @Test
    void keepsSuccessfulDtoAndItsNullFieldUnwrapped() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"topicId\":null,\"title\":\"현재 제목\"}"))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("topicId")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void keepsNoContentResponseEmpty() throws Exception {
        mockMvc.perform(get("/test/no-content"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/errors/{code}")
        void apiError(@PathVariable ErrorCode code) {
            throw new ApiException(code, "사용자용 오류 문구");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.NOTE_CONFLICT, "다른 곳에서 이 노트가 먼저 수정되었습니다.",
                    Map.of("current", new TestResponse(null, "현재 제목")));
        }

        @PostMapping("/test/validated")
        TestRequest validated(@Valid @RequestBody TestRequest request) {
            return request;
        }

        @GetMapping("/test/ids/{id}")
        UUID id(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/test/query")
        String query(@RequestParam String q) {
            return q;
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("private SQL or credentials");
        }

        @GetMapping("/test/success")
        TestResponse success() {
            return new TestResponse(null, "현재 제목");
        }

        @GetMapping("/test/no-content")
        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }
    }

    record TestRequest(@NotBlank(message = "validator-internal-message") String title) {
    }

    record TestResponse(UUID topicId, String title) {
    }
}
