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

import java.net.Inet4Address;
import java.net.Inet6Address;
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
 *
 * <p>另外提供 {@link #checkTunnelAfterReadyAsync}：VPN 隧道真正建立后再探一次，
 * 这次探测会经过 VPN，用于验证"VPN 内 IPv6 不可达、只下发 A 才能稳定连上"这一结论。
 */
public final class NetworkReadiness
{
    private static final String TAG = "RadarDiag";
    private static final String PROBE_HOST = "live.albiononline.com";
    private static final int PROBE_TCP_PORT = 443;
    private static final int MAX_ATTEMPTS = 3;
    private static final int TCP_TIMEOUT_MS = 5000;
    private static final int IPV6_TIMEOUT_MS = 3000;
    /** live.albiononline.com 的 Gcore CDN AAAA 地址（用来验证 VPN 内 IPv6 是否可用）。 */
    private static final String PROBE_IPV6_LITERAL = "2a03:90c0:9995::9995";
    /** live.albiononline.com 的 Gcore CDN A 地址（用来验证 VPN 内 IPv4 是否可用）。 */
    private static final String PROBE_IPV4_LITERAL = "92.223.95.95";

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

    /**
     * 隧道建立后再探一次（延迟执行，避开 onVpnStart 回调早于 tun 建立的时间窗）。
     *
     * <p>这次 DNS/连接会真正经过 VPN，用于取证：
     * <ul>
     *   <li>DNS 是否还返回 AAAA（修复后应为 0）；</li>
     *   <li>VPN 内 IPv6 TCP 连接是否失败（本机 IPv6 在 VPN 内没有可用路由）；</li>
     *   <li>IPv4 TCP 是否正常。</li>
     * </ul>
     */
    public static void checkTunnelAfterReadyAsync(Context context, long delayMs)
    {
        if (context == null)
        {
            return;
        }

        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());

        main.postDelayed(() ->
                new Thread(() -> runTunnelProbe(app), "radar-tunnel-probe").start(), Math.max(0, delayMs));
    }

    private static void runTunnelProbe(Context app)
    {
        if (!com.minhui.vpn.utils.VpnServiceHelper.vpnRunningStatus())
        {
            Log.d(TAG, "readiness2: vpn not running, skip tunnel probe");
            return;
        }

        long startedAt = System.currentTimeMillis();
        InetAddress ipv4 = null;
        InetAddress ipv6 = null;
        int ipv4Count = 0;
        int ipv6Count = 0;

        try
        {
            InetAddress[] addresses = InetAddress.getAllByName(PROBE_HOST);

            for (InetAddress address : addresses)
            {
                if (address instanceof Inet4Address)
                {
                    ipv4Count++;

                    if (ipv4 == null)
                    {
                        ipv4 = address;
                    }
                }
                else if (address instanceof Inet6Address)
                {
                    ipv6Count++;

                    if (ipv6 == null)
                    {
                        ipv6 = address;
                    }
                }
            }

            Log.d(TAG, "readiness2: dns " + PROBE_HOST + " total=" + addresses.length
                    + " v4=" + ipv4Count + " v6=" + ipv6Count
                    + " took=" + (System.currentTimeMillis() - startedAt) + "ms");
        }
        catch (Throwable e)
        {
            Log.w(TAG, "readiness2: dns failed: " + e);
        }

        // VPN 内 IPv6 探测：修复前这里通常失败（No route to host / Network unreachable），
        // 正好说明"客户端拿到 AAAA 就会偶发连不上"。
        if (ipv6 != null)
        {
            testTcpConnect("readiness2-v6", ipv6, IPV6_TIMEOUT_MS);
        }
        else
        {
            Log.d(TAG, "readiness2: no AAAA returned, skip ipv6 tcp probe (expected after AAAA filter)");
        }

        if (ipv4 != null)
        {
            testTcpConnect("readiness2-v4", ipv4, TCP_TIMEOUT_MS);
        }

        // 不依赖 DNS 的固定地址探测：证明 VPN 内 IPv6 不可路由、IPv4 通路正常，
        // 这样即使 DNS 已过滤 AAAA，也能留下"为什么必须过滤"的直接证据。
        try
        {
            InetAddress v6Literal = InetAddress.getByName(PROBE_IPV6_LITERAL);
            testTcpConnect("readiness2-v6-literal", v6Literal, IPV6_TIMEOUT_MS);
        }
        catch (Throwable e)
        {
            Log.w(TAG, "readiness2: v6 literal parse failed: " + e);
        }

        try
        {
            InetAddress v4Literal = InetAddress.getByName(PROBE_IPV4_LITERAL);
            testTcpConnect("readiness2-v4-literal", v4Literal, TCP_TIMEOUT_MS);
        }
        catch (Throwable e)
        {
            Log.w(TAG, "readiness2: v4 literal parse failed: " + e);
        }

        try
        {
            Log.d(TAG, "readiness2: summary " + com.minhui.vpn.tunnel.DnsStats.summary()
                    + " | " + com.minhui.vpn.tunnel.TcpStats.summary());
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void testTcpConnect(String label, InetAddress address, int timeoutMs)
    {
        Socket socket = new Socket();
        long startedAt = System.currentTimeMillis();

        try
        {
            socket.connect(new InetSocketAddress(address, PROBE_TCP_PORT), timeoutMs);
            Log.d(TAG, label + " tcp " + PROBE_TCP_PORT + " ok addr=" + address.getHostAddress()
                    + " took=" + (System.currentTimeMillis() - startedAt) + "ms");
        }
        catch (Throwable e)
        {
            Log.w(TAG, label + " tcp " + PROBE_TCP_PORT + " failed addr=" + address.getHostAddress()
                    + " took=" + (System.currentTimeMillis() - startedAt) + "ms err=" + e);
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
