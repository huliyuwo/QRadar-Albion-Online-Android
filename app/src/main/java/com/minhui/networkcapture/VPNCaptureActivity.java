/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.minhui.networkcapture;

import static com.minhui.vpn.utils.VpnServiceHelper.START_VPN_SERVICE_REQUEST_CODE;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.minhui.networkcapture.Fragments.MainFragment;
import com.minhui.networkcapture.RadarView.RadarSettings;
import com.minhui.networkcapture.Utils.MihomoProcessManager;
import com.minhui.vpn.ProxyConfig;
import com.minhui.vpn.proxy.UpstreamProxyConfig;
import com.minhui.vpn.utils.DebugLog;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.util.ArrayList;

@Keep
public class VPNCaptureActivity extends FragmentActivity
{
    private static final int REQUEST_STORAGE_PERMISSION = 104;
    private static final int REQUEST_OVERLAY_PERMISSION = 5469;
    public static Context mContext;

    ProxyConfig.VpnStatusListener vpnStatusListener = new ProxyConfig.VpnStatusListener()
    {
        @Override
        public void onVpnStart(Context context)
        {
            // 回调在 VPN 工作线程，UI 更新必须抛回主线程；handler 在字段初始化时创建，
            // 不会出现“先注册监听、后 new Handler”导致的空指针竞态。
            postVpnButtonSync();
            startRadarWindowService();

            // VPN 刚建好时 DNS 链路可能还没通：做一次带重试的解析自检，并检查系统私人 DNS
            com.minhui.networkcapture.Utils.NetworkReadiness.checkAsync(context);
        }

        @Override
        public void onVpnEnd(Context context)
        {
            postVpnButtonSync();

            if(windowService!=null)
            {
                try
                {
                    stopService(windowService);
                }
                catch (Throwable ignored)
                {
                }
            }
        }
    };

    /** 统一走“按真实运行状态刷新”，避免依赖某一次回调时按钮状态还是旧的。 */
    private void postVpnButtonSync()
    {
        handler.post(this::syncVpnButtonState);
    }

    private ImageView vpnButton;

    private static final long VPN_BUTTON_SYNC_INTERVAL_MS = 1000;

    /** 兜底轮询：即使 VPN 状态回调漏掉（系统收回 VPN、服务被杀等），按钮也能回到正确状态。 */
    private final Runnable vpnButtonSyncTask = new Runnable()
    {
        @Override
        public void run()
        {
            syncVpnButtonState();

            if (handler != null)
            {
                handler.postDelayed(this, VPN_BUTTON_SYNC_INTERVAL_MS);
            }
        }
    };

    /** 用真实运行状态刷新右上角按钮（红色=停止 / 绿色=开启）。 */
    private void syncVpnButtonState()
    {
        if (vpnButton == null)
        {
            return;
        }

        boolean running = VpnServiceHelper.vpnRunningStatus();
        int expected = running ? R.mipmap.ic_stop : R.mipmap.ic_start;

        // 只在状态变化时设置，避免每秒重复触发 ImageView 重绘
        if (vpnButton.getTag(R.id.vpn) == null || (Integer) vpnButton.getTag(R.id.vpn) != expected)
        {
            vpnButton.setTag(R.id.vpn, expected);
            vpnButton.setImageResource(expected);
        }

        vpnButton.setEnabled(true);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        syncVpnButtonState();
        handler.removeCallbacks(vpnButtonSyncTask);
        handler.postDelayed(vpnButtonSyncTask, VPN_BUTTON_SYNC_INTERVAL_MS);
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        handler.removeCallbacks(vpnButtonSyncTask);
    }

    String[] needPermissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
    String[] needOverlayPermission = {Manifest.permission.SYSTEM_ALERT_WINDOW};

    private final Handler handler = new Handler(android.os.Looper.getMainLooper());

    @Override
    public void onBackPressed()
    {
        try
        {
            finishAffinity(); // Close all activities

            if(windowService != null)
            {
                stopService(windowService);
            }

            // 退出前杀掉内置 mihomo 子进程（stop 最多等 3s，放后台线程避免卡 UI）
            new Thread(() ->
            {
                MihomoProcessManager.getInstance().stop();
                System.exit(0);
            }, "app-exit").start();
        }
        catch (Exception ex)
        {

        }
    }

    ActivityResultLauncher<Intent> overlayPermissionResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result ->
            {
                if (!Settings.canDrawOverlays(this))
                {
                    // You don't have the permission
                    // Handle the case where user declined the permission
                }
            }
    );


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpn_capture);

        mContext = this;


        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.toolbar));
        vpnButton = (ImageView) findViewById(R.id.vpn);

        vpnButton.setOnClickListener(v ->
        {
          if(VpnServiceHelper.vpnRunningStatus())
          {
              closeVpn();
          }
          else
          {
              startVPN();
          }
        });

        // packageId = (TextView) findViewById(R.id.package_id);

        initMainFragment();
        requestStoragePermission();

        vpnButton.setEnabled(true);
        ProxyConfig.Instance.registerVpnStatusListener(vpnStatusListener);
        syncVpnButtonState();

        if (!Settings.canDrawOverlays(this))
        {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionResultLauncher.launch(intent);
        }

        MainFragment newFragment = new MainFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container_vp, newFragment)
                .addToBackStack(null)
                .commit();

        RadarSettings.getInstance().init(getApplicationContext());

        // 仅当"使用代理"开关打开时才拉起内置 mihomo（直连模式不需要它跑）
        MihomoProcessManager.getInstance().init(getApplicationContext());
        UpstreamProxyConfig.load(this);
        if (UpstreamProxyConfig.isEnabled())
        {
            MihomoProcessManager.getInstance().startAsync();
        }
    }
    Intent windowService;

    public  void initMainFragment()
    {

    }

    @Override
    protected void onStop()
    {
        super.onStop();
    }

    private void startRadarWindowService()
    {
        DebugLog.i("startRadar(%d)\n", 1);

        // 用户可在设置页关闭雷达渲染；关闭状态下启动 VPN 不再自动弹出悬浮窗
        boolean enabled = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("radarRenderEnabled", true);

        if (!enabled)
        {
            return;
        }

        windowService = new Intent(this, RadarDrawView.class);

        try
        {
            startService(windowService);
        }
        catch (Throwable e)
        {
            // 后台启动服务在部分 ROM 上会被限制，失败时退回前台 Activity 上下文再试一次
            DebugLog.e("startRadarWindowService failed: %s\n", e);
        }
    }

    private void requestStoragePermission()
    {
        ActivityCompat.requestPermissions(this, needPermissions, REQUEST_STORAGE_PERMISSION);
    }

    private void closeVpn() {
        VpnServiceHelper.changeVpnRunningStatus(this,false);
    }

    private void startVPN() {
        VpnServiceHelper.changeVpnRunningStatus(this,true);
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        ProxyConfig.Instance.unregisterVpnStatusListener(vpnStatusListener);

        // 退出前停掉按钮轮询，避免 Handler 泄漏 Activity
        if (handler != null)
        {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY_PERMISSION)
        {
            if (Settings.canDrawOverlays(this))
            {
                startRadarWindowService();
            }
            else
            {
                // Overlay permission not granted, handle accordingly
            }
        }

        if (requestCode == START_VPN_SERVICE_REQUEST_CODE && resultCode == RESULT_OK)
        {
            VpnServiceHelper.startVpnService(getApplicationContext());
        }
    }
}
