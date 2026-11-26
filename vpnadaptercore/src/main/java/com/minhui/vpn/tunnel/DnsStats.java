package com.minhui.vpn.tunnel;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DNS 诊断计数。
 *
 * <p>游戏报 "Could not resolve host" / 偶发连不上服务器时，先看这组计数判断 DNS 是否健康：
 * <ul>
 *   <li>{@code queries}：进入隧道的 53 端口查询数（含 UDP/TCP）</li>
 *   <li>{@code a} / {@code aaaa} / {@code other}：按 QTYPE 分类的查询数；
 *       AAAA 查询会被本地回 NOERROR/NODATA（本机 IPv6 在 VPN 内不可路由）</li>
 *   <li>{@code hijacked}：被改投到 223.5.5.5 的次数</li>
 *   <li>{@code failed}：改投失败次数</li>
 *   <li>{@code aaaaDropped}：被本地拦截、没再转发的 AAAA 查询数</li>
 *   <li>{@code aaaaStripped}：上游应答里被剥掉的 AAAA 记录数</li>
 *   <li>{@code retried}：上游无应答时的重发次数</li>
 *   <li>{@code servfail}：上游无应答/隧道未就绪时本地回 SERVFAIL 的次数</li>
 * </ul>
 * 汇总日志按 3s 限流；单条查询/应答日志按 1s 窗口限流（每秒最多 20 条）。
 */
public final class DnsStats
{
    private static final String TAG = "VPNLog";
    private static final long LOG_INTERVAL_MS = 3000;
    private static final long DETAIL_WINDOW_MS = 1000;
    private static final int DETAIL_LIMIT_PER_WINDOW = 20;

    private static final AtomicLong queries = new AtomicLong();
    private static final AtomicLong aQueries = new AtomicLong();
    private static final AtomicLong aaaaQueries = new AtomicLong();
    private static final AtomicLong otherQueries = new AtomicLong();
    private static final AtomicLong hijacked = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong aaaaDropped = new AtomicLong();
    private static final AtomicLong aaaaStripped = new AtomicLong();
    private static final AtomicLong retried = new AtomicLong();
    private static final AtomicLong servfail = new AtomicLong();

    private static volatile long lastLogTime;
    private static final AtomicLong detailWindowStart = new AtomicLong();
    private static final AtomicInteger detailCount = new AtomicInteger();

    private DnsStats()
    {
    }

    /** 兼容旧调用：只记一次查询，不区分 QTYPE。 */
    public static void recordQuery()
    {
        queries.incrementAndGet();
        otherQueries.incrementAndGet();
        logThrottled("query");
    }

    /** 记一次查询并输出（限流的）明细日志，便于取证"游戏到底查了什么、有没有 AAAA"。 */
    public static void recordQuery(int qtype, String name)
    {
        queries.incrementAndGet();

        if (qtype == DnsMessage.TYPE_A)
        {
            aQueries.incrementAndGet();
        }
        else if (qtype == DnsMessage.TYPE_AAAA)
        {
            aaaaQueries.incrementAndGet();
        }
        else
        {
            otherQueries.incrementAndGet();
        }

        if (detailBudget())
        {
            Log.d(TAG, "DNSQ name=" + safeName(name) + " type=" + DnsMessage.typeName(qtype)
                    + " | " + summary());
        }

        logThrottled("query");
    }

    /** 记一次应答摘要（A/AAAA 记录数、rcode），用于证明"AAAA 是否真的没再下发给游戏"。 */
    public static void recordResponseDetail(String detail)
    {
        if (detailBudget())
        {
            Log.d(TAG, "DNSR " + detail + " | " + summary());
        }
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

    public static void recordAaaaDropped(String name)
    {
        aaaaDropped.incrementAndGet();

        if (detailBudget())
        {
            Log.d(TAG, "DNSAAAA drop-query name=" + safeName(name) + " action=reply-NODATA | " + summary());
        }

        logThrottled("aaaa-drop");
    }

    public static void recordAaaaStripped(int records, String name)
    {
        aaaaStripped.addAndGet(Math.max(1, records));
        Log.d(TAG, "DNSAAAA strip-records name=" + safeName(name) + " count=" + records + " | " + summary());
        logThrottled("aaaa-strip");
    }

    public static void recordRetry()
    {
        retried.incrementAndGet();
        logThrottled("retry");
    }

    public static void recordServfail()
    {
        servfail.incrementAndGet();
        logThrottled("servfail");
    }

    public static String summary()
    {
        return "dns queries=" + queries.get() + " hijacked=" + hijacked.get() + " failed=" + failures.get()
                + " a=" + aQueries.get() + " aaaa=" + aaaaQueries.get() + " other=" + otherQueries.get()
                + " aaaaDropped=" + aaaaDropped.get() + " aaaaStripped=" + aaaaStripped.get()
                + " retried=" + retried.get() + " servfail=" + servfail.get();
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

    public static long aaaaQueryCount()
    {
        return aaaaQueries.get();
    }

    public static long aaaaDroppedCount()
    {
        return aaaaDropped.get();
    }

    public static long aaaaStrippedCount()
    {
        return aaaaStripped.get();
    }

    public static long servfailCount()
    {
        return servfail.get();
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

    private static String safeName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "-";
        }

        return name.length() > 64 ? name.substring(0, 64) + "..." : name;
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
