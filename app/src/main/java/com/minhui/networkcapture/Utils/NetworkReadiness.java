package com.minhui.networkcapture.Utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPN 就绪自检 / DNS 与 TCP 连通性检查。
 *
 * <p>游戏出现 "Could not resolve host" 或 "无法连接到服务器" 时，需要区分是 DNS 解析失败
 * 还是 TCP 连接失败。本类在 VPN 启动后依次做三件事：
 * <ol>
 *   <li>检查系统「私人 DNS（DoT/DoH）」是否开启（会绕过我们 53 端口的劫持）；</li>
 *   <li>对 live.albiononline.com 做带重试的 DNS 解析；</li>
 *   <li>对 live.albiononline.com:443 做带重试的 TCP 连接，并输出耗时。</li>
 * </ol>
 *
 * <p>注意：本应用的流量被排除在自身 VPN 之外，这里的解析/连接验证的是设备到
 * Albion 接入点的物理链路；隧道侧是否真的收到 DNS 包，看 {@link com.minhui.vpn.tunnel.DnsStats}
 * 的 DNSSTATS 计数。
 */
public final class NetworkReadiness
{
    private static final String TAG = "RadarDiag";
    private static final String PROBE_HOST = "live.albiononline.com";
    private static final int PROBE_TCP_PORT = 443;
    private static final int MAX_ATTEMPTS = 3;
    private static final int TCP_TIMEOUT_MS = 5000;

    private NetworkReadiness()
    {
    }

    /** 在后台线程做自检，结果写入日志，异常情况在主线程 Toast 提示。 */
    public static void checkAsync(Context context)
    {
        if (context == null)
        {
            return;
        }

        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());

        String privateDnsMode = readPrivateDnsMode(app);
        Log.d(TAG, "readiness: privateDnsMode=" + privateDnsMode + " " + describeDnsServers(app));

        if (privateDnsMode != null && !privateDnsMode.isEmpty()
                && !"off".equalsIgnoreCase(privateDnsMode))
        {
            main.post(() -> Toast.makeText(app,
                    "检测到系统已开启「私人 DNS」，可能绕过雷达的 DNS 劫持，建议关闭",
                    Toast.LENGTH_LONG).show());
        }

        new Thread(() -> runChecks(app, main), "radar-network-readiness").start();
    }

    private static void runChecks(Context app, Handler main)
    {
        boolean dnsOk = checkDns(app, main);

        if (dnsOk)
        {
            checkTcp443(app, main);
        }

        // 隧道侧 DNS 计数：为 0 说明 DNS 包还没进 VPN（此时 DNS 自检通过也不代表劫持生效）
        try
        {
            Log.d(TAG, "readiness: tunnel " + com.minhui.vpn.tunnel.DnsStats.summary());
        }
        catch (Throwable ignored)
        {
        }
    }

    private static boolean checkDns(Context app, Handler main)
    {
        long startedAt = System.currentTimeMillis();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            try
            {
                InetAddress[] addresses = InetAddress.getAllByName(PROBE_HOST);

                if (addresses != null && addresses.length > 0)
                {
                    Log.d(TAG, "readiness: dns ok " + (System.currentTimeMillis() - startedAt)
                            + "ms attempt=" + attempt + " ip=" + addresses[0].getHostAddress());
                    return true;
                }
            }
            catch (Throwable e)
            {
                Log.w(TAG, "readiness: dns attempt " + attempt + " failed: " + e);
            }

            sleepBackoff(attempt);
        }

        Log.e(TAG, "readiness: dns failed after " + MAX_ATTEMPTS + " attempts");
        main.post(() -> Toast.makeText(app,
                "域名解析失败：请关闭系统「私人 DNS」/加速器，或稍等几秒后重新进入游戏",
                Toast.LENGTH_LONG).show());
        return false;
    }

    /** TCP 443 连通性检查，用于把「DNS 失败」和「TCP 连不上」区分开。 */
    private static void checkTcp443(Context app, Handler main)
    {
        long startedAt = System.currentTimeMillis();
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            Socket socket = new Socket();

            try
            {
                socket.connect(new InetSocketAddress(PROBE_HOST, PROBE_TCP_PORT), TCP_TIMEOUT_MS);
                Log.d(TAG, "readiness: tcp " + PROBE_TCP_PORT + " ok "
                        + (System.currentTimeMillis() - startedAt) + "ms attempt=" + attempt);
                return;
            }
            catch (Exception e)
            {
                lastError = e;
                Log.w(TAG, "readiness: tcp " + PROBE_TCP_PORT + " attempt " + attempt + " failed: " + e);
            }
            finally
            {
                try
                {
                    socket.close();
                }
                catch (Throwable ignored)
                {
                }
            }

            sleepBackoff(attempt);
        }

        Log.e(TAG, "readiness: tcp " + PROBE_TCP_PORT + " unreachable: " + lastError);
        main.post(() -> Toast.makeText(app,
                "连接游戏服务器失败：请检查代理节点/加速器，或等待 VPN 就绪后重试",
                Toast.LENGTH_LONG).show());
    }

    private static void sleepBackoff(int attempt)
    {
        try
        {
            Thread.sleep(500L * attempt);
        }
        catch (InterruptedException ignored)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** 读取系统「私人 DNS」模式：off / opportunistic / hostname。 */
    public static String readPrivateDnsMode(Context context)
    {
        try
        {
            return Settings.Global.getString(context.getContentResolver(), "private_dns_mode");
        }
        catch (Throwable e)
        {
            Log.w(TAG, "readPrivateDnsMode failed: " + e);
            return null;
        }
    }

    /** 汇总当前网络下发的 DNS 服务器，写日志便于排查劫持是否生效。 */
    public static String describeDnsServers(Context context)
    {
        try
        {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (cm == null)
            {
                return "dns=unknown";
            }

            Network active = cm.getActiveNetwork();
            LinkProperties props = active == null ? null : cm.getLinkProperties(active);

            if (props == null || props.getDnsServers() == null || props.getDnsServers().isEmpty())
            {
                return "dns=none";
            }

            StringBuilder builder = new StringBuilder("dns=");

            for (InetAddress address : props.getDnsServers())
            {
                builder.append(address.getHostAddress()).append(',');
            }

            return builder.toString();
        }
        catch (Throwable e)
        {
            return "dns=error(" + e.getClass().getSimpleName() + ")";
        }
    }
}
