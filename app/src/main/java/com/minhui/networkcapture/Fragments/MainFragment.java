package com.minhui.networkcapture.Fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.minhui.networkcapture.Adapters.MainDashBoardAdapter;
import com.minhui.networkcapture.R;

import java.util.ArrayList;
import java.util.List;

public class MainFragment extends Fragment
{
    RecyclerView dataList;
    List<String> titles;
    List<String> subtitles;
    List<Integer> images;
    MainDashBoardAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_main, container, false);

        dataList = view.findViewById(R.id.dataList);

        titles = new ArrayList<>();
        subtitles = new ArrayList<>();
        images = new ArrayList<>();

        // 旧版 PVP / 采集 / 怪物 / 箱子 四个零散设置入口已全部合并进「渲染选项」，
        // 首页只保留「渲染选项」（雷达显示配置）与「设置」（mihomo/订阅/VPN/关于）。
        titles.add(getString(R.string.qr_render_options));
        titles.add(getString(R.string.qr_settings));

        subtitles.add("资源 / 敌人 / 玩家 / 箱子 / 雷达显示");
        subtitles.add("mihomo / 订阅 / 关于 / 雷达调节");

        images.add(R.drawable.qradarlogo);
        images.add(R.drawable.setting);

        adapter = new MainDashBoardAdapter(getContext(), titles, subtitles, images);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(),2,GridLayoutManager.VERTICAL,false);

        dataList.setLayoutManager(gridLayoutManager);

        dataList.setAdapter(adapter);

        return view;
    }
}
