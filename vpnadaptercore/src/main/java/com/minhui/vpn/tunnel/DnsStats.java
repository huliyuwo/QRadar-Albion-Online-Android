package com.minhui.vpn.tunnel;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DNS 诊断计数。
 *
 * <p>游戏报 "Could not resolve host" 时，先看这组计数判断 DNS 包有没有进 tun：
 * <ul>
 *   <li>{@code queries}：进入隧道的 53 端口查询数（含 UDP/TCP）</li>
 *   <li>{@code hijacked}：被改投到 223.5.5.5 的次数</li>
 *   <li>{@code failures}：改投失败次数</li>
 * </ul>
 * 计数变化时输出一条限流日志，便于 adb logcat 取证。
 */
public final class DnsStats
{
    private static final String TAG = "VPNLog";
    private static final long LOG_INTERVAL_MS = 3000;

    private static final AtomicLong queries = new AtomicLong();
    private static final AtomicLong hijacked = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static volatile long lastLogTime;

    private DnsStats()
    {
    }

    public static void recordQuery()
    {
        queries.incrementAndGet();
        logThrottled("query");
    }

    public static void recordHijack()
    {
        hijacked.incrementAndGet();
    }

    public static void recordFailure()
    {
        failures.incrementAndGet();
        logThrottled("failure");
    }

    public static String summary()
    {
        return "dns queries=" + queries.get() + " hijacked=" + hijacked.get() + " failed=" + failures.get();
    }

    public static long queryCount()
    {
        return queries.get();
    }

    public static long hijackCount()
    {
        return hijacked.get();
    }

    public static long failureCount()
    {
        return failures.get();
    }

    private static void logThrottled(String reason)
    {
        long now = System.currentTimeMillis();

        if (now - lastLogTime < LOG_INTERVAL_MS)
        {
            return;
        }

        lastLogTime = now;
        Log.d(TAG, "DNSSTATS (" + reason + ") " + summary());
    }
}