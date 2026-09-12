package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.CloudFormationNamingConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/** Local-only fork extension, enabled together with deterministic naming. */
@Path("/_floci/cloudformation/lambda-name")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaNameReservationController {
    private final CloudFormationNamingConfig config;

    @Inject
    public LambdaNameReservationController(CloudFormationNamingConfig config) {
        this.config = config;
    }

    @GET
    public Map<String, Object> reserve(@QueryParam("accountId") String account,
                                      @QueryParam("region") String region,
                                      @QueryParam("rootStack") String root,
                                      @QueryParam("logicalPath") String path,
                                      @QueryParam("generation") @DefaultValue("0") long generation,
                                      @QueryParam("explicitName") String explicitName) {
        if (!config.deterministicLambdaNames()) {
            throw new NotFoundException();
        }
        String name = DeterministicLambdaNames.name(account, region, root, path, generation, explicitName);
        return Map.of("functionName", name, "functionArn", "arn:aws:lambda:" + region + ":" + account
                + ":function:" + name, "generation", generation, "rootStack", root, "logicalPath", path);
    }
}
