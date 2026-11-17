package com.minhui.vpn.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhui.vpn.VPNConstants;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游代理（SOCKS5）配置。
 *
 * 方案2：本机另一个翻墙核心（如 Termux 中的 mihomo）以"纯本地代理"模式运行，
 * 不占用 VpnService；本 VPN 作为唯一 VpnService，把流量经 SOCKS5 转交给它。
 * 开关与地址存 SharedPreferences，静态字段供转发链路热读取。
 */
public class UpstreamProxyConfig
{
    public static final String KEY_ENABLED = "upstream_proxy_enabled";
    public static final String KEY_HOST = "upstream_proxy_host";
    public static final String KEY_PORT = "upstream_proxy_port";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7897;

    private static volatile boolean enabled = false;
    private static volatile String host = DEFAULT_HOST;
    private static volatile int port = DEFAULT_PORT;

    // 强制直连端点（"ip:port"）。雷达 UID 在 VPN 白名单内后，mihomo 自身连节点/拉订阅的
    // 出口流量也会被吸入隧道；若再按开关转回 mihomo 会形成递归环，命中此集合的目的地址一律直连。
    private static final Set<String> directEndpoints = ConcurrentHashMap.newKeySet();

    /**
     * 运行期"该 IP 一律直连"的集合。
     * 场景：机场节点不支持 UDP 时，游戏 UDP 只能回退直连；若此时 TCP 还走机场，
     * 就会出现"登录 IP 和游戏 IP 不一致"，服务器直接拒绝。这里把同一 IP 也钉死直连。
     */
    private static final Set<String> runtimeDirectHosts = ConcurrentHashMap.newKeySet();

    private UpstreamProxyConfig()
    {
    }

    /** 把一个主机 IP 标记为"始终直连"（UDP 中继无回包时调用）。 */
    public static void markDirectHost(InetAddress address)
    {
        if (address != null)
        {
            runtimeDirectHosts.add(address.getHostAddress());
        }
    }

    public static void clearRuntimeDirectHosts()
    {
        runtimeDirectHosts.clear();
    }

    public static void load(Context context)
    {
        SharedPreferences sp = preferences(context);
        enabled = sp.getBoolean(KEY_ENABLED, false);
        host = sp.getString(KEY_HOST, DEFAULT_HOST);
        port = sp.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void save(Context context, boolean enabledValue, String hostValue, int portValue)
    {
        preferences(context).edit()
                .putBoolean(KEY_ENABLED, enabledValue)
                .putString(KEY_HOST, hostValue)
                .putInt(KEY_PORT, portValue)
                .apply();

        enabled = enabledValue;
        host = hostValue;
        port = portValue;
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    public static String getHost()
    {
        return host;
    }

    public static int getPort()
    {
        return port;
    }

    public static InetSocketAddress endpoint()
    {
        return new InetSocketAddress(host, port);
    }

    /** 整体替换直连端点集合（应用层解析订阅/节点信息后调用）。 */
    public static void replaceDirectEndpoints(Set<String> endpoints)
    {
        directEndpoints.clear();
        directEndpoints.addAll(endpoints);
    }

    public static boolean isDirectEndpoint(InetSocketAddress dest)
    {
        if (dest == null || dest.getAddress() == null)
        {
            return false;
        }

        String hostAddress = dest.getAddress().getHostAddress();

        return runtimeDirectHosts.contains(hostAddress)
                || directEndpoints.contains(hostAddress + ":" + dest.getPort());
    }

    private static SharedPreferences preferences(Context context)
    {
        return context.getApplicationContext()
                .getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
    }
}
