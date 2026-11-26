package com.minhui.vpn.zone;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 当前所在地图 / 主城暂停状态（进程内全局单例，静态字段）。
 *
 * <p>雷达的解析线程（{@code PhotonPacketParser}）与渲染线程（{@code RadarView}）
 * 都会高频读取，因此全部用 volatile 字段，不分配对象、不加锁读。
 *
 * <p>暂停模式：
 * <ul>
 *   <li>{@link #MODE_OFF}：不暂停（旧行为）；</li>
 *   <li>{@link #MODE_SKIP_ALL}（默认）：主城里完全不解析、完全不渲染；</li>
 *   <li>{@link #MODE_RENDER_ONLY}：只暂停渲染，解析照常（数据仍在累积）。</li>
 * </ul>
 *
 * <p>地图 id 来自 ChangeCluster（op 41，参数 0）与 Join 响应（参数 8），
 * 由 {@code PhotonPacketParser} 在切图时调用 {@link #setZone(String)} 更新；
 * 是否主城由 {@link ZoneClassifier}（assets/zones_meta.json）判定，未知地图按野外处理。
 */
public final class ZoneState
{
    private static final String TAG = "RadarDiag";

    /** 关闭主城暂停。 */
    public static final int MODE_OFF = 0;
    /** 完全不解析 + 完全不渲染（默认）。 */
    public static final int MODE_SKIP_ALL = 1;
    /** 只暂停渲染（解析继续）。 */
    public static final int MODE_RENDER_ONLY = 2;

    /**
     * 真机调试用覆盖文件（仅 debuggable 构建生效）：
     * app 内部 files 目录下的 {@code radar_zone_override}，内容一行
     * {@code CITY} / {@code WILD} / {@code <zoneId>}，可用
     * {@code adb shell run-as com.qradar.albion sh -c 'echo CITY > files/radar_zone_override'} 写入，
     * 删除该文件即恢复按真实地图 id 判定。用于不真正跑图也能验证暂停/恢复逻辑。
     */
    private static final String DEBUG_OVERRIDE_FILE = "radar_zone_override";

    /** 当前地图 id（已 normalize；未知为空串）。 */
    public static volatile String zoneId = "";
    /** 当前地图名（未知为 null）。 */
    public static volatile String zoneName = "";
    /** 当前是否在主城/安全城。 */
    public static volatile boolean isCity = false;
    /** 主城暂停模式，见 {@link #MODE_OFF} / {@link #MODE_SKIP_ALL} / {@link #MODE_RENDER_ONLY}。 */
    public static volatile int pauseMode = MODE_SKIP_ALL;

    private static volatile Context appContext;

    private ZoneState()
    {
    }

    /**
     * 启动时初始化（Application / 设置初始化处调用）：
     * 记录 ApplicationContext 并加载 assets 里的地图元数据。
     */
    public static void init(Context context)
    {
        if (context == null)
        {
            return;
        }

        appContext = context.getApplicationContext();
        ZoneClassifier.getInstance().ensureLoaded(appContext);
    }

    /** 完全不解析（pauseMode=1 且在主城）。 */
    public static boolean shouldSkipFully()
    {
        return pauseMode == MODE_SKIP_ALL && isCity;
    }

    /** 完全不渲染（pauseMode=1 或 2 且在主城）。 */
    public static boolean shouldSkipRender()
    {
        return pauseMode != MODE_OFF && isCity;
    }

    /**
     * 切图/入场时更新当前地图：用 {@link ZoneClassifier} 判定是否主城。
     *
     * <p>空 id（该消息没带地图 id）直接忽略，保持现状，避免把已知城市状态冲掉。
     * 只有地图或城市属性发生变化时才打一行 {@code ZONE ...} 日志（限流）。
     */
    public static synchronized void setZone(String rawZoneId)
    {
        String id = ZoneClassifier.normalize(rawZoneId);

        if (id.isEmpty())
        {
            return;
        }

        ZoneClassifier classifier = ZoneClassifier.getInstance();
        String name = classifier.zoneName(id);
        boolean city = classifier.isCity(id);
        String override = readDebugOverride();

        if (override != null && !override.isEmpty())
        {
            if ("CITY".equals(override))
            {
                city = true;
                name = "DEBUG-CITY";
            }
            else if ("WILD".equals(override))
            {
                city = false;
            }
            else
            {
                // 用指定地图 id 替代真实地图（按同一份元数据判定）
                id = ZoneClassifier.normalize(override);
                name = classifier.zoneName(id);

                if (name == null)
                {
                    name = "DEBUG-" + id;
                }

                city = classifier.isCity(id);
            }
        }

        if (id.equals(zoneId) && city == isCity)
        {
            return;
        }

        zoneId = id;
        zoneName = name;
        isCity = city;

        Log.d(TAG, "ZONE id=" + id + " city=" + city + " name=" + name
                + (override != null && !override.isEmpty() ? " override=" + override : ""));
    }

    /** 清空当前地图状态（退出游戏 / 停止 VPN 时可用）。 */
    public static synchronized void reset()
    {
        zoneId = "";
        zoneName = null;
        isCity = false;
    }

    /** 读取真机调试覆盖文件；非 debuggable 构建或文件不存在时返回 null。 */
    private static String readDebugOverride()
    {
        Context context = appContext;

        if (context == null)
        {
            return null;
        }

        try
        {
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            {
                return null;
            }

            File file = new File(context.getFilesDir(), DEBUG_OVERRIDE_FILE);

            if (!file.isFile())
            {
                return null;
            }

            BufferedReader reader = new BufferedReader(new FileReader(file));

            try
            {
                String line = reader.readLine();
                return line == null ? null : line.trim();
            }
            finally
            {
                reader.close();
            }
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
