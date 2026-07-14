package org.jawata.mcp.runtime.profile;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Sprint 24 (D13) — a short-lived local JMX connection to a session's target,
 * via the JDK's own Dynamic Attach (the same mechanism {@code jcmd} rides).
 * The dev/sim preset already enables local JMX (loopback-only, no
 * authentication because there is no remote surface to authenticate); this is
 * simply the JMX counterpart to {@link Jcmd} — one call, one connection,
 * closed when done.
 */
public final class JmxClient {

    private JmxClient() {
    }

    /** Attach, connect, run one operation against the connection, then always disconnect. */
    public static <T> T withConnection(long pid, Operation<T> operation) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        try {
            String address = vm.startLocalManagementAgent();
            JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
            try {
                return operation.run(connector.getMBeanServerConnection());
            } finally {
                connector.close();
            }
        } finally {
            vm.detach();
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run(MBeanServerConnection mbs) throws Exception;
    }

    /** The platform Logging MBean — read/set a logger's level, JUL's own runtime control surface. */
    public static final ObjectName LOGGING_MBEAN;

    static {
        try {
            LOGGING_MBEAN = new ObjectName("java.util.logging:type=Logging");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
