package com.minhui.networkcapture.Utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhui.vpn.VPNConstants;
import com.minhui.vpn.proxy.UpstreamProxyConfig;
import com.minhui.vpn.utils.DebugLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置 mihomo 子进程管理器（路线一）。
 *
 * mihomo 的 Android arm64 可执行文件被伪装成 libmihomo.so 打进 APK 的
 * jniLibs/arm64-v8a/，安装时（extractNativeLibs/useLegacyPackaging）解压到
 * nativeLibraryDir——这是 Android 10+ 唯一允许应用执行的自身文件位置。
 *
 * 职责：
 * - 依据用户填入的机场订阅链接生成 filesDir/mihomo/config.yaml
 *   （SOCKS5 127.0.0.1:7897 给雷达转发，REST API 127.0.0.1:9090 给节点切换）
 * - 以子进程方式启动/停止 mihomo
 * - 崩溃自动拉起：监视线程 waitFor 退出码，非主动停止时按指数退避重启
 *   （1s 起步，翻倍封顶 30s；存活满 60s 视为稳定并重置退避）
 * - 孤儿进程清理：App 被系统强杀时 stop() 不会执行，mihomo 会残留并占住
 *   7897/9090 端口；启动时读取上次记录的 pid 文件并 killProcess（同 UID 可杀）
 */
public class MihomoProcessManager
{
    public static final String KEY_SUBSCRIPTION_URL = "mihomo_subscription_url";

    private static final String TAG = "MihomoProc";
    private static final String BINARY_NAME = "libmihomo.so";
    private static final String CONFIG_DIR_NAME = "mihomo";
    private static final String CONFIG_FILE_NAME = "config.yaml";
    private static final String LOG_FILE_NAME = "mihomo.log";
    private static final String PID_FILE_NAME = "mihomo.pid";

    private static final int SOCKS_PORT = 7897;
    private static final int CONTROLLER_PORT = 9090;

    private static final long STABLE_UPTIME_MS = 60_000L;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final int MAX_FAST_FAILS = 5;

    private static volatile MihomoProcessManager instance;

    private Context appContext;
    private Process process;
    private Thread monitorThread;
    private volatile boolean stopRequested = true;
    private long backoffMs = INITIAL_BACKOFF_MS;
    private int fastFails = 0;

    private MihomoProcessManager()
    {
    }

