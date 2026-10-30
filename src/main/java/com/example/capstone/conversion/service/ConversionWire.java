package com.example.capstone.conversion.service;

import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import com.example.capstone.analysis.client.AiResponseValidator;
import static com.example.capstone.conversion.service.ConversionErrors.*;

@Component
public class ConversionWire {
    public static final String REVISION="ocr-conversion-2026-10-30-adopted.1";
    private final ObjectMapper mapper;
    public ConversionWire(ObjectMapper mapper) { this.mapper=mapper; }
    public void fields(JsonNode node,String... names) { if(node==null || !node.isObject() || node.size()!=names.length)throw invalid();for(String name:names)if(!node.has(name))throw invalid(); }
    public String text(JsonNode n,int min,int max) { if(n==null || !n.isString())throw invalid();String s=n.asString();if(s.codePoints().anyMatch(c->c>=0xD800 && c<=0xDFFF))throw invalid();if(s.codePointCount(0,s.length())<min || s.codePointCount(0,s.length())>max)throw invalid();return s; }
    public UUID uuid(JsonNode n) { try { String s=text(n,36,36);UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw invalid();return id; }catch(RuntimeException invalid){throw invalid();} }
    public String hash(JsonNode n) { String h=text(n,64,64);if(!h.matches("[a-f0-9]{64}"))throw invalid();return h; }
    public int integer(JsonNode n,int min,int max) { if(n==null || !n.isIntegralNumber() || !n.canConvertToInt() || n.asInt()<min || n.asInt()>max)throw invalid();return n.asInt(); }
    public ObjectNode job(JsonNode row) { var n=mapper.createObjectNode().put("id",row.path("id").asString()).put("fileId",row.path("file_id").asString()).put("status",row.path("status").asString()).put("conversionRevision",row.path("conversion_revision").asInt());n.set("resultRevision",row.path("result_revision"));n.set("errorCode",row.path("error_code"));return n; }
    public JsonNode receipt(JsonNode row) { return mapper.createObjectNode().put("fileId",row.path("id").asString()).put("mediaType",row.path("media_type").asString()).put("contentSha256",row.path("content_sha256").asString()).put("byteSize",row.path("byte_size").asLong()).put("pageCount",row.path("page_count").asInt()); }
    public JsonNode confirmed(JsonNode c) { return mapper.createObjectNode().put("noteId",c.path("original_note_id").asString()).put("sourceFileId",c.path("file_id").asString()).put("conversionJobId",c.path("job_id").asString()).put("resultRevision",c.path("result_revision").asInt()).put("noteVersion",c.path("note_version").asInt()).put("mappingStatus",c.path("mapping_status").asString().equals("verified")?"verified":"unverified"); }
    public JsonNode validate(byte[] bytes,JsonNode request,String engine) {
        try {
            if(bytes==null || bytes.length>4*1024*1024)throw invalid();JsonNode result=mapper.readTree(bytes);
            fields(result,"contractRevision","jobId","attemptId","ownerId","sourceSha256","conversionRevision","resultRevision","engineRevision","markdown","markdownSha256","pages","mappings","unknownRegions","costMicrounits");
            for(String key:List.of("contractRevision","jobId","attemptId","ownerId","conversionRevision"))if(!AiResponseValidator.sameJson(result.path(key),request.path(key)))throw invalid();
            if(!hash(result.path("sourceSha256")).equals(request.path("source").path("contentSha256").asString()) || !text(result.path("engineRevision"),1,200).equals(engine))throw invalid();
            if(integer(result.path("resultRevision"),1,Integer.MAX_VALUE)!=1)throw invalid();
            String markdown=text(result.path("markdown"),0,50000);if(!Normalizer.isNormalized(markdown,Normalizer.Form.NFC) || !AiResponseValidator.hash(markdown).equals(hash(result.path("markdownSha256"))))throw invalid();
            int pageCount=request.path("source").path("pageCount").asInt();var pages=result.path("pages");if(!pages.isArray() || pages.size()!=pageCount)throw invalid();Set<Integer> seen=new HashSet<>(),converted=new HashSet<>();
            for(var page:pages) { fields(page,"page","status","errorCode");int number=integer(page.path("page"),1,pageCount);if(!seen.add(number))throw invalid();String status=text(page.path("status"),1,20);if(!Set.of("converted","failed","unreadable").contains(status))throw invalid();if(!page.path("errorCode").isNull())text(page.path("errorCode"),1,200);if(status.equals("converted")) { if(!page.path("errorCode").isNull())throw invalid();converted.add(number); } }
            var mappings=result.path("mappings");if(!mappings.isArray() || mappings.size()>10000)throw invalid();
            for(var mapping:mappings) {
                fields(mapping,"page","region","startOffset","endOffset","quote","status");int page=integer(mapping.path("page"),1,pageCount);
                int start=integer(mapping.path("startOffset"),0,markdown.length()),end=integer(mapping.path("endOffset"),1,markdown.length());
                if(end<=start || split(markdown,start) || split(markdown,end) || !markdown.substring(start,end).equals(text(mapping.path("quote"),1,50000)))throw invalid();
                String status=text(mapping.path("status"),1,20);if(!Set.of("verified","unverified","invalidated").contains(status) || (status.equals("verified") && !converted.contains(page)))throw invalid();
                var box=mapping.path("region");if(!box.isArray() || box.size()!=4)throw invalid();for(var coordinate:box)if(!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble()) || coordinate.asDouble()<0 || coordinate.asDouble()>1)throw invalid();
                if(box.get(2).asDouble()<=box.get(0).asDouble() || box.get(3).asDouble()<=box.get(1).asDouble())throw invalid();
            }
            var unknown=result.path("unknownRegions");if(!unknown.isArray() || unknown.size()>10000)throw invalid();for(var region:unknown) { fields(region,"page","reason");integer(region.path("page"),1,pageCount);text(region.path("reason"),1,2000); }
            var cost=result.path("costMicrounits");if(!cost.isNull() && (!cost.isIntegralNumber() || !cost.canConvertToLong() || cost.asLong()<0 || cost.asLong()>request.path("limits").path("maxCostMicrounits").asLong()))throw invalid();
            return result;
        } catch(RuntimeException failure) { throw new InvalidOutput(failure); }
    }
    private boolean split(String s,int offset) { return offset>0 && offset<s.length() && Character.isHighSurrogate(s.charAt(offset-1)) && Character.isLowSurrogate(s.charAt(offset)); }
    public static final class InvalidOutput extends RuntimeException { public InvalidOutput(Throwable cause){super("Invalid conversion output",cause);} }
}
