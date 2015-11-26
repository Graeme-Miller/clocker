package brooklyn.entity.mesos.jumphost;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.sensor.AttributeSensorAndConfigKey;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcess;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcessImpl;

@ImplementedBy(JumpHostImpl.class)
public interface JumpHost extends EmptySoftwareProcess {
}
