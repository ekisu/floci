package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inline {@code ZipFile} Lambda packages must carry the cfn-response module AWS injects
 * for that code path — custom-resource handlers in AWS Solutions templates (e.g. Landing
 * Zone Accelerator's installer stack) {@code require('cfn-response')} / {@code import
 * cfnresponse} and fail at init without it.
 */
class InlineZipCfnResponseModuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"python3.7", "python3.14", "public.ecr.aws/lambda/python:3.7",
            "public.ecr.aws/lambda/python@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "public.ecr.aws/lambda/python:3.7@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "amazon/aws-lambda-python:3.12", "docker.io/amazon/aws-lambda-python:3.12"})
    void pythonIdentifiersAndKnownImagesPreserveSourceAndHelper(String runtime) throws Exception {
        String source = "import cfnresponse\nimport requests\ndef handler(event, context):\n    return 'ação 日本語'\n";
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(source, "custom.handler", runtime));
        assertEquals(source, entries.get("custom.py"));
        assertEquals(2, entries.size());
        assertEquals(unzip(InlineZipPackager.sourceToZipBase64(source, "custom.handler", "python3.12"))
                .get("cfnresponse.py"), entries.get("cfnresponse.py"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nodejs", "nodejs4.3", "nodejs22.x", "public.ecr.aws/lambda/nodejs:22",
            "public.ecr.aws/lambda/nodejs@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "amazon/aws-lambda-nodejs:22", "docker.io/amazon/aws-lambda-nodejs:22"})
    void nodeIdentifiersAndKnownImagesPreserveSourceAndHelper(String runtime) throws Exception {
        String source = "const response = require('cfn-response');\nexports.handler = () => 'ação 日本語';\n";
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(source, "custom.handler", runtime));
        assertEquals(source, entries.get("custom.js"));
        assertEquals(3, entries.size());
        Map<String, String> standard = unzip(InlineZipPackager.sourceToZipBase64(source, "custom.handler", "nodejs22.x"));
        assertEquals(standard, entries);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"java21", "provided.al2", "python-custom", "nodejs-custom", "python:3.12",
            "example.com/lambda/python:3.12", "example.com/nodejs:22", "public.ecr.aws/lambda/python-unknown:3.12",
            "public.ecr.aws/lambda/python@sha256:bad", "public.ecr.aws/lambda/ruby:3.4",
            "public.ecr.aws/lambda/python:3.12/other", "public.ecr.aws/lambda/python:", " "})
    void unknownInlineLanguageFailsExplicitly(String runtime) {
        AwsException error = assertThrows(AwsException.class,
                () -> InlineZipPackager.sourceToZipBase64("source", "index.handler", runtime));
        assertTrue(error.getMessage().contains("Code.ZipFile"));
        assertTrue(error.getMessage().contains("cannot determine inline source language"));
    }

    private static Map<String, String> unzip(String base64) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void nodeZipCarriesRequirableCfnResponseModule() throws Exception {
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(
                "exports.handler = async () => {};", "index.handler", "nodejs22.x"));

        assertEquals("exports.handler = async () => {};", entries.get("index.js"));
        assertTrue(entries.containsKey("node_modules/cfn-response/package.json"));
        String module = entries.get("node_modules/cfn-response/cfn-response.js");
        assertTrue(module.contains("exports.send"));
        // Floci's ResponseURL is plain http on the emulator port, so the module must
        // pick the transport and port from the URL instead of hardcoding https:443.
        assertTrue(module.contains("require(\"http\")"));
        assertTrue(module.contains("parsedUrl.port"));
    }

    @Test
    void pythonZipCarriesCfnresponseModule() throws Exception {
        Map<String, String> entries = unzip(InlineZipPackager.sourceToZipBase64(
                "def handler(event, context):\n    pass\n", "index.handler", "python3.12"));

        assertTrue(entries.containsKey("index.py"));
        String module = entries.get("cfnresponse.py");
        assertTrue(module.contains("def send("));
        assertTrue(module.contains("HTTPConnection"));
        assertFalse(entries.containsKey("node_modules/cfn-response/cfn-response.js"));
    }
}
