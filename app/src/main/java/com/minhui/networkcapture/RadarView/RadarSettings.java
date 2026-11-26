package com.minhui.networkcapture.RadarView;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.minhui.vpn.Handlers.HandlerItem.HarvestableType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RadarSettings
{
    private static RadarSettings instance;
    public  ArrayList<HarvestableType> harvestableTypes = new ArrayList<>();
    public boolean playerDot = true;
    public boolean playerNickname = true;
    public boolean playerHealth = true;
    public boolean playerMounted = true;
    public boolean playerGuildName = false;
    public boolean playerDistance = false;
    public boolean playerSound= false;

    /**
     * 过滤默认值：新安装/首次进游戏时必须能直接看到资源与怪物。
     * 之前这里全部默认 false，导致“解析到了实体但一个都不画”的核心问题。
     * PC 版默认矩阵是 T4-T8，但手机端地图经常是 T2-T8，这里默认全开更符合“开箱即用”。
     * 用户在设置里显式取消勾选时会把对应 key 写成 false，不会受影响。
     */
    public boolean[] harvestingTiers = new boolean[]{true,true,true,true,true,true,true,true};
    public boolean[] harvestingEnchants = new boolean[]{true,true,true,true,true,false};

    public boolean[] mobEnchants = new boolean[]{true,true,true,true,true};
    public boolean[] mobTiers = new boolean[]{true,true,true,true,true,true,true,true};
    public boolean mobHp = false;
    public int radarFloatingSettingWheelX = -999;
    public int radarFloatingSettingWheelY = -999;

    public int radarFloatingSquareX = -999;
    public int radarFloatingSquareY = -999;
    public boolean mobOther = true;
    public boolean mobSkinnable = true;
    public boolean mobHarvestable = true;
    public boolean mobBoss = true;
    public boolean harvestingFiber = true;
    public boolean harvestingWood = true;
    public boolean harvestingHide = true;
    public boolean harvestingOre = true;
    public boolean harvestingRock = true;
    public boolean harvestingZoneFishing = true;
    public boolean harvestingSize = false;
    public HashMap<String, Boolean> chests = new HashMap<>();
    public int pvpCircleBar;
    public int pvpCircleGapBar;

    public int pvpTextSizeBar;
    public int pvpTextGapBar;
    public int harvestingWidthHeightBar;
    public int harvestingIconTextGapBar;
    public int harvestingIconTextSizeBar;
    public int mobTextSizeBar;
    public int mobTextGapBar;
    public int mobRadarSizeBar;
    public int mobIconWidthHeightBar;
    public int chestIconWidthHeightBar;
    /** 渲染比例：1.0 = 100%（默认视野约 160 码），>1 为放大。 */
    public float radarScaleBar = 1f;
    public int radarSizeWidthHeightBar;
    public int radarMiddleCircleBar;
    public boolean radarShowTopMost = false;
    public boolean radarShowSquare;
    public boolean radarShowCircle;
    public int radarCircleSquareBorderBar;
    public int radarXBar;
    public int radarYBar;
    public int floatingWidthHeightBar;
    public int settingsHeightBar;
    public int settingsWidthBar;
    public int floatingYBar;
    public int floatingXBar;

    public int settingsTransparencyBar;

    public void init(Context context)
    {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        playerDot = sharedPreferences.getBoolean("playerDot", true);
        playerNickname = sharedPreferences.getBoolean("playerNickname", true);
        playerHealth = sharedPreferences.getBoolean("playerHealth", true);
        playerMounted = sharedPreferences.getBoolean("playerMounted", true);
        playerDistance = sharedPreferences.getBoolean("playerDistance", false);
        playerSound = sharedPreferences.getBoolean("playerSound", false);
        playerGuildName = sharedPreferences.getBoolean("playerGuildName", false);

        // 等级/附魔：缺省=可见（PC 版默认有等级矩阵，这里全开保证任何地图都能看到资源）
        harvestingTiers[0] =  sharedPreferences.getBoolean("harvestingTier1", true);
        harvestingTiers[1] =  sharedPreferences.getBoolean("harvestingTier2", true);
        harvestingTiers[2] =  sharedPreferences.getBoolean("harvestingTier3", true);
        harvestingTiers[3] =  sharedPreferences.getBoolean("harvestingTier4", true);
        harvestingTiers[4] =  sharedPreferences.getBoolean("harvestingTier5", true);
        harvestingTiers[5] =  sharedPreferences.getBoolean("harvestingTier6", true);
        harvestingTiers[6] =  sharedPreferences.getBoolean("harvestingTier7", true);
        harvestingTiers[7] =  sharedPreferences.getBoolean("harvestingTier8", true);

        harvestingEnchants[0]  = sharedPreferences.getBoolean("harvestingEnchant0", true);
        harvestingEnchants[1]  = sharedPreferences.getBoolean("harvestingEnchant1", true);
        harvestingEnchants[2]  = sharedPreferences.getBoolean("harvestingEnchant2", true);
        harvestingEnchants[3]  = sharedPreferences.getBoolean("harvestingEnchant3", true);
        harvestingEnchants[4]  = sharedPreferences.getBoolean("harvestingEnchant4", true);

        // 资源类型开关默认全开；只有用户显式关掉的类型才会从列表里移除
        harvestingFiber = sharedPreferences.getBoolean("harvestingFiber", true);
        harvestingRock = sharedPreferences.getBoolean("harvestingRock", true);
        harvestingOre = sharedPreferences.getBoolean("harvestingOre", true);
        harvestingHide = sharedPreferences.getBoolean("harvestingHide", true);
        harvestingWood = sharedPreferences.getBoolean("harvestingWood", true);
        harvestingZoneFishing = sharedPreferences.getBoolean("harvestingZoneFishing", true);

        harvestingSize = sharedPreferences.getBoolean("harvestingSize", false);

        chests.put("standard",sharedPreferences.getBoolean("chestStandard", false));
        chests.put("uncommon",sharedPreferences.getBoolean("chestUncommon", false));
        chests.put("rare",sharedPreferences.getBoolean("chestRare", false));
        chests.put("legendary",sharedPreferences.getBoolean("chestLegendary", false));

        // 先清空再按开关重建，避免 init() 被多次调用时列表重复累积
        harvestableTypes.clear();

        if(harvestingFiber)
        {
            updateHarvestableType(Arrays.asList(
                    HarvestableType.FIBER,
                    HarvestableType.FIBER_CRITTER,
                    HarvestableType.FIBER_GUARDIAN_DEAD,
                    HarvestableType.FIBER_GUARDIAN_RED), true);
        }

        if(harvestingRock)
        {
            updateHarvestableType(Arrays.asList(
                    HarvestableType.ROCK,
                    HarvestableType.ROCK_CRITTER_DEAD,
                    HarvestableType.ROCK_CRITTER_GREEN,
                    HarvestableType.ROCK_CRITTER_RED,
                    HarvestableType.ROCK_GUARDIAN_RED), true);
        }

        if(harvestingOre)
        {
            updateHarvestableType(Arrays.asList(
                    HarvestableType.ORE,
                    HarvestableType.ORE_CRITTER_DEAD,
                    HarvestableType.ORE_CRITTER_GREEN,
                    HarvestableType.ORE_CRITTER_RED,
                    HarvestableType.ORE_GUARDIAN_RED), true);
        }

        if(harvestingWood)
        {
            updateHarvestableType(Arrays.asList(
                    HarvestableType.WOOD,
                    HarvestableType.WOOD_CRITTER_DEAD,
                    HarvestableType.WOOD_CRITTER_GREEN,
                    HarvestableType.WOOD_CRITTER_RED,
                    HarvestableType.WOOD_GIANTTREE,
                    HarvestableType.WOOD_GUARDIAN_RED),true);
        }

        if(harvestingHide)
        {
            updateHarvestableType(Arrays.asList(
                    HarvestableType.HIDE,
                    HarvestableType.HIDE_FOREST,
                    HarvestableType.HIDE_STEPPE,
                    HarvestableType.HIDE_SWAMP,
                    HarvestableType.HIDE_MOUNTAIN,
                    HarvestableType.HIDE_HIGHLAND),true);
        }

        if (harvestingZoneFishing)
        {
            updateHarvestableType(List.of(
                    HarvestableType.FISHING_ZONE),true);
        }

        mobTiers[0] =  sharedPreferences.getBoolean("mobTier1", true);
        mobTiers[1] =  sharedPreferences.getBoolean("mobTier2", true);
        mobTiers[2] =  sharedPreferences.getBoolean("mobTier3", true);
        mobTiers[3] =  sharedPreferences.getBoolean("mobTier4", true);
        mobTiers[4] =  sharedPreferences.getBoolean("mobTier5", true);
        mobTiers[5] =  sharedPreferences.getBoolean("mobTier6", true);
        mobTiers[6] =  sharedPreferences.getBoolean("mobTier7", true);
        mobTiers[7] =  sharedPreferences.getBoolean("mobTier8", true);

        mobEnchants[0] = sharedPreferences.getBoolean("mobEnchant0", true);
        mobEnchants[1] = sharedPreferences.getBoolean("mobEnchant1", true);
        mobEnchants[2] = sharedPreferences.getBoolean("mobEnchant2", true);
        mobEnchants[3] = sharedPreferences.getBoolean("mobEnchant3", true);
        mobEnchants[4] = sharedPreferences.getBoolean("mobEnchant4", true);

        mobHp =  sharedPreferences.getBoolean("mobHp", false);

        // 怪物默认全开：用户报告“mobs=11 但一个怪物都不显示”的根因就是这四个默认 false
        mobHarvestable = sharedPreferences.getBoolean("mobHarvestable", true);
        mobOther = sharedPreferences.getBoolean("mobOther", true);
        mobSkinnable = sharedPreferences.getBoolean("mobSkinnable",true);
        mobBoss = sharedPreferences.getBoolean("mobBoss",true);

        radarFloatingSettingWheelX = sharedPreferences.getInt("radarFloatingSettingWheelX", -999);
        radarFloatingSettingWheelY = sharedPreferences.getInt("radarFloatingSettingWheelY", -999);

        radarFloatingSquareX = sharedPreferences.getInt("radarFloatingSquareX", -999);
        radarFloatingSquareY = sharedPreferences.getInt("radarFloatingSquareY", -999);

        pvpCircleBar = sharedPreferences.getInt("pvpCircleBar", 10);
        pvpCircleGapBar = sharedPreferences.getInt("pvpCircleGapBar", 25);
        pvpTextGapBar =   sharedPreferences.getInt("pvpTextGapBar", 15);
        pvpTextSizeBar =   sharedPreferences.getInt("pvpTextSizeBar", 15);

        harvestingWidthHeightBar = sharedPreferences.getInt("harvestingWidthHeightBar", 50);
        harvestingIconTextGapBar = sharedPreferences.getInt("harvestingIconTextGapBar", 15);
        harvestingIconTextSizeBar =  sharedPreferences.getInt("harvestingIconTextSizeBar", 15);

        mobTextSizeBar=  sharedPreferences.getInt("mobTextSizeBar", 15);
        mobTextGapBar=  sharedPreferences.getInt("mobTextGapBar", 15);
        mobRadarSizeBar=  sharedPreferences.getInt("mobRadarSizeBar", 10);
        mobIconWidthHeightBar=  sharedPreferences.getInt("mobIconWidthHeightBar", 50);

        chestIconWidthHeightBar = sharedPreferences.getInt("chestIconWidthHeightBar", 50);

        radarScaleBar  = sharedPreferences.getFloat("radarScaleBar", 1f);
        radarSizeWidthHeightBar =  sharedPreferences.getInt("radarSizeWidthHeightBar", 200);

        radarMiddleCircleBar = sharedPreferences.getInt("radarMiddleCircleBar",10);
        radarShowTopMost =  sharedPreferences.getBoolean("radarShowTopMost",false);

        radarShowSquare = sharedPreferences.getBoolean("radarShowSquare",false);
        radarShowCircle = sharedPreferences.getBoolean("radarShowCircle",false);

        radarCircleSquareBorderBar =sharedPreferences.getInt("radarCircleSquareBorderBar", 5);
        if (radarCircleSquareBorderBar <= 0)
        {
            radarCircleSquareBorderBar = 5;
        }

        radarXBar  = sharedPreferences.getInt("radarXBar",10);
        radarYBar = sharedPreferences.getInt("radarYBar",10);

        floatingWidthHeightBar = sharedPreferences.getInt("floatingWidthHeightBar",115);
        settingsHeightBar = sharedPreferences.getInt("settingsHeightBar",600);
        settingsWidthBar = sharedPreferences.getInt("settingsWidthBar",1200);
        floatingYBar = sharedPreferences.getInt("floatingYBar",10);
        floatingXBar = sharedPreferences.getInt("floatingXBar",10);

        settingsTransparencyBar = sharedPreferences.getInt("settingsTransparencyBar",1);
    }

    private RadarSettings()
    {
        // Initialize settings here
    }

    public boolean isInChests(String name)
    {
        for (Map.Entry<String, Boolean> entry :chests.entrySet())
        {
            if(name.contains(entry.getKey()))
            {
                return entry.getValue();
            }
        }
        return  false;
    }

    public  boolean isInHarvestable(HarvestableType ht) {
        return harvestableTypes.contains(ht);
    }

    /** 类型下标 -> 是否显示；越界时按“显示”处理，避免解析层新增类型时被静默过滤掉。 */
    public boolean isHarvestableTypeEnabled(int typeIndex)
    {
        HarvestableType[] values = HarvestableType.values();

        if (typeIndex < 0 || typeIndex >= values.length)
        {
            return true;
        }

        return harvestableTypes.isEmpty() || harvestableTypes.contains(values[typeIndex]);
    }

    public  void updateHarvestableType(List<HarvestableType> h, boolean show)
    {
        if (show)
        {
            for (HarvestableType ht : h)
            {
                if (!harvestableTypes.contains(ht))
                {
                    harvestableTypes.add(ht);
                }
            }
        }
        else
        {
            for (HarvestableType ht : h)
            {
                if (harvestableTypes.contains(ht))
                {
                    harvestableTypes.remove(ht);
                }
            }
        }
    }

    public static RadarSettings getInstance()
    {
        if (instance == null)
        {
            synchronized (RadarSettings.class)
            {
                if (instance == null)
                {
                    instance = new RadarSettings();
                }
            }
        }
        return instance;
    }
}
