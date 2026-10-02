package com.example.capstone.note.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

@Schema(example = "{\"title\":\"트랜잭션\",\"topicId\":null,\"body\":\"트랜잭션은 여러 작업을 하나의 단위로 처리합니다.\"}")
public class NoteCreateRequest {
    @Schema(description = "필수. NFC 정규화·trim 후 1~200 코드 포인트. / 금지, 같은 주제 내 중복 금지.", requiredMode = Schema.RequiredMode.REQUIRED, example = "트랜잭션", minLength = 1, maxLength = 200)
    @JsonProperty private String title;
    @Schema(description = "본인 주제 UUID. 생략 또는 null이면 미분류입니다.", types = {"string", "null"})
    @JsonProperty private UUID topicId;
    private String body = "";
    @Schema(description = "생략은 빈 문자열. 명시적 null은 400. NFC 정규화, 공백 유지, 최대 1,000,000 코드 포인트.", example = "트랜잭션은 여러 작업을 하나의 단위로 처리합니다.", maxLength = 1000000)
    @JsonSetter(nulls=Nulls.FAIL)
    public void setBody(String body) { this.body=body; }
    public String title() { return title; }
    public UUID topicId() { return topicId; }
    public String body() { return body; }
}
