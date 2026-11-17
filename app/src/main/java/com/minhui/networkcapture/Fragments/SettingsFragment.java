package com.minhui.networkcapture.Fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;
import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarDrawView;
import com.minhui.networkcapture.RadarView.RadarSettings;
import com.minhui.networkcapture.RadarView.RadarView;
import com.minhui.networkcapture.Utils.MihomoApiClient;
import com.minhui.networkcapture.Utils.MihomoProcessManager;
import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.proxy.UpstreamProxyConfig;

import androidx.appcompat.widget.SwitchCompat;

public class SettingsFragment extends Fragment
{
    TextView pvpCircleLabel;
    TextView pvpCircleGapLabel;
    TextView pvpTextGapLabel;
    TextView pvpTextSizeLabel;
    SeekBar pvpCircleBar;
    SeekBar pvpCircleGapBar;
    SeekBar pvpTextGapBar;
    SeekBar pvpTextSizeBar;
    TextView harvestingWidthHeightLabel;
    TextView harvestingIconTextGapLabel;
    TextView harvestingIconTextSizeLabel;
    SeekBar harvestingWidthHeightBar;
    SeekBar harvestingIconTextGapBar;
    SeekBar harvestingIconTextSizeBar;
    Button resetRadar;
    public  static int currPos = 0;

    public SettingsFragment(View view, RadarView radarView, RadarDrawView radarDrawView)
    {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(view.getContext());
        SharedPreferences.Editor editor = sharedPreferences.edit();

        resetRadar = view.findViewById(R.id.resetRadar);

        resetRadar.setOnClickListener(view1 -> MainHandler.getInstance().clearAll());

        // 雷达渲染开关：关掉后立刻移除悬浮窗，屏幕上的标记全部消失
        SwitchCompat radarRenderSwitch = view.findViewById(R.id.radarRenderSwitch);
        radarRenderSwitch.setChecked(sharedPreferences.getBoolean("radarRenderEnabled", true));
        radarRenderSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
        {
            editor.putBoolean("radarRenderEnabled", isChecked).apply();

            android.content.Intent intent = new android.content.Intent(view.getContext(), RadarDrawView.class);

            if (isChecked)
            {
                view.getContext().startService(intent);
            }
            else
            {
                view.getContext().stopService(intent);
            }
        });

