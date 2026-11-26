package com.minhui.networkcapture;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.minhui.vpn.zone.ZoneState;

import java.io.File;
import java.util.List;


/**
 * @author minhui.zhu
 *         Created by minhui.zhu on 2018/4/30.
 *         Copyright © 2017年 Oceanwing. All rights reserved.
 */

public class MyApplication extends Application
{
    public static final String BUGLY_ID="6c905fa4a7";
    /** 主城暂停模式持久化 key（0=关闭 / 1=完全不解析+不渲染 / 2=仅暂停渲染）。 */
    public static final String PREF_CITY_PAUSE_MODE = "radarCityPauseMode";
    private static  Context context;
    @Override
    public void onCreate()
    {
        super.onCreate();

        context = getApplicationContext();

        // 主城暂停：整个进程最早初始化地图元数据 + 暂停模式。
        // 解析线程（PhotonPacketParser）可能在 UI 起来之前就收到 ChangeCluster/Join，
        // 所以必须在这里读入，而不是等设置页。
        try
        {
            ZoneState.init(this);
            ZoneState.pauseMode = PreferenceManager.getDefaultSharedPreferences(this)
                    .getInt(PREF_CITY_PAUSE_MODE, ZoneState.MODE_SKIP_ALL);
        }
        catch (Throwable e)
        {
            Log.w("RadarDiag", "ZoneState init failed: " + e);
        }
    }
    public static Context getContext(){
        return context;
    }
}
