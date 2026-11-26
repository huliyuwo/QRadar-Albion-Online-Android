package com.minhui.vpn.zone;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 地图种类识别（主城 / 安全城 vs 野外）。
 *
 * <p>数据来自 PC 版 ao-bin-dumps 的 zones.json（精简成 assets/zones_meta.json）：
 * <pre>{"3003":["Caerleon",1],"1007":["Inis Mon",0]}</pre>
 * 第二项 1 表示该图属于主城/安全城（zoneType 含 CITY / STARTINGCITY）。
 *
 * <p>放在 vpnadaptercore 而不是 app 模块，是因为解析发生在 vpnadaptercore
 * （{@code PhotonPacketParser}），而 app 依赖 vpnadaptercore，反向依赖不成立。
 * 库模块的 assets 会合并进 APK，app 侧用 Context 打开同一份文件即可。
 *
 * <p>游戏切换地图时会下发 ChangeCluster（op 41），参数 0 即为目标地图 id；
 * Join 响应参数 8 是当前 cluster。解析到 id 后即可判断是否在主城，
 * 从而决定是否暂停解析与渲染（见 {@link ZoneState}）。
 */
public final class ZoneClassifier
{
    private static final String TAG = "RadarDiag";
    private static final String ASSET_NAME = "zones_meta.json";

    private static ZoneClassifier instance;

    private final Map<String, ZoneMeta> zones = new HashMap<>();
    private boolean loaded;

    public static synchronized ZoneClassifier getInstance()
    {
        if (instance == null)
        {
            instance = new ZoneClassifier();
        }

        return instance;
    }

    private ZoneClassifier()
    {
    }

    /** 首次调用时从 assets 加载；失败则视为未知地图（不会误暂停）。 */
    public synchronized void ensureLoaded(Context context)
    {
        if (loaded || context == null)
        {
            return;
        }

        loaded = true;

        try
        {
            AssetManager assets = context.getAssets();
            InputStream in = assets.open(ASSET_NAME);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;

            while ((read = in.read(buffer)) > 0)
            {
                out.write(buffer, 0, read);
            }

            in.close();

            JSONObject root = new JSONObject(out.toString("UTF-8"));

            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); )
            {
                String key = it.next();
                JSONArray value = root.optJSONArray(key);

                if (value == null || value.length() < 2)
                {
                    continue;
                }

                zones.put(normalize(key), new ZoneMeta(value.optString(0), value.optInt(1) == 1));
            }

            Log.d(TAG, "zone metadata loaded: " + zones.size() + " zones");
        }
        catch (Throwable e)
        {
            Log.w(TAG, "zone metadata load failed: " + e);
        }
    }

    public boolean isLoaded()
    {
        return loaded && !zones.isEmpty();
    }

    /** 未知地图按“非主城”处理，避免误暂停。 */
    public boolean isCity(String zoneId)
    {
        ZoneMeta meta = zones.get(normalize(zoneId));
        return meta != null && meta.isCity;
    }

    public String zoneName(String zoneId)
    {
        ZoneMeta meta = zones.get(normalize(zoneId));
        return meta == null ? null : meta.name;
    }

    public int size()
    {
        return zones.size();
    }

    /**
     * 统一 key 形式：ChangeCluster 下发的是数字 id（可能是 Integer 也可能是 String），
     * 部分地图 key 带前缀（TNL-119 / BLACKBANK-2347 等），统一 trim + 大写。
     * 空值 / "null"（Utils.getString 对 null 会返回字符串 "null"）归一为空串。
     */
    public static String normalize(String zoneId)
    {
        if (zoneId == null)
        {
            return "";
        }

        String trimmed = zoneId.trim();

        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed))
        {
            return "";
        }

        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static final class ZoneMeta
    {
        final String name;
        final boolean isCity;

        ZoneMeta(String name, boolean isCity)
        {
            this.name = name;
            this.isCity = isCity;
        }
    }
}
