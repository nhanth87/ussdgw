package org.mobicents.ussd.cdr.local.ra;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

import javax.slee.Address;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityHandle;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.FailureReason;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.Marshaler;
import javax.slee.resource.ReceivableService;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;

import org.jctools.queues.MpscArrayQueue;
import org.mobicents.ussd.cdr.local.CdrWriteRequest;

public class CdrLocalResourceAdaptor implements ResourceAdaptor {

    public static final String DEFAULT_CDR_FILE_PREFIX = "cdr";
    public static final int DEFAULT_QUEUE_CAPACITY = 65536;
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int DEFAULT_FLUSH_INTERVAL_MS = 100;

    private static final String QUEUE_CAPACITY_CONFIG_PROPERTY = "QUEUE_CAPACITY";
    private static final String BATCH_SIZE_CONFIG_PROPERTY = "BATCH_SIZE";
    private static final String FLUSH_INTERVAL_MS_CONFIG_PROPERTY = "FLUSH_INTERVAL_MS";
    private static final String CDR_LOG_DIR_CONFIG_PROPERTY = "CDR_LOG_DIR";
    private static final String CDR_FILE_PREFIX_CONFIG_PROPERTY = "CDR_FILE_PREFIX";

    private ResourceAdaptorContext context;
    private Tracer tracer;
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    private String cdrLogDir = "";
    private String cdrFilePrefix = DEFAULT_CDR_FILE_PREFIX;

    private final CdrLocalResourceAdaptorSbbInterfaceImpl sbbInterface = new CdrLocalResourceAdaptorSbbInterfaceImpl(this);
    private MpscArrayQueue<String> queue;
    private CdrQueueDiskWriter diskWriter;
    private volatile boolean active;
    private final AtomicLong queueFullSpins = new AtomicLong();

    public Tracer getTracer() {
        return tracer;
    }

    void submit(CdrWriteRequest request) {
        if (request == null) {
            return;
        }
        if (!active || queue == null) {
            tracer.severe("CDR Local RA submit rejected: RA entity not active");
            return;
        }
        String line = request.getLine();
        while (!queue.relaxedOffer(line)) {
            if (!active) {
                tracer.severe("CDR Local RA submit rejected while shutting down, recordId=" + request.getRecordId());
                return;
            }
            long spins = queueFullSpins.incrementAndGet();
            if (spins == 1 || spins % 10000 == 0) {
                tracer.warning("CDR queue full (capacity=" + queueCapacity + "), backpressure active, spins=" + spins);
            }
            Thread.yield();
        }
    }

    private File resolveLogDir() {
        if (cdrLogDir != null && !cdrLogDir.trim().isEmpty()) {
            return new File(cdrLogDir.trim());
        }
        String jbossLogDir = System.getProperty("jboss.server.log.dir");
        if (jbossLogDir != null && !jbossLogDir.isEmpty()) {
            return new File(jbossLogDir);
        }
        return new File(".");
    }

    private static int normalizeQueueCapacity(int capacity) {
        if (capacity < 1024) {
            return 1024;
        }
        int normalized = 1;
        while (normalized < capacity && normalized < (1 << 30)) {
            normalized <<= 1;
        }
        return normalized;
    }

    @Override
    public Object getResourceAdaptorInterface(String className) {
        return sbbInterface;
    }

    @Override
    public void raConfigure(ConfigProperties configuration) {
        this.queueCapacity = (Integer) configuration.getProperty(QUEUE_CAPACITY_CONFIG_PROPERTY).getValue();
        this.batchSize = (Integer) configuration.getProperty(BATCH_SIZE_CONFIG_PROPERTY).getValue();
        this.flushIntervalMs = (Integer) configuration.getProperty(FLUSH_INTERVAL_MS_CONFIG_PROPERTY).getValue();
        Object logDir = configuration.getProperty(CDR_LOG_DIR_CONFIG_PROPERTY).getValue();
        this.cdrLogDir = logDir != null ? logDir.toString() : "";
        Object prefix = configuration.getProperty(CDR_FILE_PREFIX_CONFIG_PROPERTY).getValue();
        this.cdrFilePrefix = prefix != null && !prefix.toString().trim().isEmpty()
                ? prefix.toString().trim()
                : DEFAULT_CDR_FILE_PREFIX;
    }

