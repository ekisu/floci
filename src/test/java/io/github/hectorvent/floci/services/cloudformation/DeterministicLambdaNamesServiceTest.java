package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeterministicLambdaNamesServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Test
    void scopeAndGenerationAreUnambiguousAndNamesFitAwsLimit() {
        String root = "Stack".repeat(25);
        String path = "Nested/Function".replace("Function", "Function".repeat(25));
        String first = DeterministicLambdaNames.name(ACCOUNT, REGION, root, path, 0, null);
        assertEquals(64, first.length());
        assertTrue(first.matches("[A-Za-z0-9-]+-[a-f0-9]{13}"));
        assertEquals(first, DeterministicLambdaNames.name(ACCOUNT, REGION, root, path, 0, null));
        assertNotEquals(first, DeterministicLambdaNames.name(ACCOUNT, REGION, root, path, 1, null));
        assertNotEquals(first, DeterministicLambdaNames.name("111111111111", REGION, root, path, 0, null));
        assertNotEquals(first, DeterministicLambdaNames.name(ACCOUNT, "us-west-2", root, path, 0, null));
        assertNotEquals(first, DeterministicLambdaNames.name(ACCOUNT, REGION, root, "Other/" + path, 0, null));
        assertNotEquals(first, DeterministicLambdaNames.name(ACCOUNT, REGION, root + "X", path, 0, null));
        assertEquals("explicit_name", DeterministicLambdaNames.name(ACCOUNT, REGION, root, path, 9, "explicit_name"));
        assertThrows(AwsException.class, () -> DeterministicLambdaNames.name(ACCOUNT, REGION, root, path, -1, null));
    }

    @Test
    void queryMatchesProvisioningAndUpdateReplacementFailureDoNotDrift() throws Exception {
        try (MockedStatic<DeterministicLambdaNames> names = mockStatic(DeterministicLambdaNames.class, CALLS_REAL_METHODS)) {
            names.when(DeterministicLambdaNames::enabled).thenReturn(true);
            ObjectMapper mapper = new ObjectMapper();
            LambdaService lambda = mock(LambdaService.class);
            when(lambda.createFunction(anyString(), anyMap())).thenAnswer(inv -> function(
                    (String) ((Map<?, ?>) inv.getArgument(1)).get("FunctionName"), "Zip"));
            CloudFormationResourceProvisioner provisioner = CfnProvisionerFixture.builder()
                    .lambda(lambda).objectMapper(mapper).build();
            CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(ACCOUNT, REGION, "Root-Nested",
                    "stack-id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper, name -> null);
            engine.setNamingScope("Root", "Nested/");
            Map<String, Object> reservation = new LambdaNameReservationController(() -> true)
                    .reserve(ACCOUNT, REGION, "Root", "Nested/Function", 0, null);
            StackResource created = provisioner.provision("Function", "AWS::Lambda::Function", mapper.readTree("{}"),
                    engine, REGION, ACCOUNT, "Root-Nested", null, Map.of());
            assertEquals("CREATE_COMPLETE", created.getStatus());
            assertEquals(reservation.get("functionName"), created.getPhysicalId());
            assertEquals("0", created.getAttributes().get(DeterministicLambdaNames.GENERATION));
            when(lambda.getFunction(REGION, created.getPhysicalId())).thenReturn(function(created.getPhysicalId(), "Zip"));
            when(lambda.updateFunctionConfiguration(anyString(), anyString(), anyMap()))
                    .thenReturn(function(created.getPhysicalId(), "Zip"));
            StackResource updated = provisioner.provision("Function", "AWS::Lambda::Function", mapper.readTree("{}"),
                    engine, REGION, ACCOUNT, "Root-Nested", created.getPhysicalId(), created.getAttributes());
            assertEquals(created.getPhysicalId(), updated.getPhysicalId());
            assertEquals("0", updated.getAttributes().get(DeterministicLambdaNames.GENERATION));
            when(lambda.createFunction(anyString(), anyMap())).thenThrow(new AwsException("ResourceConflictException", "occupied", 409));
            StackResource failed = provisioner.provision("Function", "AWS::Lambda::Function",
                    mapper.readTree("{\"PackageType\":\"Image\",\"Code\":{\"ImageUri\":\"local:test\"}}"),
                    engine, REGION, ACCOUNT, "Root-Nested", created.getPhysicalId(), created.getAttributes());
            assertEquals("CREATE_FAILED", failed.getStatus());
            assertEquals("0", created.getAttributes().get(DeterministicLambdaNames.GENERATION));
            verify(lambda, never()).deleteFunction(anyString(), anyString());
            doAnswer(inv -> function((String) ((Map<?, ?>) inv.getArgument(1)).get("FunctionName"), "Image"))
                    .when(lambda).createFunction(anyString(), anyMap());
            doThrow(new AwsException("ValidationException", "invalid concurrency", 400))
                    .when(lambda).putFunctionConcurrency(anyString(), anyString(), anyInt());
            StackResource rolledBack = provisioner.provision("Function", "AWS::Lambda::Function",
                    mapper.readTree("{\"PackageType\":\"Image\",\"ReservedConcurrentExecutions\":5,\"Code\":{\"ImageUri\":\"local:test\"}}"),
                    engine, REGION, ACCOUNT, "Root-Nested", created.getPhysicalId(), created.getAttributes());
            assertEquals("CREATE_FAILED", rolledBack.getStatus());
            assertEquals("0", rolledBack.getAttributes().get(DeterministicLambdaNames.GENERATION));
            verify(lambda).deleteFunction(REGION,
                    DeterministicLambdaNames.name(ACCOUNT, REGION, "Root", "Nested/Function", 1, null));
            verify(lambda, never()).deleteFunction(REGION, created.getPhysicalId());
            StackResource replacement = provisioner.provision("Function", "AWS::Lambda::Function",
                    mapper.readTree("{\"PackageType\":\"Image\",\"Code\":{\"ImageUri\":\"local:test\"}}"),
                    engine, REGION, ACCOUNT, "Root-Nested", created.getPhysicalId(), created.getAttributes());
            assertEquals("CREATE_COMPLETE", replacement.getStatus());
            assertEquals(DeterministicLambdaNames.name(ACCOUNT, REGION, "Root", "Nested/Function", 1, null), replacement.getPhysicalId());
            assertEquals("1", replacement.getAttributes().get(DeterministicLambdaNames.GENERATION));
            verify(lambda).deleteFunction(REGION, created.getPhysicalId());
        }
    }

    private LambdaFunction function(String name, String packageType) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name);
        function.setPackageType(packageType);
        return function;
    }
}