        SwitchCompat upstreamProxySwitch = view.findViewById(R.id.upstreamProxySwitch);
        UpstreamProxyConfig.load(view.getContext());
        upstreamProxySwitch.setChecked(UpstreamProxyConfig.isEnabled());
        upstreamProxySwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
        {
            UpstreamProxyConfig.save(view.getContext(), isChecked,
                    UpstreamProxyConfig.getHost(), UpstreamProxyConfig.getPort());

            if (isChecked)
            {
                // 打开：走内置 mihomo 代理，确保它在跑
                MihomoProcessManager.getInstance().startAsync();
            }
            else
            {
                // 关闭：直连模式，停掉 mihomo 省电
                MihomoProcessManager.getInstance().stopAsync();
            }

            // 让新出口立刻生效：断开现有 TCP/UDP 会话，新流量按新设置重建
            com.minhui.vpn.service.FirewallVpnService.closeAllConnections();

            // 启动/停止是异步的，延时刷新状态行
            view.postDelayed(() -> updateMihomoStatus(MihomoProcessManager.getInstance(),
                    view.findViewById(R.id.tvMihomoStatus)), 1500);
        });

        TextView currentNodeText = view.findViewById(R.id.tvCurrentNode);
        Button refreshSubscription = view.findViewById(R.id.btnRefreshSubscription);
        Button switchNode = view.findViewById(R.id.btnSwitchNode);
        Context appContext = view.getContext().getApplicationContext();

        refreshSubscription.setOnClickListener(v ->
        {
            refreshSubscription.setEnabled(false);

            MihomoApiClient.refreshAllProviders(new MihomoApiClient.Callback<Integer>()
            {
                @Override
                public void onSuccess(Integer count)
                {
                    refreshSubscription.setEnabled(true);
                    Toast.makeText(appContext, appContext.getString(R.string.qr_subscription_updated, count), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message)
                {
                    refreshSubscription.setEnabled(true);
                    Toast.makeText(appContext, appContext.getString(R.string.qr_api_error, message), Toast.LENGTH_LONG).show();
                }
            });
        });

        switchNode.setOnClickListener(v -> MihomoApiClient.fetchSelectorGroup(new MihomoApiClient.Callback<MihomoApiClient.NodeGroup>()
        {
            @Override
            public void onSuccess(MihomoApiClient.NodeGroup group)
            {
                String[] items = group.all.toArray(new String[0]);
                int checked = group.all.indexOf(group.now);

                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.qr_select_node)
                        .setSingleChoiceItems(items, checked, (dialog, which) ->
                        {
                            dialog.dismiss();

                            String chosen = items[which];
                            switchNode.setEnabled(false);

                            MihomoApiClient.selectNode(group.groupName, chosen, new MihomoApiClient.Callback<String>()
                            {
                                @Override
                                public void onSuccess(String nodeName)
                                {
                                    switchNode.setEnabled(true);
                                    currentNodeText.setText(appContext.getString(R.string.qr_current_node, nodeName));
                                    Toast.makeText(appContext, appContext.getString(R.string.qr_node_switched, nodeName), Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onError(String message)
                                {
                                    switchNode.setEnabled(true);
                                    Toast.makeText(appContext, appContext.getString(R.string.qr_api_error, message), Toast.LENGTH_LONG).show();
                                }
                            });
                        })
                        .show();
            }

            @Override
            public void onError(String message)
            {
                Toast.makeText(appContext, appContext.getString(R.string.qr_api_error, message), Toast.LENGTH_LONG).show();
            }
        }));

        MihomoApiClient.fetchSelectorGroup(new MihomoApiClient.Callback<MihomoApiClient.NodeGroup>()
        {
            @Override
            public void onSuccess(MihomoApiClient.NodeGroup group)
            {
                currentNodeText.setText(appContext.getString(R.string.qr_current_node, group.now));
            }

            @Override
            public void onError(String message)
            {
                currentNodeText.setText(R.string.qr_current_node_unknown);
            }
        });

        MihomoProcessManager mihomoManager = MihomoProcessManager.getInstance();
        EditText subscriptionUrlInput = view.findViewById(R.id.etSubscriptionUrl);
        Button saveSubscription = view.findViewById(R.id.btnSaveSubscription);
        TextView mihomoStatusText = view.findViewById(R.id.tvMihomoStatus);

        subscriptionUrlInput.setText(mihomoManager.getSubscriptionUrl());
        updateMihomoStatus(mihomoManager, mihomoStatusText);

        saveSubscription.setOnClickListener(v ->
        {
            String url = subscriptionUrlInput.getText().toString().trim();

            if (url.isEmpty())
            {
                Toast.makeText(appContext, R.string.qr_subscription_empty, Toast.LENGTH_SHORT).show();
                return;
            }

            mihomoManager.saveSubscriptionUrl(url);

            if (UpstreamProxyConfig.isEnabled())
            {
                mihomoManager.restartAsync();
            }
            Toast.makeText(appContext, R.string.qr_subscription_saved, Toast.LENGTH_SHORT).show();

            // mihomo 重启需要一点时间，延时刷新状态与当前节点
            for (long delay : new long[]{1500L, 4000L})
            {
                mihomoStatusText.postDelayed(() ->
                {
                    updateMihomoStatus(mihomoManager, mihomoStatusText);

                    MihomoApiClient.fetchSelectorGroup(new MihomoApiClient.Callback<MihomoApiClient.NodeGroup>()
                    {
                        @Override
                        public void onSuccess(MihomoApiClient.NodeGroup group)
                        {
                            currentNodeText.setText(appContext.getString(R.string.qr_current_node, group.now));
                        }

                        @Override
                        public void onError(String message)
                        {
                            // 还没起来就等下一轮刷新
                        }
                    });
                }, delay);
            }
        });

        TextView languageRow = view.findViewById(R.id.qrLanguageRow);
        if (languageRow != null)
        {
            languageRow.setOnClickListener(v -> {
                final String[] items = {
                        v.getContext().getString(R.string.qr_follow_system),
                        v.getContext().getString(R.string.qr_chinese),
                        v.getContext().getString(R.string.qr_english)
                };
                new AlertDialog.Builder(v.getContext())
                        .setTitle(R.string.qr_language)
                        .setItems(items, (dialog, which) -> {
                            if (which == 0)
                            {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
                            }
                            else if (which == 1)
                            {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"));
                            }
                            else
                            {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"));
                            }
                        })
                        .show();
            });
        }

        pvpCircleLabel = view.findViewById(R.id.pvpCircleLabel);
        pvpCircleGapLabel = view.findViewById(R.id.pvpCircleGapLabel);
        pvpTextGapLabel = view.findViewById(R.id.pvpTextGapLabel);
        pvpTextSizeLabel = view.findViewById(R.id.pvpTextSizeLabel);

        pvpCircleBar = view.findViewById(R.id.pvpCircleBar);
        pvpCircleGapBar = view.findViewById(R.id.pvpCircleGapBar);
        pvpTextGapBar = view.findViewById(R.id.pvpTextGapBar);
        pvpTextSizeBar = view.findViewById(R.id.pvpTextSizeBar);

        pvpCircleLabel.setText(view.getContext().getString(R.string.qr_pvp_circle_size) + RadarSettings.getInstance().pvpCircleBar);
        pvpCircleBar.setProgress(RadarSettings.getInstance().pvpCircleBar);
        pvpCircleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().pvpCircleBar = progress;
                pvpCircleLabel.setText(view.getContext().getString(R.string.qr_pvp_circle_size) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // Called when the user starts interacting with the SeekBar
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                // Called when the user stops interacting with the SeekBar
                editor.putInt("pvpCircleBar", seekBar.getProgress());
                editor.apply();
            }
        });

        pvpCircleGapLabel.setText(view.getContext().getString(R.string.qr_pvp_circle_gap) + RadarSettings.getInstance().pvpCircleGapBar);
        pvpCircleGapBar.setProgress(RadarSettings.getInstance().pvpCircleGapBar);

        pvpCircleGapBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().pvpCircleGapBar = progress;
                pvpCircleGapLabel.setText(view.getContext().getString(R.string.qr_pvp_circle_gap) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("pvpCircleGapBar", seekBar.getProgress());
                editor.apply();
            }
        });

        pvpTextGapLabel.setText(view.getContext().getString(R.string.qr_pvp_text_gap) + RadarSettings.getInstance().pvpTextGapBar);
        pvpTextGapBar.setProgress(RadarSettings.getInstance().pvpTextGapBar);

        pvpTextGapBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().pvpTextGapBar = progress;
                pvpTextGapLabel.setText(view.getContext().getString(R.string.qr_pvp_text_gap2) + progress);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {

            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("pvpTextGapBar", seekBar.getProgress());
                editor.apply();
            }
        });

        pvpTextSizeLabel.setText(view.getContext().getString(R.string.qr_pvp_text_size) + RadarSettings.getInstance().pvpTextSizeBar);
        pvpTextSizeBar.setProgress(RadarSettings.getInstance().pvpTextSizeBar);

        pvpTextSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().pvpTextSizeBar = progress;
                pvpTextSizeLabel.setText(view.getContext().getString(R.string.qr_pvp_text_size) + progress);

                if (radarView != null)
                {
                    radarView.drawPlayers.setTextSize(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // Called when the user starts interacting with the SeekBar
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                // Called when the user stops interacting with the SeekBar
                editor.putInt("pvpTextSizeBar", seekBar.getProgress());
                editor.apply();
            }
        });

        harvestingWidthHeightLabel = view.findViewById(R.id.harvestingWidthHeightLabel);
        harvestingIconTextGapLabel = view.findViewById(R.id.harvestingIconTextGapLabel);
        harvestingIconTextSizeLabel = view.findViewById(R.id.harvestingIconTextSizeLabel);

        harvestingWidthHeightBar = view.findViewById(R.id.harvestingWidthHeightBar);
        harvestingIconTextGapBar = view.findViewById(R.id.harvestingIconTextGapBar);
        harvestingIconTextSizeBar = view.findViewById(R.id.harvestingIconTextSizeBar);

        // For harvestingWidthHeightBar:
        harvestingWidthHeightLabel.setText(view.getContext().getString(R.string.qr_harvest_icon_size) + RadarSettings.getInstance().harvestingWidthHeightBar);
        harvestingWidthHeightBar.setProgress(RadarSettings.getInstance().harvestingWidthHeightBar);
        harvestingWidthHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().harvestingWidthHeightBar = progress;
                harvestingWidthHeightLabel.setText(view.getContext().getString(R.string.qr_harvest_icon_size) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("harvestingWidthHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        harvestingIconTextGapLabel.setText(view.getContext().getString(R.string.qr_icon_text_gap_label) + RadarSettings.getInstance().harvestingIconTextGapBar);
        harvestingIconTextGapBar.setProgress(RadarSettings.getInstance().harvestingIconTextGapBar);
        harvestingIconTextGapBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().harvestingIconTextGapBar = progress;
                harvestingIconTextGapLabel.setText(view.getContext().getString(R.string.qr_icon_text_gap_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("harvestingIconTextGapBar", seekBar.getProgress());
                editor.apply();
            }
        });

        // For harvestingIconTextSizeBar:
        harvestingIconTextSizeLabel.setText(view.getContext().getString(R.string.qr_icon_text_size_label) + RadarSettings.getInstance().harvestingIconTextSizeBar);
        harvestingIconTextSizeBar.setProgress(RadarSettings.getInstance().harvestingIconTextSizeBar);

        harvestingIconTextSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().harvestingIconTextSizeBar = progress;

                if (radarView != null)
                {
                    radarView.harvestingDraw.setTextSize(RadarSettings.getInstance().harvestingIconTextSizeBar);
                }
                harvestingIconTextSizeLabel.setText(view.getContext().getString(R.string.qr_icon_text_size_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("harvestingIconTextSizeBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView mobIconWidthHeightLabel = view.findViewById(R.id.mobIconWidthHeightLabel);
        TextView mobRadarSizeLabel = view.findViewById(R.id.mobRadarSizeLabel);
        TextView mobTextGapLabel = view.findViewById(R.id.mobTextGapLabel);
        TextView mobTextSizeLabel = view.findViewById(R.id.mobTextSizeLabel);

        SeekBar mobIconWidthHeightBar = view.findViewById(R.id.mobIconWidthHeightBar);
        SeekBar mobRadarSizeBar = view.findViewById(R.id.mobRadarSizeBar);
        SeekBar mobTextGapBar = view.findViewById(R.id.mobTextGapBar);
        SeekBar mobTextSizeBar = view.findViewById(R.id.mobTextSizeBar);

        mobIconWidthHeightLabel.setText(view.getContext().getString(R.string.qr_mob_icon_size) + RadarSettings.getInstance().mobIconWidthHeightBar);
        mobIconWidthHeightBar.setProgress(RadarSettings.getInstance().mobIconWidthHeightBar);
        mobIconWidthHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().mobIconWidthHeightBar = progress;
                mobIconWidthHeightLabel.setText(view.getContext().getString(R.string.qr_mob_icon_size) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("mobIconWidthHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        // For mobRadarSizeBar:
        mobRadarSizeLabel.setText(view.getContext().getString(R.string.qr_circle_size_label) + RadarSettings.getInstance().mobRadarSizeBar);
        mobRadarSizeBar.setProgress(RadarSettings.getInstance().mobRadarSizeBar);

        mobRadarSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().mobRadarSizeBar = progress;
                mobRadarSizeLabel.setText(view.getContext().getString(R.string.qr_circle_size_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("mobRadarSizeBar", seekBar.getProgress());
                editor.apply();
            }
        });

        // For mobTextGapBar:
        mobTextGapLabel.setText(view.getContext().getString(R.string.qr_text_gap_label) + RadarSettings.getInstance().mobTextGapBar);
        mobTextGapBar.setProgress(RadarSettings.getInstance().mobTextGapBar);

        mobTextGapBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().mobTextGapBar = progress;
                mobTextGapLabel.setText(view.getContext().getString(R.string.qr_text_gap_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("mobTextGapBar", seekBar.getProgress());
                editor.apply();
            }
        });

        // For mobTextSizeBar:
        mobTextSizeLabel.setText(view.getContext().getString(R.string.qr_text_size_label) + RadarSettings.getInstance().mobTextSizeBar);
        mobTextSizeBar.setProgress(RadarSettings.getInstance().mobTextSizeBar);

        mobTextSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().mobTextSizeBar = progress;
                if (radarView != null)
                {
                    radarView.drawMobs.setTextSize(progress);
                    radarView.invalidate();
                }

                mobTextSizeLabel.setText(view.getContext().getString(R.string.qr_text_size_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("mobTextSizeBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView chestIconWidthHeightLabel = view.findViewById(R.id.chestIconWidthHeightLabel);
        SeekBar chestIconWidthHeightBar = view.findViewById(R.id.chestIconWidthHeightBar);

        chestIconWidthHeightLabel.setText(view.getContext().getString(R.string.qr_chest_icon_size)+RadarSettings.getInstance().chestIconWidthHeightBar);
        chestIconWidthHeightBar.setProgress(RadarSettings.getInstance().chestIconWidthHeightBar);
        chestIconWidthHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().chestIconWidthHeightBar = progress;
                chestIconWidthHeightLabel.setText(view.getContext().getString(R.string.qr_chest_icon_size)+progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("chestIconWidthHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView radarScaleLabel = view.findViewById(R.id.radarScaleLabel);
        SeekBar radarScaleBar= view.findViewById(R.id.radarScaleBar);

        int firstTime = (int) (RadarSettings.getInstance().radarScaleBar * 100);
        String text = Math.round(RadarSettings.getInstance().radarScaleBar * 100) + "%";
        radarScaleLabel.setText(view.getContext().getString(R.string.qr_radar_scale_label)+text);
        radarScaleBar.setProgress(firstTime);

        radarScaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                float totalProgress =  progress/100f;

                // 先更新缩放值再重算矩阵，否则这一帧用的还是旧比例
                RadarSettings.getInstance().radarScaleBar = totalProgress;

                if (radarDrawView != null)
                {
                    radarDrawView.reInitMatrix();
                }

                radarScaleLabel.setText(view.getContext().getString(R.string.qr_radar_scale_label) + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                float totalProgress = seekBar.getProgress()/100f;

                editor.putFloat("radarScaleBar", totalProgress);
                editor.apply();
            }
        });

        TextView radarSizeWidthHeightLabel = view.findViewById(R.id.radarSizeWidthHeightLabel);
        SeekBar radarSizeWidthHeightBar = view.findViewById(R.id.radarSizeWidthHeightBar);

        radarSizeWidthHeightLabel.setText(view.getContext().getString(R.string.qr_radar_size_label)+RadarSettings.getInstance().radarSizeWidthHeightBar);
        radarSizeWidthHeightBar.setProgress(RadarSettings.getInstance().radarSizeWidthHeightBar);
        radarSizeWidthHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (radarDrawView != null)
                {
                    radarDrawView.setRadarSize(progress);
                }

                RadarSettings.getInstance().radarSizeWidthHeightBar = progress;
                radarSizeWidthHeightLabel.setText(view.getContext().getString(R.string.qr_radar_size_label)+progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("radarSizeWidthHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView radarMiddleCircleLabel = view.findViewById(R.id.radarMiddleCircleLabel);
        SeekBar radarMiddleCircleBar = view.findViewById(R.id.radarMiddleCircleBar);

        radarMiddleCircleLabel.setText(view.getContext().getString(R.string.qr_radar_mid_circle2)+RadarSettings.getInstance().radarMiddleCircleBar);
        radarMiddleCircleBar.setProgress(RadarSettings.getInstance().radarMiddleCircleBar);

        radarMiddleCircleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().radarMiddleCircleBar = progress;
                radarMiddleCircleLabel.setText(view.getContext().getString(R.string.qr_radar_mid_circle)+progress);

                if (radarView != null)
                {
                    radarView.invalidate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("radarMiddleCircleBar", seekBar.getProgress());
                editor.apply();
            }
        });

        CheckBox radarShowTopMostCheckBox = view.findViewById(R.id.radarShowTopMostCheckBox);
        radarShowTopMostCheckBox.setChecked(RadarSettings.getInstance().radarShowTopMost);

        radarShowTopMostCheckBox.setOnCheckedChangeListener((compoundButton, b) ->
        {
            RadarSettings.getInstance().radarShowTopMost = b;

            editor.putBoolean("radarShowTopMost", b);
            editor.apply();
        });

        CheckBox radarShowSquareCheckBox = view.findViewById(R.id.radarShowSquareCheckBox);
        radarShowSquareCheckBox.setChecked(RadarSettings.getInstance().radarShowSquare);

        radarShowSquareCheckBox.setOnCheckedChangeListener((compoundButton, b) ->
        {
            RadarSettings.getInstance().radarShowSquare = b;
            editor.putBoolean("radarShowSquare", b);
            editor.apply();
            if (radarView != null)
            {
                radarView.invalidate();
            }
        });

        CheckBox radarShowCircleCheckBox = view.findViewById(R.id.radarShowCircleCheckBox);
        radarShowCircleCheckBox.setChecked(RadarSettings.getInstance().radarShowCircle);

        radarShowCircleCheckBox.setOnCheckedChangeListener((compoundButton, b) ->
        {
            RadarSettings.getInstance().radarShowCircle = b;
            editor.putBoolean("radarShowCircle", b);
            editor.apply();
            if (radarView != null)
            {
                radarView.invalidate();
            }
        });

        TextView radarCircleSquareBorderLabel = view.findViewById(R.id.radarCircleSquareBorderLabel);
        SeekBar radarCircleSquareBorderBar = view.findViewById(R.id.radarCircleSquareBorderBar);

        radarCircleSquareBorderLabel.setText(view.getContext().getString(R.string.qr_radar_border)+RadarSettings.getInstance().radarCircleSquareBorderBar);
        radarCircleSquareBorderBar.setProgress(RadarSettings.getInstance().radarCircleSquareBorderBar);

        radarCircleSquareBorderBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (radarView != null)
                {
                    radarView.setBorderSize((int) progress);
                }
                RadarSettings.getInstance().radarCircleSquareBorderBar = progress;

                radarCircleSquareBorderLabel.setText(view.getContext().getString(R.string.qr_radar_border)+progress);
                if (radarView != null)
                {
                    radarView.invalidate();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("radarCircleSquareBorderBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView radarXLabel = view.findViewById(R.id.radarXLabel);
        SeekBar radarXBar = view.findViewById(R.id.radarXBar);

        radarXLabel.setText(view.getContext().getString(R.string.qr_radar_x_label)+RadarSettings.getInstance().radarXBar);
        radarXBar.setProgress(RadarSettings.getInstance().radarXBar);

        radarXBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (radarDrawView != null)
                {
                    radarDrawView.setRadarX(progress);
                }

                RadarSettings.getInstance().radarXBar = progress;
                radarXLabel.setText(view.getContext().getString(R.string.qr_radar_x_label)+progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("radarXBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView radarYLabel = view.findViewById(R.id.radarYLabel);
        SeekBar radarYBar = view.findViewById(R.id.radarYBar);

        radarYLabel.setText(view.getContext().getString(R.string.qr_radar_y_label) + RadarSettings.getInstance().radarYBar);
        radarYBar.setProgress(RadarSettings.getInstance().radarYBar);

        radarYBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (radarDrawView != null)
                {
                    radarDrawView.setRadarY(progress);
                }
                RadarSettings.getInstance().radarYBar = progress;
                radarYLabel.setText(view.getContext().getString(R.string.qr_radar_y_label) + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("radarYBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView settingsWidthLabel = view.findViewById(R.id.settingsWidthLabel);
        SeekBar settingsWidthBar = view.findViewById(R.id.settingsWidthBar);

        settingsWidthLabel.setText(view.getContext().getString(R.string.qr_settings_width_label)+RadarSettings.getInstance().settingsWidthBar);
        settingsWidthBar.setProgress(RadarSettings.getInstance().settingsWidthBar);

        settingsWidthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().settingsWidthBar = progress;
                settingsWidthLabel.setText(view.getContext().getString(R.string.qr_settings_width_label)+progress);

                if (radarDrawView != null)
                {
                    radarDrawView.setSettingsWidth(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("settingsWidthBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView settingsHeightLabel = view.findViewById(R.id.settingsHeightLabel);
        SeekBar settingsHeightBar = view.findViewById(R.id.settingsHeightBar);

        settingsHeightLabel.setText(view.getContext().getString(R.string.qr_settings_height_label)+RadarSettings.getInstance().settingsHeightBar);
        settingsHeightBar.setProgress(RadarSettings.getInstance().settingsHeightBar);

        settingsHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().settingsHeightBar = progress;
                settingsHeightLabel.setText(view.getContext().getString(R.string.qr_settings_height_label) + progress);
                if (radarDrawView != null)
                {
                    radarDrawView.setSettingsHeight(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("settingsHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView floatingWidthHeightLabel  = view.findViewById(R.id.floatingWidthHeightLabel);
        SeekBar floatingWidthHeightBar = view.findViewById(R.id.floatingWidthHeightBar);

        floatingWidthHeightLabel.setText(view.getContext().getString(R.string.qr_floating_icon_size)+RadarSettings.getInstance().floatingWidthHeightBar);
        floatingWidthHeightBar.setProgress(RadarSettings.getInstance().floatingWidthHeightBar);

        floatingWidthHeightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().floatingWidthHeightBar = progress;
                floatingWidthHeightLabel.setText(view.getContext().getString(R.string.qr_floating_icon_size)+progress);

                if (radarDrawView != null)
                {
                    radarDrawView.setFloatingSize(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("floatingWidthHeightBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView floatingXLabel = view.findViewById(R.id.floatingXLabel);
        SeekBar floatingXBar = view.findViewById(R.id.floatingXBar);

        floatingXLabel.setText(view.getContext().getString(R.string.qr_floating_x_label)+RadarSettings.getInstance().floatingXBar);
        floatingXBar.setProgress(RadarSettings.getInstance().floatingXBar);

        floatingXBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().floatingXBar = progress;
                floatingXLabel.setText(view.getContext().getString(R.string.qr_floating_x_label)+progress);

                if (radarDrawView != null)
                {
                    radarDrawView.setFloatingX(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("floatingXBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView floatingYLabel = view.findViewById(R.id.floatingYLabel);
        SeekBar floatingYBar = view.findViewById(R.id.floatingYBar);

        floatingYLabel.setText(view.getContext().getString(R.string.qr_floating_y_label)+RadarSettings.getInstance().floatingYBar);
        floatingYBar.setProgress(RadarSettings.getInstance().floatingYBar);

        floatingYBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                RadarSettings.getInstance().floatingYBar = progress;

                floatingYLabel.setText(view.getContext().getString(R.string.qr_floating_y_label) + progress);
                if (radarDrawView != null)
                {
                    radarDrawView.setFloatingY(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("floatingYBar", seekBar.getProgress());
                editor.apply();
            }
        });

        TextView settingsTransparencyLabel = view.findViewById(R.id.settingsTransparencyLabel);
        SeekBar settingsTransparencyBar = view.findViewById(R.id.settingsTransparencyBar);

        settingsTransparencyLabel.setText(view.getContext().getString(R.string.qr_settings_transparency_label)+RadarSettings.getInstance().settingsTransparencyBar);
        settingsTransparencyBar.setProgress(RadarSettings.getInstance().settingsTransparencyBar);

        settingsTransparencyBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                int color = Color.argb(progress,0,0,0);

                RadarSettings.getInstance().settingsTransparencyBar = progress;
                settingsTransparencyLabel.setText(view.getContext().getString(R.string.qr_settings_transparency_label) + progress);
                if (radarDrawView != null)
                {
                    radarDrawView.setTransparencySettings(color);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
                // No action needed here
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                editor.putInt("settingsTransparencyBar", seekBar.getProgress());
                editor.apply();
            }
        });
    }

    private static void updateMihomoStatus(MihomoProcessManager manager, TextView statusText)
    {
        int textRes;
        boolean running;

        if (!manager.isBinaryAvailable())
        {
            textRes = R.string.qr_mihomo_status_unavailable;
            running = false;
        }
        else if (!manager.isConfigured())
        {
            textRes = R.string.qr_mihomo_status_not_configured;
            running = false;
        }
        else if (manager.isRunning())
        {
            textRes = R.string.qr_mihomo_status_running;
            running = true;
        }
        else
        {
            textRes = R.string.qr_mihomo_status_stopped;
            running = false;
        }

        statusText.setText(textRes);
        statusText.setTextColor(Color.parseColor(running ? "#00FF00" : "#808080"));
    }
}
