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
 * 悬浮窗齿轮面板（齿轮按钮点开后的小面板）。
 *
 * <p>上一版这里只有一个「渲染选项 ▸」按钮，点击会 startActivity 跳到 RenderOptionsActivity，
 * 需要离开游戏才能改渲染参数。现在默认入口改成悬浮窗内嵌的「快速渲染选项」面板：
 * 点击按钮由 {@link RadarDrawView#showQuickRenderOptions()} 在同层悬浮窗里展开选项卡 + 开关列表，
 * 雷达继续渲染，开关下一帧生效。
 *
 * <p>原来的完整设置页 {@link RenderOptionsActivity} 仍然保留，面板下方提供「更多设置…」入口，
 * 独立任务栈属性不变（返回直接回到游戏）。
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

        TextView openQuickOptions = new TextView(context);
        openQuickOptions.setText("渲染选项 ▸");
        openQuickOptions.setTextColor(Color.WHITE);
        openQuickOptions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        openQuickOptions.setGravity(Gravity.CENTER);
        openQuickOptions.setPadding(padding, paddingY, padding, paddingY);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFF4840B);
        background.setCornerRadius(corner);
        openQuickOptions.setBackground(background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(openQuickOptions, params);

        TextView hint = new TextView(context);
        hint.setText("雷达 / 资源 / 敌人 / 玩家 / 箱子 · 悬浮窗内直接调");
        hint.setTextColor(0xFF90A4AE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, hintTop, 0, 0);

        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(hint, hintParams);

        // 完整设置页入口（保留旧 Activity，独立任务栈跳转，返回直接回游戏）
        TextView openFullSettings = new TextView(context);
        openFullSettings.setText("更多设置…");
        openFullSettings.setTextColor(0xFF90A4AE);
        openFullSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        openFullSettings.setGravity(Gravity.CENTER);
        openFullSettings.setPadding(0, hintTop, 0, 0);

        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentLayout.addView(openFullSettings, moreParams);

        openQuickOptions.setOnClickListener(v ->
        {
            if (radarDrawView != null)
            {
                // 在悬浮窗里展开内嵌快速选项面板（自动收起当前小面板）
                radarDrawView.showQuickRenderOptions();
            }
        });

        openFullSettings.setOnClickListener(v ->
        {
            if (radarDrawView != null)
            {
                radarDrawView.openRenderOptionsActivity();
                return;
            }

            // 兜底：雷达服务不在时仍然能打开完整设置页
            Intent intent = new Intent(context, RenderOptionsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        });
    }
}
