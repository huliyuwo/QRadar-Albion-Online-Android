package com.minhui.networkcapture.Adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarActivities.RenderOptionsActivity;
import com.minhui.networkcapture.RadarActivities.SettingsActivity;

import java.util.List;

/**
 * 首页卡片入口。
 *
 * <p>旧版「PVP / 采集 / 怪物 / 箱子」四个零散设置入口已合并到「渲染选项」统一设置页，
 * 首页只保留两个入口：渲染选项（雷达显示配置）与设置（mihomo/订阅/VPN/关于 + 雷达调节）。
 */
public class MainDashBoardAdapter extends RecyclerView.Adapter<MainDashBoardAdapter.ViewHolder>
{
    List<String> titles;
    List<String> subtitles;
    List<Integer> images;
    LayoutInflater inflater;
    Context mContext;

    public MainDashBoardAdapter(Context ctx, List<String> titles, List<String> subtitles, List<Integer> images)
    {
        this.titles = titles;
        this.subtitles = subtitles;
        this.images = images;
        this.mContext = ctx;
        this.inflater = LayoutInflater.from(ctx);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        View view = inflater.inflate(R.layout.item_main_adapter,parent,false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position)
    {
        holder.title.setText(titles.get(position));
        holder.gridIcon.setImageResource(images.get(position));

        if (subtitles != null && position < subtitles.size())
        {
            holder.subtitle.setText(subtitles.get(position));
        }
    }

    @Override
    public int getItemCount() {
        return titles.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder
    {
        TextView title;
        TextView subtitle;
        ImageView gridIcon;

        public ViewHolder(@NonNull View itemView)
        {
            super(itemView);
            title = itemView.findViewById(R.id.textView2);
            subtitle = itemView.findViewById(R.id.textSub);
            gridIcon = itemView.findViewById(R.id.imageView2);

            itemView.setOnClickListener(v ->
            {
                switch (getAdapterPosition())
                {
                    case 0:
                        // PC 式「渲染选项」统一入口：资源/敌人/玩家/箱子/雷达显示分组
                        Intent renderOptionsActivity = new Intent(mContext, RenderOptionsActivity.class);
                        mContext.startActivity(renderOptionsActivity);
                        break;

                    case 1:
                        // App 设置：mihomo/订阅/VPN/语言 + 雷达大小、悬浮窗位置等基础调节
                        Intent settingsActivity = new Intent(mContext, SettingsActivity.class);
                        mContext.startActivity(settingsActivity);
                        break;
                }
            });
        }
    }
}
