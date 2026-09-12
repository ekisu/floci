package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationIamPolicyIntrinsicsIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private String query(String service, String action, Map<String, String> params, String path) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260912/us-east-1/"
                        + service + "/aws4_request")
                .formParam("Action", action).formParams(params).post("/").then().statusCode(200)
                .extract().xmlPath().getString(path);
    }

    private JsonNode document(String value) throws Exception {
        return mapper.readTree(value.startsWith("%") ? URLDecoder.decode(value, StandardCharsets.UTF_8) : value);
    }

    @Test
    void nativeSsmParameterRefsResolveInEveryIamPolicyDocumentWithoutChangingTemplate() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "iam-intrinsics-" + suffix;
        String role = "iam-intrinsics-role-" + suffix;
        String parameter = "/lambda/eligibility/" + suffix;
        String arn = "arn:aws:lambda:us-east-1:000000000000:function:Eligibility-Handler-a1b2c3d4e5f6g";
        String principal = "arn:aws:iam::000000000000:role/NativeCaller";
        given().contentType("application/x-amz-json-1.1").header("X-Amz-Target", "AmazonSSM.PutParameter")
                .body(mapper.writeValueAsBytes(Map.of("Name", parameter, "Type", "String", "Value", arn)))
                .post("/").then().statusCode(200);
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Action":["lambda:InvokeFunction"],"Resource":{"Ref":"EligibilityArn"},
                  "Condition":{"StringEquals":{"aws:PrincipalArn":{"Ref":"CallerArn"},
                    "literal":"${aws:username}","escaped":{"Fn::Sub":"${!aws:username}"}}}
                },{"Effect":"Allow","Action":"lambda:GetFunction","Resource":{"Fn::If":["Enabled",
                  [{"Ref":"EligibilityArn"},{"Fn::Join":["",[{"Ref":"EligibilityArn"},":live"]]}],
                  ["never-selected"]]}}]}
                """;
        String template = """
                {"Parameters":{
                  "EligibilityArn":{"Type":"AWS::SSM::Parameter::Value<String>","Default":"%s"},
                  "CallerArn":{"Type":"String","Default":"%s"}},
                 "Conditions":{"Enabled":{"Fn::Equals":["yes","yes"]}},
                 "Resources":{
                  "Role":{"Type":"AWS::IAM::Role","Properties":{"RoleName":"%s",
                    "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[{
                      "Effect":"Allow","Action":"sts:AssumeRole","Principal":{"AWS":{"Ref":"CallerArn"}}}]},
                    "Policies":[{"PolicyName":"embedded","PolicyDocument":%s}]}},
                  "Inline":{"Type":"AWS::IAM::Policy","Properties":{"PolicyName":"standalone",
                    "Roles":[{"Ref":"Role"}],"PolicyDocument":%s}},
                  "Managed":{"Type":"AWS::IAM::ManagedPolicy","Properties":{"PolicyDocument":%s}}
                },"Outputs":{"ManagedArn":{"Value":{"Ref":"Managed"}}}}
                """.formatted(parameter, principal, role, policy, policy, policy);
        try {
            query("cloudformation", "CreateStack", Map.of("StackName", stack, "TemplateBody", template),
                    "CreateStackResponse.CreateStackResult.StackId");
            assertEquals("CREATE_COMPLETE", query("cloudformation", "DescribeStacks", Map.of("StackName", stack),
                    "DescribeStacksResponse.DescribeStacksResult.Stacks.member.StackStatus"));
            for (String name : new String[]{"standalone", "embedded"}) {
                JsonNode actual = document(query("iam", "GetRolePolicy", Map.of("RoleName", role, "PolicyName", name),
                        "GetRolePolicyResponse.GetRolePolicyResult.PolicyDocument"));
                assertPolicy(actual, arn, principal);
            }
            String managed = query("cloudformation", "DescribeStacks", Map.of("StackName", stack),
                    "DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member.OutputValue");
            JsonNode actualManaged = document(query("iam", "GetPolicyVersion", Map.of("PolicyArn", managed, "VersionId", "v1"),
                    "GetPolicyVersionResponse.GetPolicyVersionResult.PolicyVersion.Document"));
            assertPolicy(actualManaged, arn, principal);
            JsonNode trust = document(query("iam", "GetRole", Map.of("RoleName", role),
                    "GetRoleResponse.GetRoleResult.Role.AssumeRolePolicyDocument"));
            assertEquals(principal, trust.at("/Statement/0/Principal/AWS").asText());
            assertEquals(mapper.readTree(template), mapper.readTree(query("cloudformation", "GetTemplate",
                    Map.of("StackName", stack, "TemplateStage", "Original"),
                    "GetTemplateResponse.GetTemplateResult.TemplateBody")));
        } finally {
            query("cloudformation", "DeleteStack", Map.of("StackName", stack), "DeleteStackResponse.ResponseMetadata.RequestId");
            given().contentType("application/x-amz-json-1.1").header("X-Amz-Target", "AmazonSSM.DeleteParameter")
                    .body(mapper.writeValueAsBytes(Map.of("Name", parameter))).post("/").then().statusCode(200);
        }
    }

    private void assertPolicy(JsonNode policy, String arn, String principal) {
        assertEquals(arn, policy.at("/Statement/0/Resource").asText());
        assertTrue(policy.at("/Statement/0/Action").isArray());
        assertEquals(principal, policy.at("/Statement/0/Condition/StringEquals/aws:PrincipalArn").asText());
        assertEquals("${aws:username}", policy.at("/Statement/0/Condition/StringEquals/literal").asText());
        assertEquals("${aws:username}", policy.at("/Statement/0/Condition/StringEquals/escaped").asText());
        assertTrue(policy.at("/Statement/1/Resource").isArray());
        assertEquals(arn, policy.at("/Statement/1/Resource/0").asText());
        assertEquals(arn + ":live", policy.at("/Statement/1/Resource/1").asText());
        assertFalse(policy.toString().contains("\"Ref\""));
        assertFalse(policy.toString().contains("Fn::"));
        assertFalse(policy.toString().contains("*"));
    }
}
