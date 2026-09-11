package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationBootstrapSubIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260911/us-east-1/cloudformation/aws4_request";

    private String call(String action, String stack, String parameter, String value) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH).formParam("Action", action)
                .formParam("StackName", stack).formParam(parameter, value)
                .when().post("/").then().statusCode(200).extract().asString();
    }

    @Test
    void bootstrapSubDomainCanFetchPrivateS3Template() {
        String stack = "bootstrap-sub-" + Long.toString(System.nanoTime(), 36);
        String child = stack + "-child";
        call("CreateStack", stack, "TemplateBody", """
                Resources:
                  StagingBucket:
                    Type: AWS::S3::Bucket
                Outputs:
                  BucketName:
                    Value: !Ref StagingBucket
                  BucketDomainName:
                    Value:
                      Fn::Sub: ${StagingBucket.RegionalDomainName}
                  DirectDomain:
                    Value: !GetAtt StagingBucket.RegionalDomainName
                """);
        String bucket = null;
        try {
            String xml = call("DescribeStacks", stack, "Version", "2010-05-15");
            var outputs = XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
            bucket = outputs.get("BucketName");
            String domain = outputs.get("BucketDomainName");
            assertEquals(bucket + ".s3.us-east-1.amazonaws.com", domain);
            assertEquals(outputs.get("DirectDomain"), domain);
            given().header("Authorization", AUTH.replace("/cloudformation/", "/s3/"))
                    .body("Resources: {}\nOutputs:\n  Probe:\n    Value: private-template-loaded\n")
                    .when().put("/" + bucket + "/cdk/template.yml").then().statusCode(200);
            call("CreateStack", child, "TemplateURL", "https://" + domain + "/cdk/template.yml");
            String childXml = call("DescribeStacks", child, "Version", "2010-05-15");
            assertEquals("private-template-loaded",
                    XmlParser.extractPairs(childXml, "Outputs", "OutputKey", "OutputValue").get("Probe"));
        } finally {
            call("DeleteStack", child, "Version", "2010-05-15");
            if (bucket != null) {
                given().header("Authorization", AUTH.replace("/cloudformation/", "/s3/"))
                        .when().delete("/" + bucket + "/cdk/template.yml").then().statusCode(204);
            }
            call("DeleteStack", stack, "Version", "2010-05-15");
        }
    }
}
