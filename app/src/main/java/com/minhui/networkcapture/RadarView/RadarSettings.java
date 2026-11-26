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

    // ------------------------------------------------------------------
    // 资源类别（渲染选项页/MobsResourceTable/图标都用这套 key）
    // ------------------------------------------------------------------
    public static final String[] RESOURCE_KIND_KEYS = {"wood", "rock", "fiber", "hide", "ore"};
    public static final String[] RESOURCE_KIND_NAMES = {"Wood 木材", "Rock 岩石", "Fiber 纤维", "Hide 兽皮", "Ore 矿石"};
    /** 每个类别的等级矩阵：matrix[enchant 0..4][tier 0..7]。 */
    public static final int MATRIX_ENCHANTS = 5;
    public static final int MATRIX_TIERS = 8;

    public  ArrayList<HarvestableType> harvestableTypes = new ArrayList<>();
    public boolean playerDot = true;
    public boolean playerNickname = true;
    public boolean playerHealth = true;
    public boolean playerMounted = true;
    public boolean playerGuildName = false;
    public boolean playerDistance = false;
    public boolean playerSound= false;
    /** PC 式玩家分组：联盟/阵营配色、敌对(红名)、被动/中立、雷达上检测到的玩家列表。 */
    public boolean playerAllianceColor = true;
    public boolean playerFactionColor = true;
    public boolean playerHostile = true;
    public boolean playerPassive = true;
    public boolean playerPanel = true;
    public int playerMaxDisplay = 50;

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
    /** PC 式敌人分类开关（默认全开，与配色一一对应）。 */
    public boolean mobMiniBoss = true;
    public boolean mobEnemy = true;
    public boolean mobDrone = true;
    public boolean mobMist = true;
    public boolean mobEvents = true;
    public boolean mobGuard = true;
    public boolean harvestingFiber = true;
    public boolean harvestingWood = true;
    public boolean harvestingHide = true;
    public boolean harvestingOre = true;
    public boolean harvestingRock = true;
    public boolean harvestingZoneFishing = true;
    public boolean harvestingSize = false;

    // ------------------------------------------------------------------
    // 雷达 / 显示（PC Radar 分组）
    // ------------------------------------------------------------------
    /** 图标大小倍率（0.5~2.0，1.0 = PC 默认）。 */
    public float radarIconScale = 1f;
    /** 资源等级角标：true=按类别色块 + 等级/附魔，false=直接用图标。 */
    public boolean radarTierBadges = false;
    /** 资源数量标签（节点剩余数量）。 */
    public boolean radarResourceCount = false;
    /** 资源距离标签（到本地玩家的码数）。 */
    public boolean radarResourceDistance = false;
    /** 资源聚类（同类相邻节点合并成一个点）。 */
    public boolean radarClusters = false;
    public int radarClusterRadius = 30;
    public int radarClusterMinSize = 2;
    /** HUD 统计框 / 距离环 / 背景。 */
    public boolean radarShowHud = true;
    public boolean radarShowRings = true;
    public boolean radarShowBackground = true;

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

    /** 静态资源等级矩阵：kind -> [enchant 0..4][tier 0..7]。 */
    private final HashMap<String, boolean[][]> staticResourceMatrix = new HashMap<>();
    /** 活体资源（资源怪）等级矩阵：kind -> [enchant 0..4][tier 0..7]。 */
    private final HashMap<String, boolean[][]> livingResourceMatrix = new HashMap<>();

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

        playerAllianceColor = sharedPreferences.getBoolean("playerAllianceColor", true);
        playerFactionColor = sharedPreferences.getBoolean("playerFactionColor", true);
        playerHostile = sharedPreferences.getBoolean("playerHostile", true);
        playerPassive = sharedPreferences.getBoolean("playerPassive", true);
        playerPanel = sharedPreferences.getBoolean("playerPanel", true);
        playerMaxDisplay = sharedPreferences.getInt("playerMaxDisplay", 50);

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
        mobMiniBoss = sharedPreferences.getBoolean("mobMiniBoss", true);
        mobEnemy = sharedPreferences.getBoolean("mobEnemy", true);
        mobDrone = sharedPreferences.getBoolean("mobDrone", true);
        mobMist = sharedPreferences.getBoolean("mobMist", true);
        mobEvents = sharedPreferences.getBoolean("mobEvents", true);
        mobGuard = sharedPreferences.getBoolean("mobGuard", true);

        // 雷达/显示
        radarIconScale = sharedPreferences.getFloat("radarIconScale", 1f);
        radarTierBadges = sharedPreferences.getBoolean("radarTierBadges", false);
        radarResourceCount = sharedPreferences.getBoolean("radarResourceCount", false);
        radarResourceDistance = sharedPreferences.getBoolean("radarResourceDistance", false);
        radarClusters = sharedPreferences.getBoolean("radarClusters", false);
        radarClusterRadius = sharedPreferences.getInt("radarClusterRadius", 30);
        radarClusterMinSize = sharedPreferences.getInt("radarClusterMinSize", 2);
        radarShowHud = sharedPreferences.getBoolean("radarShowHud", true);
        radarShowRings = sharedPreferences.getBoolean("radarShowRings", true);
        radarShowBackground = sharedPreferences.getBoolean("radarShowBackground", true);

        // 资源等级矩阵：旧版本没有矩阵 key，用旧的全局等级/附魔开关作为默认值，保证升级后行为不变
        for (String kind : RESOURCE_KIND_KEYS)
        {
            boolean kindEnabledFromPrefs = isLegacyKindEnabled(sharedPreferences, kind);

            staticResourceMatrix.put(kind, loadMatrix(sharedPreferences, "resStatic_" + kind,
                    harvestingTiers, harvestingEnchants, kindEnabledFromPrefs));
            livingResourceMatrix.put(kind, loadMatrix(sharedPreferences, "resLiving_" + kind,
                    null, null, mobHarvestable && mobSkinnable));
        }

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

    private boolean isLegacyKindEnabled(SharedPreferences preferences, String kind)
    {
        switch (kind)
        {
            case "wood":
                return harvestingWood;
            case "rock":
                return harvestingRock;
            case "fiber":
                return harvestingFiber;
            case "hide":
                return harvestingHide;
            case "ore":
                return harvestingOre;
            default:
                return true;
        }
    }

    /**
     * 读取一个类别矩阵。pref 里没有这个 key 时用 defaultOn + 旧的全局等级/附魔开关做默认值，
     * 这样老用户升级后原有的“层级筛选”行为不会突然失效。
     */
    private boolean[][] loadMatrix(SharedPreferences preferences, String key,
                                   boolean[] legacyTiers, boolean[] legacyEnchants, boolean defaultOn)
    {
        String packed = preferences.getString(key, null);
        boolean[][] matrix = new boolean[MATRIX_ENCHANTS][MATRIX_TIERS];

        if (packed != null && packed.length() >= MATRIX_ENCHANTS * MATRIX_TIERS)
        {
            for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
            {
                for (int tier = 0; tier < MATRIX_TIERS; tier++)
                {
                    matrix[enchant][tier] = packed.charAt(enchant * MATRIX_TIERS + tier) == '1';
                }
            }

            return matrix;
        }

        for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
        {
            for (int tier = 0; tier < MATRIX_TIERS; tier++)
            {
                boolean value = defaultOn;

                if (legacyTiers != null && tier < legacyTiers.length)
                {
                    value = value && legacyTiers[tier];
                }

                if (legacyEnchants != null && enchant < legacyEnchants.length)
                {
                    value = value && legacyEnchants[enchant];
                }

                matrix[enchant][tier] = value;
            }
        }

        return matrix;
    }

    private static String packMatrix(boolean[][] matrix)
    {
        StringBuilder builder = new StringBuilder(MATRIX_ENCHANTS * MATRIX_TIERS);

        for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
        {
            for (int tier = 0; tier < MATRIX_TIERS; tier++)
            {
                builder.append(matrix[enchant][tier] ? '1' : '0');
            }
        }

        return builder.toString();
    }

    public boolean[][] getResourceMatrix(boolean living, String kind)
    {
        String normalized = normalizeKind(kind);
        HashMap<String, boolean[][]> source = living ? livingResourceMatrix : staticResourceMatrix;
        boolean[][] matrix = source.get(normalized);

        if (matrix == null)
        {
            matrix = new boolean[MATRIX_ENCHANTS][MATRIX_TIERS];

            for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
            {
                Arrays.fill(matrix[enchant], true);
            }

            source.put(normalized, matrix);
        }

        return matrix;
    }

    /** 把 "log"/"logs"/"wood" 统一成 "wood"；未知类别返回 null。 */
    public static String normalizeKind(String kind)
    {
        if (kind == null)
        {
            return null;
        }

        String lower = kind.toLowerCase();

        if (lower.startsWith("wood") || lower.startsWith("log"))
        {
            return "wood";
        }

        if (lower.startsWith("fiber"))
        {
            return "fiber";
        }

        if (lower.startsWith("hide") || lower.startsWith("leather"))
        {
            return "hide";
        }

        if (lower.startsWith("rock") || lower.startsWith("stone"))
        {
            return "rock";
        }

        if (lower.startsWith("ore"))
        {
            return "ore";
        }

        return null;
    }

    /**
     * 静态资源是否显示。
     * 同时兼容旧设置页的全局等级/附魔开关（AND），矩阵是新的细粒度控制。
     */
    public boolean isStaticResourceEnabled(String kind, int tier, int enchant)
    {
        String normalized = normalizeKind(kind);

        if (normalized == null)
        {
            return true;
        }

        if (tier >= 1 && tier <= MATRIX_TIERS && !harvestingTiers[tier - 1])
        {
            return false;
        }

        if (enchant >= 0 && enchant < harvestingEnchants.length && !harvestingEnchants[enchant])
        {
            return false;
        }

        return isMatrixCellVisible(getResourceMatrix(false, normalized), tier, enchant);
    }

    /**
     * 活体资源（资源怪）是否显示。
     * PC 语义：living resource 属资源页，按 类别 + 等级/附魔矩阵 控制；
     * 未知 tier/enchant 时默认显示（此前按怪物等级矩阵过滤会把它们滤掉）。
     */
    public boolean isLivingResourceEnabled(String kind, int tier, int enchant)
    {
        String normalized = normalizeKind(kind);

        if (normalized == null)
        {
            return true;
        }

        return isMatrixCellVisible(getResourceMatrix(true, normalized), tier, enchant);
    }

    private static boolean isMatrixCellVisible(boolean[][] matrix, int tier, int enchant)
    {
        // 未知等级/附魔：默认显示，避免“解析到了但被静默滤掉”
        if (tier < 1 || tier > MATRIX_TIERS || enchant < 0 || enchant >= MATRIX_ENCHANTS)
        {
            return true;
        }

        return matrix[enchant][tier - 1];
    }

    public void setResourceCell(boolean living, String kind, int enchant, int tier, boolean value)
    {
        if (enchant < 0 || enchant >= MATRIX_ENCHANTS || tier < 0 || tier >= MATRIX_TIERS)
        {
            return;
        }

        getResourceMatrix(living, kind)[enchant][tier] = value;
    }

    public void setResourceRow(boolean living, String kind, int enchant, boolean value)
    {
        if (enchant < 0 || enchant >= MATRIX_ENCHANTS)
        {
            return;
        }

        boolean[][] matrix = getResourceMatrix(living, kind);
        Arrays.fill(matrix[enchant], value);
    }

    public void setResourceColumn(boolean living, String kind, int tier, boolean value)
    {
        if (tier < 0 || tier >= MATRIX_TIERS)
        {
            return;
        }

        boolean[][] matrix = getResourceMatrix(living, kind);

        for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
        {
            matrix[enchant][tier] = value;
        }
    }

    public void setResourceMatrixAll(boolean living, String kind, boolean value)
    {
        boolean[][] matrix = getResourceMatrix(living, kind);

        for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
        {
            Arrays.fill(matrix[enchant], value);
        }
    }

    /** 持久化某个类别的静态 + 活体矩阵。 */
    public void saveResourceMatrices(Context context, String kind)
    {
        String normalized = normalizeKind(kind);

        if (normalized == null)
        {
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("resStatic_" + normalized, packMatrix(getResourceMatrix(false, normalized)));
        editor.putString("resLiving_" + normalized, packMatrix(getResourceMatrix(true, normalized)));
        editor.apply();
    }

    public static void putBoolean(Context context, String key, boolean value)
    {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply();
    }

    public static void putInt(Context context, String key, int value)
    {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, value).apply();
    }

    public static void putFloat(Context context, String key, float value)
    {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putFloat(key, value).apply();
    }

    private RadarSettings()
    {
        // 先给矩阵一个“全开”的默认值：即使 init() 还没被调用（或在 Service 上下文里），渲染也不会 NPE
        for (String kind : RESOURCE_KIND_KEYS)
        {
            boolean[][] staticMatrix = new boolean[MATRIX_ENCHANTS][MATRIX_TIERS];
            boolean[][] livingMatrix = new boolean[MATRIX_ENCHANTS][MATRIX_TIERS];

            for (int enchant = 0; enchant < MATRIX_ENCHANTS; enchant++)
            {
                Arrays.fill(staticMatrix[enchant], true);
                Arrays.fill(livingMatrix[enchant], true);
            }

            staticResourceMatrix.put(kind, staticMatrix);
            livingResourceMatrix.put(kind, livingMatrix);
        }
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
