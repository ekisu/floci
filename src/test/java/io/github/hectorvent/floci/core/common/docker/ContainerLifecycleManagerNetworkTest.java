package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerNetworkTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectsIsolatedNetworkBeforeStartEvenWithPublishedPorts(boolean publishPort) {
        DockerClient docker = mock(DockerClient.class);
        ImageCacheService images = mock(ImageCacheService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(images.ensureImageExists(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(docker.createContainerCmd("registry:2")).thenReturn(create);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("registry-id");
        when(create.exec()).thenReturn(response);
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(docker.startContainerCmd("registry-id")).thenReturn(start);
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, images,
                mock(ContainerDetector.class), mock(PortAllocator.class), config);
        ContainerSpec spec = new ContainerSpec("registry:2", "registry", List.of(), null, null, null,
                publishPort ? Map.of(5000, 5100) : Map.of(), List.of(), "sandbox-internal",
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of());

        manager.createAndStart(spec);

        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        var order = inOrder(create, start);
        order.verify(create).withHostConfig(host.capture());
        order.verify(create).exec();
        order.verify(start).exec();
        assertEquals("sandbox-internal", host.getValue().getNetworkMode());
        if (publishPort) {
            assertEquals("5100", host.getValue().getPortBindings().getBindings()
                    .get(ExposedPort.tcp(5000))[0].getHostPortSpec());
        }
        verify(docker, never()).connectToNetworkCmd();
    }
}
