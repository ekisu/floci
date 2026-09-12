package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(LambdaNameReservationIntegrationTest.NamingProfile.class)
class LambdaNameReservationIntegrationTest {
    public static final class NamingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.cloudformation.deterministic-lambda-names", "true");
        }
    }

    @Test
    void queriedNameIsTheActualCloudFormationPhysicalId() {
        String root = "Reservation" + Long.toString(System.nanoTime(), 36);
        String name = given().queryParam("accountId", "000000000000").queryParam("region", "us-east-1")
                .queryParam("rootStack", root).queryParam("logicalPath", "Function")
                .get("/_floci/cloudformation/lambda-name").then().statusCode(200)
                .body("generation", equalTo(0)).extract().path("functionName");
        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CloudFormationLambdaMissingS3CodeIntegrationTest.CFN_AUTH)
                .formParam("Action", "CreateStack").formParam("StackName", root)
                .formParam("TemplateBody", """
                        {"Resources":{"Function":{"Type":"AWS::Lambda::Function","Properties":{
                          "Runtime":"nodejs22.x","Handler":"index.handler",
                          "Code":{"ZipFile":"exports.handler = async () => 'ok';"}
                        }}}}
                        """)
                .post("/").then().statusCode(200);
        given().get("/2015-03-31/functions/" + name).then().statusCode(200)
                .body("Configuration.FunctionName", equalTo(name));
        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CloudFormationLambdaMissingS3CodeIntegrationTest.CFN_AUTH)
                .formParam("Action", "DeleteStack").formParam("StackName", root)
                .post("/").then().statusCode(200);
    }
}