    public static MihomoProcessManager getInstance()
    {
        if (instance == null)
        {
            synchronized (MihomoProcessManager.class)
            {
                if (instance == null)
                {
                    instance = new MihomoProcessManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context)
    {
        appContext = context.getApplicationContext();

        // 上次是"代理开"状态时，App 重启后要把 mihomo 自动拉起来，
        // 否则开关还是开、7897 却没人监听，所有 TCP 都连不上。
        if (preferences().getBoolean(UpstreamProxyConfig.KEY_ENABLED, false) && isConfigured())
        {
            startAsync();
        }
    }

    // ---------- 订阅链接 ----------

    public String getSubscriptionUrl()
    {
        SharedPreferences sp = preferences();
        return sp.getString(KEY_SUBSCRIPTION_URL, "");
    }

    public void saveSubscriptionUrl(String url)
    {
        preferences().edit()
                .putString(KEY_SUBSCRIPTION_URL, url == null ? "" : url.trim())
                .apply();
    }

    public boolean isConfigured()
    {
        return !getSubscriptionUrl().isEmpty();
    }

    public boolean isBinaryAvailable()
    {
        return appContext != null && binaryFile().isFile();
    }

    // ---------- 进程生命周期 ----------

    public synchronized boolean isRunning()
    {
        return process != null && isAlive(process);
    }

    /** 后台线程启动，避免阻塞 UI（孤儿清理最多等待 3s）。 */
    public void startAsync()
    {
        Thread t = new Thread(this::start, "mihomo-start");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void start()
    {
        if (appContext == null)
        {
            DebugLog.eWithTag(TAG, "%s", "not initialized");
            return;
        }
        if (isRunning())
        {
            return;
        }
        if (!isConfigured())
        {
            DebugLog.iWithTag(TAG, "%s", "subscription url not configured, skip start");
            return;
        }
        if (!isBinaryAvailable())
        {
            DebugLog.eWithTag(TAG, "%s", "mihomo binary not available on this device");
            return;
        }

        try
        {
            killOrphanProcess();

            File configDir = new File(appContext.getFilesDir(), CONFIG_DIR_NAME);
            if (!configDir.isDirectory() && !configDir.mkdirs())
            {
                DebugLog.eWithTag(TAG, "%s", "cannot create config dir");
                return;
            }
            new File(configDir, "proxies").mkdirs();
            writeConfig(configDir);

            File logFile = new File(configDir, LOG_FILE_NAME);

            ProcessBuilder builder = new ProcessBuilder(
                    binaryFile().getAbsolutePath(),
                    "-d", configDir.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));

            stopRequested = false;
            Process started = builder.start();
            process = started;
            writePidFile(configDir, pidOf(started));

            DebugLog.iWithTag(TAG, "mihomo started, pid=%d", pidOf(started));
            startMonitor(started);
            scheduleDirectEndpointRefresh(configDir);
        }
        catch (IOException e)
        {
            stopRequested = true;
            DebugLog.eWithTag(TAG, "%s", "failed to start mihomo: " + e.getMessage());
        }
    }

    public void stopAsync()
    {
        Thread t = new Thread(this::stop, "mihomo-stop");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop()
    {
        stopRequested = true;
        backoffMs = INITIAL_BACKOFF_MS;
        fastFails = 0;

        Thread monitor = monitorThread;
        monitorThread = null;
        if (monitor != null)
        {
            monitor.interrupt();
        }

        Process p = process;
        process = null;
        if (p != null)
        {
            p.destroy();
            try
            {
                if (!p.waitFor(3, TimeUnit.SECONDS))
                {
                    p.destroyForcibly();
                }
            }
            catch (InterruptedException e)
            {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            DebugLog.iWithTag(TAG, "%s", "mihomo stopped");
        }

        if (appContext != null)
        {
            File pidFile = new File(new File(appContext.getFilesDir(), CONFIG_DIR_NAME), PID_FILE_NAME);
            //noinspection ResultOfMethodCallIgnored
            pidFile.delete();
        }
    }

    /** 保存订阅后调用：先停再启，让新配置生效。 */
    public void restartAsync()
    {
        Thread t = new Thread(() ->
        {
            stop();
            start();
        }, "mihomo-restart");
        t.setDaemon(true);
        t.start();
    }

    // ---------- 直连端点集合 ----------

    private static final Pattern SERVER_PATTERN = Pattern.compile("\\bserver:\\s*\"?([^\",}\\s]+)\"?");
    private static final Pattern PORT_PATTERN = Pattern.compile("\\bport:\\s*(\\d+)");

    /**
     * 雷达 UID 在 VPN 白名单内后，mihomo 连节点/拉订阅的出口流量也会被吸入隧道。
     * 若按代理开关再转回 mihomo 会形成递归环，故把这些目的端点加入强制直连集合。
     * sub.yaml 由 mihomo 启动后下载，首轮可能尚不存在，做多次延迟刷新。
     */
    private void scheduleDirectEndpointRefresh(File configDir)
    {
        Thread t = new Thread(() ->
        {
            refreshDirectEndpoints(configDir);

            for (long delayMs : new long[]{5_000L, 20_000L})
            {
                try
                {
                    Thread.sleep(delayMs);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                refreshDirectEndpoints(configDir);
            }

            while (isRunning())
            {
                try
                {
                    Thread.sleep(300_000L);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                refreshDirectEndpoints(configDir);
            }
        }, "mihomo-direct-endpoints");
        t.setDaemon(true);
        t.start();
    }

    private void refreshDirectEndpoints(File configDir)
    {
        Set<String> endpoints = new HashSet<>();

        // 订阅 URL 端点（mihomo 自己下载订阅用，走 DIRECT 出口）
        try
        {
            URL url = new URL(getSubscriptionUrl());
            int urlPort = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();

            for (InetAddress addr : InetAddress.getAllByName(url.getHost()))
            {
                endpoints.add(addr.getHostAddress() + ":" + urlPort);
            }
        }
        catch (Exception e)
        {
            DebugLog.wWithLog(TAG, "%s", "cannot resolve subscription host: " + e.getMessage());
        }

        // 全部订阅节点端点（含未选中的，切节点后集合依然有效）
        File subFile = new File(new File(configDir, "proxies"), "sub.yaml");
        String pendingServer = null;
        int pendingPort = -1;

        try (BufferedReader reader = new BufferedReader(new FileReader(subFile)))
        {
            String line;

            while ((line = reader.readLine()) != null)
            {
                if (line.trim().startsWith("- "))
                {
                    pendingServer = null;
                    pendingPort = -1;
                }

                Matcher ms = SERVER_PATTERN.matcher(line);
                if (ms.find())
                {
                    pendingServer = ms.group(1);
                }

                Matcher mp = PORT_PATTERN.matcher(line);
                if (mp.find())
                {
                    pendingPort = Integer.parseInt(mp.group(1));
                }

                if (pendingServer != null && pendingPort > 0)
                {
                    addEndpoint(endpoints, pendingServer, pendingPort);
                    pendingServer = null;
                    pendingPort = -1;
                }
            }
        }
        catch (IOException e)
        {
            // 首次启动 sub.yaml 还没下载下来，属正常情况
            DebugLog.iWithTag(TAG, "sub.yaml not readable yet: %s", e.getMessage());
        }

        UpstreamProxyConfig.replaceDirectEndpoints(endpoints);
        DebugLog.iWithTag(TAG, "direct endpoints refreshed, count=%d", endpoints.size());
    }

    private void addEndpoint(Set<String> endpoints, String server, int port)
    {
        try
        {
            for (InetAddress addr : InetAddress.getAllByName(server))
            {
                endpoints.add(addr.getHostAddress() + ":" + port);
            }
        }
        catch (Exception e)
        {
            DebugLog.wWithLog(TAG, "cannot resolve node server %s: %s", server, e.getMessage());
        }
    }

    // ---------- 崩溃拉起 ----------

    private void startMonitor(Process watched)
    {
        Thread t = new Thread(() ->
        {
            long startedAt = System.currentTimeMillis();
            int exitCode;
            try
            {
                exitCode = watched.waitFor();
            }
            catch (InterruptedException e)
            {
                return; // stop() 主动打断
            }

            synchronized (MihomoProcessManager.this)
            {
                if (process == watched)
                {
                    process = null;
                }
                if (stopRequested)
                {
                    return;
                }
            }

            long uptime = System.currentTimeMillis() - startedAt;
            if (uptime >= STABLE_UPTIME_MS)
            {
                backoffMs = INITIAL_BACKOFF_MS; // 稳定运行过，重置退避
                fastFails = 0;
            }
            else if (uptime < 1_000L)
            {
                fastFails++;
            }

            DebugLog.eWithTag(TAG, "mihomo exited (code=%d, uptime=%dms), restart in %dms",
                    exitCode, uptime, backoffMs);

            // 连续秒退通常是配置错误（如订阅解析失败），无限重启只会空耗电量
            if (fastFails >= MAX_FAST_FAILS)
            {
                DebugLog.eWithTag(TAG, "%s",
                        "mihomo exited immediately " + MAX_FAST_FAILS + " times in a row, giving up auto-restart (check config/subscription)");

                // 进程起不来时把代理开关置回“关”，否则新连接会一直去连一个不存在的 7897 端口
                if (appContext != null)
                {
                    UpstreamProxyConfig.save(appContext, false,
                            UpstreamProxyConfig.getHost(), UpstreamProxyConfig.getPort());
                }

                return;
            }

            try
            {
                Thread.sleep(backoffMs);
            }
            catch (InterruptedException e)
            {
                return;
            }

            synchronized (MihomoProcessManager.this)
            {
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                if (stopRequested)
                {
                    return;
                }
            }

            start();
        }, "mihomo-monitor");
        t.setDaemon(true);
        monitorThread = t;
        t.start();
    }

    // ---------- 孤儿进程清理 ----------

    private void killOrphanProcess()
    {
        File pidFile = new File(new File(appContext.getFilesDir(), CONFIG_DIR_NAME), PID_FILE_NAME);
        if (!pidFile.isFile())
        {
            return;
        }

        int pid = -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(pidFile)))
        {
            String line = reader.readLine();
            if (line != null)
            {
                pid = Integer.parseInt(line.trim());
            }
        }
        catch (IOException | NumberFormatException e)
        {
            DebugLog.wWithLog(TAG, "%s", "bad pid file: " + e.getMessage());
        }

        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();

        if (pid > 0 && pid != android.os.Process.myPid())
        {
            // 上次 App 被强杀，mihomo 仍占着 7897/9090；同 UID 进程可以直接杀
            try
            {
                android.os.Process.killProcess(pid);
                DebugLog.iWithTag(TAG, "killed orphan mihomo, pid=%d", pid);
                Thread.sleep(300); // 等端口释放
            }
            catch (Exception e)
            {
                DebugLog.wWithLog(TAG, "%s", "cannot kill orphan: " + e.getMessage());
            }
        }
    }

    // ---------- 配置生成 ----------

    private void writeConfig(File configDir) throws IOException
    {
        String url = getSubscriptionUrl()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");

        String subscriptionHost = "";

        try
        {
            String host = java.net.URI.create(getSubscriptionUrl()).getHost();
            subscriptionHost = host == null ? "" : host;
        }
        catch (Exception ignored)
        {
        }

        String yaml = ""
                + "socks-port: " + SOCKS_PORT + "\n"
                + "external-controller: 127.0.0.1:" + CONTROLLER_PORT + "\n"
                + "bind-address: local\n"
                + "mode: rule\n"
                + "allow-lan: false\n"
                + "log-level: debug\n"
                + "\n"
                // mihomo 自己的 DNS 不能依赖 8.8.8.8（国内被墙，订阅/节点解析会超时）
                + "dns:\n"
                + "  enable: true\n"
                + "  ipv6: false\n"
                + "  nameserver:\n"
                + "    - 223.5.5.5\n"
                + "    - 119.29.29.29\n"
                + "\n"
                + "proxy-providers:\n"
                + "  sub:\n"
                + "    type: http\n"
                + "    url: \"" + url + "\"\n"
                // 关键：拉订阅必须直连。否则 mihomo 会按 rules 把订阅请求塞进「节点选择」组，
                // 而组里此时还没节点，形成“没节点→拉不到订阅→更没节点”的死锁（日志里表现为
                // sub pull error: context deadline exceeded，节点列表只剩 1 个）。
                + "    proxy: DIRECT\n"
                + "    interval: 86400\n"
                + "    path: ./proxies/sub.yaml\n"
                // 订阅节点普遍不声明 udp，mihomo 默认不中继 UDP，会导致游戏 DNS 与 Photon(UDP 5056) 全断
                + "    override:\n"
                + "      udp: true\n"
                + "    health-check:\n"
                + "      enable: true\n"
                + "      url: http://www.gstatic.com/generate_204\n"
                + "      interval: 300\n"
                + "\n"
                + "proxy-groups:\n"
                + "  - name: \"节点选择\"\n"
                + "    type: select\n"
                + "    use:\n"
                + "      - sub\n"
                + "\n"
                + "rules:\n"
                + (subscriptionHost.isEmpty() ? "" : "  - DOMAIN-SUFFIX," + subscriptionHost + ",DIRECT\n")
                + "  - MATCH,节点选择\n";

        File configFile = new File(configDir, CONFIG_FILE_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(configFile), StandardCharsets.UTF_8))
        {
            writer.write(yaml);
        }
    }

    // ---------- 工具 ----------

    private File binaryFile()
    {
        return new File(appContext.getApplicationInfo().nativeLibraryDir, BINARY_NAME);
    }

    private SharedPreferences preferences()
    {
        if (appContext == null)
        {
            throw new IllegalStateException("MihomoProcessManager not initialized");
        }
        return appContext.getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isAlive(Process p)
    {
        try
        {
            p.exitValue();
            return false;
        }
        catch (IllegalThreadStateException e)
        {
            return true;
        }
    }

    private void writePidFile(File configDir, int pid)
    {
        if (pid <= 0)
        {
            return;
        }
        File pidFile = new File(configDir, PID_FILE_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(pidFile), StandardCharsets.UTF_8))
        {
            writer.write(String.valueOf(pid));
        }
        catch (IOException e)
        {
            DebugLog.wWithLog(TAG, "%s", "cannot write pid file: " + e.getMessage());
        }
    }

    /** Android 的 java.lang.Process 没有公开的 pid()，反射 ProcessImpl 的 pid 字段。 */
    private static int pidOf(Process p)
    {
        if (p == null)
        {
            return -1;
        }
        try
        {
            Field pidField = p.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(p);
        }
        catch (Exception e)
        {
            return -1;
        }
    }
}
