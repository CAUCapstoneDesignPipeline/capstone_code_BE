package com.example.capstone.conversion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.conversion.service.ConversionWire;
import static org.junit.jupiter.api.Assertions.*;
class ConversionWireTest {
 @Test void validAdapterResult() throws Exception {
  var mapper=new ObjectMapper();var request=mapper.createObjectNode().put("contractRevision",ConversionWire.REVISION).put("jobId",java.util.UUID.randomUUID().toString()).put("attemptId",java.util.UUID.randomUUID().toString()).put("ownerId",java.util.UUID.randomUUID().toString()).put("conversionRevision",1);
  request.putObject("source").put("pageCount",1).put("contentSha256","a".repeat(64));request.putObject("limits").put("maxCostMicrounits",1000);
  var stub=new ConversionIntegrationTest.Stub(mapper);
  var raw=stub.convert(request,new java.io.ByteArrayInputStream(new byte[]{1}));assertDoesNotThrow(()->new ConversionWire(mapper).validate(raw,request,stub.engineRevision()));
 }
}
