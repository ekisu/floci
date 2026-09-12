package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable reservations: the identity itself is the reservation, with no external allocation. */
public final class DeterministicLambdaNames {
    public static final String GENERATION = "FlociLambdaGeneration";

    private DeterministicLambdaNames() { }

    public static boolean enabled() {
        return ConfigProvider.getConfig().getOptionalValue(
                "floci.cloudformation.deterministic-lambda-names", Boolean.class).orElse(false);
    }

    public static String name(String account, String region, String root, String path, long generation,
                              String explicitName) {
        if (account == null || !account.matches("[0-9]{12}") || region == null
                || !region.matches("[a-z0-9-]+") || root == null || !root.matches("[A-Za-z][A-Za-z0-9-]*")
                || path == null || !path.matches("[A-Za-z0-9]+(/[A-Za-z0-9]+)*") || generation < 0) {
            throw new AwsException("ValidationError", "Invalid deterministic Lambda identity scope", 400);
        }
        if (explicitName != null) {
            if (!explicitName.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new AwsException("ValidationError", "Invalid explicit Lambda FunctionName", 400);
            }
            return explicitName;
        }
        String logical = path.substring(path.lastIndexOf('/') + 1);
        String prefix = root.substring(0, Math.min(root.length(), 24)) + "-"
                + logical.substring(0, Math.min(logical.length(), 25));
        try {
            String identity = String.join("\n", "floci-lambda-v1", account, region, root, path,
                    Long.toString(generation));
            String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 13);
            return prefix + "-" + suffix;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Computing deterministic Lambda identity", e);
        }
    }
}