    @Override
    public void raActive() {
        int capacity = normalizeQueueCapacity(queueCapacity);
        this.queue = new MpscArrayQueue<String>(capacity);
        this.diskWriter = new CdrQueueDiskWriter(queue, tracer, resolveLogDir(), cdrFilePrefix, batchSize, flushIntervalMs);
        this.active = true;
        this.queueFullSpins.set(0);
        diskWriter.start();
        tracer.info("CDR Local RA active, queueCapacity=" + capacity + ", batchSize=" + batchSize
                + ", flushIntervalMs=" + flushIntervalMs + ", logDir=" + resolveLogDir().getAbsolutePath()
                + ", filePrefix=" + cdrFilePrefix);
    }

    @Override
    public void raInactive() {
        active = false;
        if (diskWriter != null) {
            diskWriter.shutdownAndDrain();
            diskWriter = null;
        }
        queue = null;
    }

    @Override
    public void raUnconfigure() {
        queueCapacity = DEFAULT_QUEUE_CAPACITY;
        batchSize = DEFAULT_BATCH_SIZE;
        flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        cdrLogDir = "";
        cdrFilePrefix = DEFAULT_CDR_FILE_PREFIX;
    }

    @Override
    public void raVerifyConfiguration(ConfigProperties properties) throws InvalidConfigurationException {
        Integer capacity = (Integer) properties.getProperty(QUEUE_CAPACITY_CONFIG_PROPERTY).getValue();
        if (capacity == null || capacity < 1024) {
            throw new InvalidConfigurationException("QUEUE_CAPACITY must be at least 1024");
        }
        Integer batch = (Integer) properties.getProperty(BATCH_SIZE_CONFIG_PROPERTY).getValue();
        if (batch == null || batch < 1) {
            throw new InvalidConfigurationException("BATCH_SIZE must be a positive integer");
        }
        Integer flushMs = (Integer) properties.getProperty(FLUSH_INTERVAL_MS_CONFIG_PROPERTY).getValue();
        if (flushMs == null || flushMs < 10) {
            throw new InvalidConfigurationException("FLUSH_INTERVAL_MS must be at least 10");
        }
        Object prefix = properties.getProperty(CDR_FILE_PREFIX_CONFIG_PROPERTY).getValue();
        if (prefix != null && prefix.toString().trim().isEmpty()) {
            throw new InvalidConfigurationException("CDR_FILE_PREFIX must not be blank when set");
        }
    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
        this.tracer = context.getTracer(CdrLocalResourceAdaptor.class.getSimpleName());
    }

    @Override
    public void unsetResourceAdaptorContext() {
        this.context = null;
        this.tracer = null;
    }

    @Override
    public void activityEnded(ActivityHandle activityHandle) {
    }

    @Override
    public void activityUnreferenced(ActivityHandle activityHandle) {
    }

    @Override
    public void administrativeRemove(ActivityHandle activityHandle) {
    }

    @Override
    public void eventProcessingFailed(ActivityHandle activityHandle, FireableEventType eventType, Object eventObject,
            Address address, ReceivableService service, int eventFlags, FailureReason reason) {
    }

    @Override
    public void eventProcessingSuccessful(ActivityHandle activityHandle, FireableEventType eventType,
            Object eventObject, Address address, ReceivableService service, int eventFlags) {
    }

    @Override
    public void eventUnreferenced(ActivityHandle activityHandle, FireableEventType eventType, Object eventObject,
            Address address, ReceivableService service, int eventFlags) {
    }

    @Override
    public Object getActivity(ActivityHandle activityHandle) {
        return null;
    }

    @Override
    public ActivityHandle getActivityHandle(Object activityObject) {
        return null;
    }

    @Override
    public Marshaler getMarshaler() {
        return null;
    }

    @Override
    public void queryLiveness(ActivityHandle activityHandle) {
    }

    @Override
    public void raConfigurationUpdate(ConfigProperties configuration) {
    }

    @Override
    public void raStopping() {
    }

    @Override
    public void serviceActive(ReceivableService service) {
    }

    @Override
    public void serviceInactive(ReceivableService service) {
    }

    @Override
    public void serviceStopping(ReceivableService service) {
    }
}
