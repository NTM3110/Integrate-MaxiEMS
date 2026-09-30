package io.openems.edge.monitoring;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import io.openems.edge.common.jsonapi.JsonApiBuilder;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.jsonapi.ComponentJsonApi;

import static io.openems.edge.common.claim.Monitoring.SINGLETON_COMPONENT_ID;
import static io.openems.edge.common.claim.Monitoring.SINGLETON_SERVICE_PID;

import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.sun.management.OperatingSystemMXBean;


@Designate(ocd = Config.class, factory = false)
@Component(//
        name = SINGLETON_SERVICE_PID, //
        immediate = true, //
        configurationPolicy = ConfigurationPolicy.OPTIONAL, //
        property = { //
                "enabled=true" //
        })

public class MonitoringImpl extends AbstractOpenemsComponent
        implements Monitoring, OpenemsComponent, ComponentJsonApi {
	private final Logger log = LoggerFactory.getLogger(MonitoringImpl.class);
    private final String fw = "V1.0.1";

    private Config config;

    @Reference
    private ConfigurationAdmin cm;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> metricsPollingTask;


    public MonitoringImpl() {
        this(Executors.newSingleThreadScheduledExecutor());
    }

    MonitoringImpl(ScheduledExecutorService executor) {
        super(
                OpenemsComponent.ChannelId.values(),
                Monitoring.ChannelId.values());
        this.executor = Objects.requireNonNull(executor);
    }

    @Activate
    void activate(ComponentContext context, Config config) {
        super.activate(context, SINGLETON_COMPONENT_ID, config.alias(), config.enabled());
        this.config = config;
        this.applyConfig();

        if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
            return;
        }
    }

    @Modified
    void modified(ComponentContext context, Config config) {
        super.modified(context, SINGLETON_COMPONENT_ID, config.alias(), config.enabled());
        this.config = config;
        this.applyConfig();
        if (OpenemsComponent.validateSingleton(this.cm, SINGLETON_SERVICE_PID, SINGLETON_COMPONENT_ID)) {
            return;
        }
    }

    private synchronized void applyConfig() {
        if (!this.config.enabled()) {
            this.stopMetricsPolling();
            return;
        }

        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.setMaxicom();

        if (this.metricsPollingTask == null || this.metricsPollingTask.isDone()
                || this.metricsPollingTask.isCancelled()) {
            this.metricsPollingTask = this.executor.scheduleAtFixedRate(() -> {
                try {
                    this.updateSystemMetrics();
                } catch (Exception e) {
                    this.log.error("Failed to update system metrics", e);
                }
            }, 0, 30, TimeUnit.SECONDS);
        }
    }

    private synchronized void stopMetricsPolling() {
        if (this.metricsPollingTask != null) {
            this.metricsPollingTask.cancel(false);
            this.metricsPollingTask = null;
        }
    }

    @Deactivate
    protected void deactivate() {
        this.stopMetricsPolling();
        this.executor.shutdownNow();
        super.deactivate();
    }

    private OperatingSystemMXBean osBean;

    private long prevTotalCpu = 0;
    private long prevIdleCpu = 0;
    private boolean cpuInitialized = false;

    private double getCpuUsagePercent() {
        try (RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r")) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return 0.0;
            }

            String[] parts = line.trim().split("\\s+");

            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;

            long idleAll = idle + iowait;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;

            if (!this.cpuInitialized) {
                this.prevIdleCpu = idleAll;
                this.prevTotalCpu = total;
                this.cpuInitialized = true;
                return 0.0;
            }

            final long totalDiff = total - this.prevTotalCpu;
            final long idleDiff = idleAll - this.prevIdleCpu;

            this.prevTotalCpu = total;
            this.prevIdleCpu = idleAll;

            if (totalDiff <= 0) {
                return 0.0;
            }

            return (double) (totalDiff - idleDiff) * 100.0 / totalDiff;

        } catch (Exception e) {
            this.log.warn("Failed to read CPU usage", e);
            return 0.0;
        }
    }

    private long memAvailableKb = 0;
    private long memTotalKb = 0;

    private void updateRamMetricsFromProc() {
        try (RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r")) {
            String line;
            int found = 0;
            while ((line = reader.readLine()) != null && found < 2) {
                if (line.startsWith("MemTotal:")) {
                    this.memTotalKb = Long.parseLong(line.replaceAll("\\D+", ""));
                    found++;
                } else if (line.startsWith("MemAvailable:")) {
                    // MemAvailable là con số chính xác nhất trên Linux hiện đại
                    this.memAvailableKb = Long.parseLong(line.replaceAll("\\D+", ""));
                    found++;
                }
            }
        } catch (Exception e) {
            this.log.warn("Failed to read /proc/meminfo, falling back to OS Bean", e);
            // Fallback nếu không đọc được file (dành cho các OS khác)
            this.memTotalKb = this.osBean.getTotalPhysicalMemorySize() / 1024;
            this.memAvailableKb = this.osBean.getFreePhysicalMemorySize() / 1024;
        }
    }

    private long getTotalMemoryMb() {
        return this.memTotalKb / 1024;
    }

    private long getUsedMemoryMb() {
        // RAM thực sự dùng = Tổng - Khả dụng
        return (this.memTotalKb - this.memAvailableKb) / 1024;
    }

    private double getRamUsagePercent() {
        if (this.memTotalKb <= 0) {
            return 0.0;
        }
        return (double) (this.memTotalKb - this.memAvailableKb) * 100.0 / this.memTotalKb;
    }

    private double getDiskUsagePercent(String mountPath) {
        try {
            FileStore store = Files.getFileStore(Paths.get(mountPath));

            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            long used = total - usable;

            if (total <= 0) {
                return 0.0;
            }

            return (double) used * 100.0 / total;
        } catch (Exception e) {
            this.log.warn("Failed to read disk usage for " + mountPath, e);
            return 0.0;
        }
    }

    private long getDiskTotalGb(String mountPath) {
        try {
            FileStore store = Files.getFileStore(Paths.get(mountPath));
            return store.getTotalSpace() / 1024 / 1024 / 1024;
        } catch (Exception e) {
            this.log.warn("Failed to read disk total for " + mountPath, e);
            return 0;
        }
    }

    private long getDiskUsedGb(String mountPath) {
        try {
            FileStore store = Files.getFileStore(Paths.get(mountPath));

            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();

            return (total - usable) / 1024 / 1024 / 1024;
        } catch (Exception e) {
            this.log.warn("Failed to read disk used for " + mountPath, e);
            return 0;
        }
    }

    private void updateSystemMetrics() {
        this.updateRamMetricsFromProc();
        double cpuUsage = this.getCpuUsagePercent();
        double ramUsage = this.getRamUsagePercent();
        double diskUsage = this.getDiskUsagePercent("/");

        long ramTotalMb = this.getTotalMemoryMb();
        long ramUsedMb = this.getUsedMemoryMb();

        long diskTotalGb = this.getDiskTotalGb("/");
        long diskUsedGb = this.getDiskUsedGb("/");

        this.log.info("CPU: {} %, RAM: {} % ({} / {} MB), DISK: {} % ({} / {} GB)",
                String.format("%.2f", cpuUsage),
                String.format("%.2f", ramUsage),
                ramUsedMb,
                ramTotalMb,
                String.format("%.2f", diskUsage),
                diskUsedGb,
                diskTotalGb);

         this.channel(Monitoring.ChannelId.CPU_USAGE).setNextValue(cpuUsage);
         this.channel(Monitoring.ChannelId.RAM_USAGE).setNextValue(ramUsage);
         this.channel(Monitoring.ChannelId.DISK_USAGE).setNextValue(diskUsage);
    }

    private void setMaxicom() {
		this.channel(Monitoring.ChannelId.FW).setNextValue(this.fw);
        this.channel(Monitoring.ChannelId.S_N).setNextValue(this.config.SN());
    }

    @Override
    public void buildJsonApiRoutes(JsonApiBuilder builder) {

    }
}
