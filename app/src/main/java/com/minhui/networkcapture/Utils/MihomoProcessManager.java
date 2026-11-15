package com.minhui.networkcapture.Utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhui.vpn.VPNConstants;
import com.minhui.vpn.utils.DebugLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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

    private static volatile MihomoProcessManager instance;

    private Context appContext;
    private Process process;
    private Thread monitorThread;
    private volatile boolean stopRequested = true;
    private long backoffMs = INITIAL_BACKOFF_MS;

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
            backoffMs = INITIAL_BACKOFF_MS;

            DebugLog.iWithTag(TAG, "mihomo started, pid=%d", pidOf(started));
            startMonitor(started);
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
            }

            DebugLog.eWithTag(TAG, "mihomo exited (code=%d, uptime=%dms), restart in %dms",
                    exitCode, uptime, backoffMs);

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

        String yaml = ""
                + "socks-port: 127.0.0.1:" + SOCKS_PORT + "\n"
                + "external-controller: 127.0.0.1:" + CONTROLLER_PORT + "\n"
                + "mode: rule\n"
                + "allow-lan: false\n"
                + "log-level: warning\n"
                + "\n"
                + "proxy-providers:\n"
                + "  sub:\n"
                + "    type: http\n"
                + "    url: \"" + url + "\"\n"
                + "    interval: 86400\n"
                + "    path: ./proxies/sub.yaml\n"
                + "    health-check:\n"
                + "      enable: true\n"
                + "      url: http://www.gstatic.com/get?\n"
                + "      interval: 300\n"
                + "\n"
                + "proxy-groups:\n"
                + "  - name: \"节点选择\"\n"
                + "    type: select\n"
                + "    use:\n"
                + "      - sub\n"
                + "\n"
                + "rules:\n"
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
