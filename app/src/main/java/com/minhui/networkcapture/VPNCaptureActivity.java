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
            handler.post(()->vpnButton.setImageResource(R.mipmap.ic_stop));
            startRadarWindowService();
        }

        @Override
        public void onVpnEnd(Context context)
        {
            handler.post(()->vpnButton.setImageResource(R.mipmap.ic_start));

            if(windowService!=null)
            {
                stopService(windowService);
            }
        }
    };

    private ImageView vpnButton;

    String[] needPermissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
    String[] needOverlayPermission = {Manifest.permission.SYSTEM_ALERT_WINDOW};

    private Handler handler;

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

        handler = new Handler();
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

        windowService = new Intent(this, RadarDrawView.class);
        startService(windowService);
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
