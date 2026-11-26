package com.minhui.networkcapture.RadarActivities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarDrawView;
import com.minhui.networkcapture.RadarView.RadarView;

/**
 * 悬浮窗齿轮面板。
 *
 * <p>旧版这里是 PVP / 采集 / 怪物 / 箱子 四个零散设置标签（会直接在 overlay 里 inflate 旧设置页，
 * 容易在 Service 上下文里闪退）。现在这些设置全部并入「渲染选项」统一页，悬浮面板只保留一个
 * 入口按钮：点击后以 Activity 形式打开渲染选项页（不再在 Service 上下文里构建设置界面）。
 */
public class RadarFloatingActivity
{
    public RadarFloatingActivity(View view, LayoutInflater layoutInflater, RadarView radarView,
                                 RadarDrawView radarDrawView)
    {
        LinearLayout contentLayout = view.findViewById(R.id.content_layout);

        if (contentLayout == null)
        {
            return;
        }

        Context context = view.getContext();
        contentLayout.removeAllViews();
        contentLayout.setGravity(Gravity.CENTER);

        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        int paddingY = Math.round(12 * context.getResources().getDisplayMetrics().density);
        int corner = Math.round(8 * context.getResources().getDisplayMetrics().density);
        int hintTop = Math.round(8 * context.getResources().getDisplayMetrics().density);

        TextView openRenderOptions = new TextView(context);
        openRenderOptions.setText("渲染选项 ▸");
        openRenderOptions.setTextColor(Color.WHITE);
        openRenderOptions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        openRenderOptions.setGravity(Gravity.CENTER);
        openRenderOptions.setPadding(padding, paddingY, padding, paddingY);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF4840B);
        background.setCornerRadius(corner);
        openRenderOptions.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(openRenderOptions, params);

        TextView hint = new TextView(context);
        hint.setText("资源 / 敌人 / 玩家 / 箱子 / 雷达显示");
        hint.setTextColor(0xFF90A4AE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, hintTop, 0, 0);

        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(hint, hintParams);

        openRenderOptions.setOnClickListener(v ->
        {
            Intent intent = new Intent(context, RenderOptionsActivity.class);
            // 从悬浮窗（Service 上下文）启动 Activity 必须带 NEW_TASK
            // 独立任务栈：按返回直接回到游戏，而不是回到雷达首页
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);

            if (radarDrawView != null)
            {
                radarDrawView.hideRadarSettingsPanel();
            }
        });
    }
}
