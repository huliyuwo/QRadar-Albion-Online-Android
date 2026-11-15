package com.minhui.vpn.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.minhui.vpn.VPNConstants;

import java.net.InetSocketAddress;

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

    private UpstreamProxyConfig()
    {
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

    private static SharedPreferences preferences(Context context)
    {
        return context.getApplicationContext()
                .getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
    }
}
