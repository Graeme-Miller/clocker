/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.sdn;

import java.net.InetAddress;
import java.util.Collections;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.entity.stock.DelegateEntity;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.TaskBuilder;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.time.Duration;

import brooklyn.entity.container.docker.DockerHost;
import brooklyn.entity.container.docker.DockerInfrastructure;
import brooklyn.networking.VirtualNetwork;

/**
 * An SDN agent process on a Docker host.
 */
public abstract class SdnAgentImpl extends SoftwareProcessImpl implements SdnAgent {

    private static final Logger LOG = LoggerFactory.getLogger(SdnAgent.class);

    @Override
    public void init() {
        super.init();

        ConfigToAttributes.apply(this, DOCKER_HOST);
        ConfigToAttributes.apply(this, SDN_PROVIDER);
    }

    @Override
    public SdnAgentDriver getDriver() {
        return (SdnAgentDriver) super.getDriver();
    }

    @Override
    protected void connectSensors() {
        super.connectSensors();
        super.connectServiceUpIsRunning();
    }

    @Override
    public void disconnectSensors() {
        super.disconnectServiceUpIsRunning();
        super.disconnectSensors();
    }

    @Override
    public DockerHost getDockerHost() {
        return sensors().get(DOCKER_HOST);
    }

    @Override
    public void preStart() {
        InetAddress address = ((DockerSdnProvider) sensors().get(SDN_PROVIDER)).getNextAgentAddress(getId());
        sensors().set(SDN_AGENT_ADDRESS, address);
    }

    @Override
    public void postStart() {
        getDockerHost().sensors().set(SDN_AGENT, this);
    }

    @Override
    public void rebind() {
        super.rebind();
        // TODO implement custom SDN agent rebind logic
    }

    @Override
    public InetAddress attachNetwork(String containerId, final String networkId) {
        final SdnProvider provider = sensors().get(SDN_PROVIDER);
        boolean createNetwork = false;
        Cidr subnetCidr = null;
        synchronized (provider.getNetworkMutex()) {
            subnetCidr = provider.getSubnetCidr(networkId);
            if (subnetCidr == null) {
                subnetCidr = provider.getNextSubnetCidr(networkId);
                createNetwork = true;
            }
        }
        if (createNetwork) {
            // Get a CIDR for the subnet from the availabkle pool and create a virtual network
            EntitySpec<VirtualNetwork> networkSpec = EntitySpec.create(VirtualNetwork.class)
                    .configure(VirtualNetwork.NETWORK_ID, networkId)
                    .configure(VirtualNetwork.NETWORK_CIDR, subnetCidr);

            // Start and then add this virtual network as a child of SDN_NETWORKS
            VirtualNetwork network = provider.sensors().get(SdnProvider.SDN_NETWORKS).addChild(networkSpec);
            Entities.manage(network);
            Entities.start(network, Collections.singleton(((DockerInfrastructure) provider.sensors().get(DockerSdnProvider.DOCKER_INFRASTRUCTURE)).getDynamicLocation()));
            Entities.waitForServiceUp(network);
        } else {
            Task<Boolean> lookup = TaskBuilder.<Boolean> builder()
                    .displayName("Waiting until virtual network is available")
                    .body(new Callable<Boolean>() {
                        @Override
                        public Boolean call() throws Exception {
                            return Repeater.create()
                                    .every(Duration.TEN_SECONDS)
                                    .until(new Callable<Boolean>() {
                                        public Boolean call() {
                                            Optional<Entity> found = Iterables.tryFind(provider.sensors().get(SdnProvider.SDN_NETWORKS).getMembers(),
                                                    EntityPredicates.attributeEqualTo(VirtualNetwork.NETWORK_ID, networkId));
                                            return found.isPresent();
                                        }
                                    })
                                    .limitTimeTo(Duration.ONE_MINUTE)
                                    .run();
                        }
                    })
                    .build();
            Boolean result = DynamicTasks.queueIfPossible(lookup)
                    .orSubmitAndBlock()
                    .andWaitForSuccess();
            if (!result) {
                throw new IllegalStateException(String.format("Cannot find virtual network entity for %s", networkId));
            }
        }

        InetAddress address = getDriver().attachNetwork(containerId, networkId);
        LOG.info("Attached container ID {} to {}: {}", new Object[] { containerId, networkId,  address.getHostAddress() });

        // Rescan SDN network groups for containers
        DynamicGroup network = (DynamicGroup) Iterables.find(provider.sensors().get(SdnProvider.SDN_APPLICATIONS).getMembers(),
                EntityPredicates.attributeEqualTo(VirtualNetwork.NETWORK_ID, networkId));
        network.rescanEntities();

        return address;
    }

    @Override
    public String provisionNetwork(VirtualNetwork network) {
        String networkId = network.sensors().get(VirtualNetwork.NETWORK_ID);

        // Record the network CIDR being provisioned, allocating if required
        Cidr subnetCidr = network.config().get(VirtualNetwork.NETWORK_CIDR);
        if (subnetCidr == null) {
            subnetCidr = sensors().get(SDN_PROVIDER).getNextSubnetCidr(networkId);
        } else {
            sensors().get(SDN_PROVIDER).recordSubnetCidr(networkId, subnetCidr);
        }
        network.sensors().set(VirtualNetwork.NETWORK_CIDR, subnetCidr);

        // Create the netwoek using the SDN driver
        getDriver().createSubnet(network.getId(), networkId, subnetCidr);

        return networkId;
    }

    static {
        RendererHints.register(DOCKER_HOST, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(SDN_PROVIDER, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(SDN_AGENT, new RendererHints.NamedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
    }

}
