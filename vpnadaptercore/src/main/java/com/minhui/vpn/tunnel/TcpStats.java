package com.minhui.vpn.tunnel;

import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 连接诊断计数（TAG = VPNLog，便于 {@code adb logcat -s VPNLog:D} 取证）。
 *
 * <p>排查"有时候连不上服务器"时要能回答：连接目标是谁、直连还是走 SOCKS5、
 * 建立耗时多少、失败原因是什么、失败后有没有尽快让客户端重试。
 * 成功/汇总日志按 3s 限流，失败日志逐条打印但每秒最多 20 条，避免刷屏。
 */
public final class TcpStats
{
    private static final String TAG = "VPNLog";
    private static final long SUMMARY_INTERVAL_MS = 3000;
    private static final long DETAIL_WINDOW_MS = 1000;
    private static final int DETAIL_LIMIT_PER_WINDOW = 20;

    private static final AtomicLong accepted = new AtomicLong();
    private static final AtomicLong direct = new AtomicLong();
    private static final AtomicLong socks = new AtomicLong();
    private static final AtomicLong connected = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();
    private static final AtomicLong timeouts = new AtomicLong();

    private static volatile long lastSummaryLogTime;
    private static final AtomicLong detailWindowStart = new AtomicLong();
    private static final AtomicInteger detailCount = new AtomicInteger();

    private TcpStats()
    {
    }

    public static void recordAccepted(InetSocketAddress target, boolean viaProxy)
    {
        accepted.incrementAndGet();

        if (viaProxy)
        {
            socks.incrementAndGet();
        }
        else
        {
            direct.incrementAndGet();
        }

        if (detailBudget())
        {
            Log.d(TAG, "TCPCONN accept target=" + format(target) + " mode=" + (viaProxy ? "socks5" : "direct"));
        }

        maybeLogSummary();
    }

    public static void recordConnected(InetSocketAddress target, long elapsedMs, boolean viaProxy)
    {
        connected.incrementAndGet();

        if (detailBudget())
        {
            Log.d(TAG, "TCPCONN ok target=" + format(target) + " mode=" + (viaProxy ? "socks5" : "direct")
                    + " elapsed=" + elapsedMs + "ms");
        }

        maybeLogSummary();
    }

    /** 连接失败：始终打印（限速），并带上目标、模式、耗时、异常类型/信息。 */
    public static void recordFailure(InetSocketAddress target, long elapsedMs, boolean viaProxy, Throwable error)
    {
        failed.incrementAndGet();

        if (detailBudget())
        {
            Log.w(TAG, "TCPCONN fail target=" + format(target) + " mode=" + (viaProxy ? "socks5" : "direct")
                    + " elapsed=" + elapsedMs + "ms err=" + describe(error));
        }

        maybeLogSummary();
    }

    /** 连接超时：兜底定时器触发，调用方随后会 dispose -> 客户端收到 FIN/RST 立即重试。 */
    public static void recordTimeout(InetSocketAddress target, long elapsedMs, boolean viaProxy)
    {
        timeouts.incrementAndGet();

        if (detailBudget())
        {
            Log.w(TAG, "TCPCONN timeout target=" + format(target) + " mode=" + (viaProxy ? "socks5" : "direct")
                    + " elapsed=" + elapsedMs + "ms action=dispose-fast-fail");
        }

        maybeLogSummary();
    }

    public static String summary()
    {
        return "tcp accepted=" + accepted.get() + " direct=" + direct.get() + " socks=" + socks.get()
                + " ok=" + connected.get() + " fail=" + failed.get() + " timeout=" + timeouts.get();
    }

    public static long timeoutCount()
    {
        return timeouts.get();
    }

    public static long failureCount()
    {
        return failed.get();
    }

    public static long connectedCount()
    {
        return connected.get();
    }

    private static boolean detailBudget()
    {
        long now = System.currentTimeMillis();
        long windowStart = detailWindowStart.get();

        if (now - windowStart >= DETAIL_WINDOW_MS)
        {
            if (detailWindowStart.compareAndSet(windowStart, now))
            {
                detailCount.set(0);
            }
        }

        return detailCount.incrementAndGet() <= DETAIL_LIMIT_PER_WINDOW;
    }

    private static void maybeLogSummary()
    {
        long now = System.currentTimeMillis();

        if (now - lastSummaryLogTime < SUMMARY_INTERVAL_MS)
        {
            return;
        }

        lastSummaryLogTime = now;
        Log.d(TAG, "TCPSTATS " + summary());
    }

    private static String describe(Throwable error)
    {
        if (error == null)
        {
            return "unknown";
        }

        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : "(" + message + ")");
    }

    private static String format(InetSocketAddress address)
    {
        if (address == null)
        {
            return "null";
        }

        InetAddress inetAddress = address.getAddress();

        if (inetAddress == null)
        {
            return address.getHostString() + ":" + address.getPort();
        }

        return inetAddress.getHostAddress() + ":" + address.getPort();
    }
}
