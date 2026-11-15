package com.minhui.networkcapture.RadarActivities;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.minhui.networkcapture.Fragments.SettingsFragment;
import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarDrawView;

/**
 * 首页"设置"卡片承载页。
 *
 * 悬浮窗面板已移除设置标签（在 Service/overlay 上下文中构建设置页会导致闪退），
 * mihomo/代理相关操作与雷达调节统一放在这里。
 *
 * 雷达服务未运行时（悬浮窗没开）radarDrawView/radarView 传 null，
 * SettingsFragment 内部已对所有雷达调用点做空值保护，代理/订阅设置不受影响。
 */
public class SettingsActivity extends AppCompatActivity
{
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.toolbar));
        setContentView(R.layout.activity_qr_settings);

        TextView toolbarTitle = findViewById(R.id.toolbarTitle);
        toolbarTitle.setText(getString(R.string.qr_settings));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("");
        }
        toolbar.setTitle("");
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        FrameLayout container = findViewById(R.id.settingsContainer);
        View settingsView = getLayoutInflater().inflate(R.layout.fragment_settings, container, false);
        container.addView(settingsView);

        RadarDrawView radarService = RadarDrawView.getInstance();

        new SettingsFragment(settingsView,
                radarService == null ? null : radarService.getRadarView(),
                radarService);
    }
}
