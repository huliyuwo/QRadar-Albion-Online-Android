package com.minhui.networkcapture.RadarActivities;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.minhui.networkcapture.MyApplication;
import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarView.RadarSettings;
import com.minhui.vpn.zone.ZoneState;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 渲染选项（PC 式分组页，旧 PvP/采集/怪物/箱子 设置页的唯一入口）。
 *
 * <p>页面按“使用频率 + 逻辑顺序”分组，横屏两列 / 竖屏单列，分组可折叠且折叠状态持久化：
 * <ol>
 *   <li>常用 / 高频：主城暂停、图标大小、视野缩放、资源等级色块角标；</li>
 *   <li>雷达 / 显示：数量标签、距离标签、聚类（半径/最小数量）、距离环、HUD、地图背景；</li>
 *   <li>资源：Wood / Hide / Ore / Rock / Fiber（按使用频率），每类一个类别总开关 +
 *       Static（静态节点）与 Living（资源怪）各一套 T1~T8 × E0~E4 矩阵；</li>
 *   <li>敌人：类别开关 → 等级/附魔矩阵 → 血条；</li>
 *   <li>玩家：显示 / 昵称 / 公会 / 联盟阵营 / 敌对被动 / 血量距离 / 骑乘 / 最大数 / 面板；</li>
 *   <li>箱子：普通 / 优秀 / 稀有 / 传说；</li>
 *   <li>底部：恢复本页默认值。</li>
 * </ol>
 *
 * <p>所有开关直接写 {@link RadarSettings} 单例（渲染线程每帧读取，下一帧即生效），
 * 同时写 SharedPreferences 持久化。竖屏单列滚动，横屏两列按分组均衡分配。
 */
public class RenderOptionsActivity extends AppCompatActivity
{
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFF90A4AE;
    private static final int COLOR_ACCENT = 0xFFF4840B;
    private static final int COLOR_SECTION_BG = 0xFF232323;
    private static final int COLOR_SECTION_HEADER = 0xFF2E2E2E;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;
    private static final int COLOR_MATRIX_BG = 0xFF1B1B1B;
    private static final int COLOR_MATRIX_HEADER = 0xFF303030;

    /** 分组展开状态持久化前缀（记住展开/收起）。 */
    private static final String PREF_SECTION_OPEN = "renderOptionsSectionOpen_";

    /** 资源分组顺序：按实际使用频率从高到低（Wood/Hide/Ore/Rock/Fiber）。 */
    private static final String[] RESOURCE_DISPLAY_ORDER = {"wood", "hide", "ore", "rock", "fiber"};

    /** 横屏两列的分组分配（按分组分配，保证两列展开后高度基本均衡）。 */
    /** “恢复默认”会清掉的渲染相关 SharedPreferences key（不包含订阅/mihomo/悬浮窗布局）。 */
    private static final String[] RESET_KEYS = {
            // 常用
            "radarIconScale", "radarScaleBar", "radarTierBadges", MyApplication.PREF_CITY_PAUSE_MODE,
            // 雷达 / 显示
            "radarResourceCount", "radarResourceDistance", "radarClusters", "radarClusterRadius",
            "radarClusterMinSize", "radarShowHud", "radarShowRings", "radarShowBackground",
            // 资源（旧采集页遗留 key 一并清掉，避免矩阵默认值继续被旧开关过滤）
            "harvestingSize", "harvestingZoneFishing",
            "harvestingWood", "harvestingRock", "harvestingFiber", "harvestingHide", "harvestingOre",
            "harvestingTier1", "harvestingTier2", "harvestingTier3", "harvestingTier4",
            "harvestingTier5", "harvestingTier6", "harvestingTier7", "harvestingTier8",
            "harvestingEnchant0", "harvestingEnchant1", "harvestingEnchant2",
            "harvestingEnchant3", "harvestingEnchant4",
            // 敌人
            "mobHp", "mobHarvestable", "mobSkinnable", "mobOther", "mobBoss", "mobMiniBoss",
            "mobEnemy", "mobDrone", "mobMist", "mobEvents", "mobGuard",
            "mobTier1", "mobTier2", "mobTier3", "mobTier4",
            "mobTier5", "mobTier6", "mobTier7", "mobTier8",
            "mobEnchant0", "mobEnchant1", "mobEnchant2", "mobEnchant3", "mobEnchant4",
            // 玩家
            "playerDot", "playerNickname", "playerHealth", "playerMounted", "playerDistance",
            "playerSound", "playerGuildName", "playerAllianceColor", "playerFactionColor",
            "playerHostile", "playerPassive", "playerPanel", "playerMaxDisplay",
            // 箱子
            "chestStandard", "chestUncommon", "chestRare", "chestLegendary"
    };

    private RadarSettings settings;
    private ScrollView optionsScroll;
    private LinearLayout optionsContainer;
    private final ArrayList<Section> sections = new ArrayList<>();
    private float density;

    /** 一个可折叠分组。 */
    private static final class Section
    {
        final String id;
        final String title;
        final String subtitle;
        final boolean leftColumn;
        final int weight;
        final LinearLayout root;
        final LinearLayout content;
        final TextView arrow;
        boolean open;

        Section(String id, String title, String subtitle, boolean leftColumn, int weight,
                LinearLayout root, LinearLayout content, TextView arrow, boolean open)
        {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.leftColumn = leftColumn;
            this.weight = weight;
            this.root = root;
            this.content = content;
            this.arrow = arrow;
            this.open = open;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.toolbar));
        setContentView(R.layout.activity_render_options);

        settings = RadarSettings.getInstance();
        // 与已持久化的开关对齐（渲染服务也在同一进程，改完立刻生效）
        settings.init(getApplicationContext());

        density = getResources().getDisplayMetrics().density;

        optionsScroll = findViewById(R.id.optionsScroll);
        optionsContainer = findViewById(R.id.optionsContainer);

        TextView toolbarTitle = findViewById(R.id.toolbarTitle);
        toolbarTitle.setText("渲染选项");

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

        buildSections();
        layoutSections();
    }

    // ------------------------------------------------------------------
    // 页面组装
    // ------------------------------------------------------------------
    private void buildSections()
    {
        buildFrequentSection();
        buildRadarSection();

        for (String kind : RESOURCE_DISPLAY_ORDER)
        {
            buildResourceSection(kind);
        }

        buildResourceOtherSection();
        buildEnemySection();
        buildPlayerSection();
        buildChestSection();
        buildResetSection();
    }

    /** 竖屏单列；横屏两列（按分组分配，左/右列展开后高度基本均衡）。 */
    private void layoutSections()
    {
        optionsContainer.removeAllViews();

        boolean twoColumn = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        if (!twoColumn)
        {
            for (Section section : sections)
            {
                addSection(optionsContainer, section);
            }

            return;
        }

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        for (Section section : sections)
        {
            addSection(section.leftColumn ? left : right, section);
        }

        columns.addView(left);
        columns.addView(right);
        optionsContainer.addView(columns);
    }

    private void addSection(LinearLayout column, Section section)
    {
        if (section.root.getParent() instanceof ViewGroup)
        {
            ((ViewGroup) section.root.getParent()).removeView(section.root);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = px(6);

        if (column.getOrientation() == LinearLayout.HORIZONTAL)
        {
            // 横屏两列：左右列之间留一点间距，避免误触到隔壁分组
            params.leftMargin = section.leftColumn ? 0 : px(5);
            params.rightMargin = section.leftColumn ? px(5) : 0;
        }

        column.addView(section.root, params);
    }

    // ------------------------------------------------------------------
    // 1. 常用 / 高频
    // ------------------------------------------------------------------
    private void buildFrequentSection()
    {
        // 竖屏默认展开“常用”（最高频）；横屏默认全部收起，整页像目录一样一屏看完，展开状态会被记住
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;

        LinearLayout content = newSection("frequent", "常用 / 高频", "主城暂停 · 大小 / 缩放 · 等级角标",
                portrait, true, 8);

        // 主城暂停（进入安全城时不解析/不渲染，离开自动恢复）
        addChoiceRow(content, "主城暂停", "进入主城/安全城时暂停雷达，离开后自动恢复",
                new String[]{"关闭", "完全不解析不渲染", "仅暂停渲染"},
                settings.radarCityPauseMode, mode ->
                {
                    settings.radarCityPauseMode = mode;
                    RadarSettings.putInt(this, MyApplication.PREF_CITY_PAUSE_MODE, mode);
                    // 解析线程 / 渲染线程直接读 ZoneState，改完立刻生效
                    ZoneState.pauseMode = mode;
                });

        addDivider(content);

        addFloatSliderRow(content, "图标大小", 0.5f, 2.0f, 0.1f, settings.radarIconScale, "x", value ->
        {
            settings.radarIconScale = value;
            RadarSettings.putFloat(this, "radarIconScale", value);
        });

        addFloatSliderRow(content, "视野缩放", 0.3f, 3.0f, 0.1f, settings.radarScaleBar, "x", value ->
        {
            settings.radarScaleBar = value;
            RadarSettings.putFloat(this, "radarScaleBar", value);
        });

        addDivider(content);

        addCheckRow(content, "资源等级色块角标", "用类别色块 + T等级/附魔角标代替图标（PC 的 Tier badges）",
                settings.radarTierBadges, checked ->
                {
                    settings.radarTierBadges = checked;
                    RadarSettings.putBoolean(this, "radarTierBadges", checked);
                });
    }

    // ------------------------------------------------------------------
    // 2. 雷达 / 显示
    // ------------------------------------------------------------------
    private void buildRadarSection()
    {
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;

        LinearLayout content = newSection("radar", "雷达 / 显示", "数量 / 距离 / 聚类 / 参考线",
                portrait, true, 12);

        addCheckRow(content, "资源数量标签", "在资源下方显示节点剩余数量（旧采集页的 Size 开关）",
                settings.radarResourceCount, checked ->
                {
                    settings.radarResourceCount = checked;
                    // 旧采集页的 harvestingSize 与这里是同一个效果，一起写，避免旧开关残留导致行为不一致
                    settings.harvestingSize = checked;
                    RadarSettings.putBoolean(this, "radarResourceCount", checked);
                    RadarSettings.putBoolean(this, "harvestingSize", checked);
                });

        addCheckRow(content, "资源距离标签", "在资源下方显示到本机的码数",
                settings.radarResourceDistance, checked ->
                {
                    settings.radarResourceDistance = checked;
                    RadarSettings.putBoolean(this, "radarResourceDistance", checked);
                });

        addDivider(content);

        addCheckRow(content, "资源聚类", "把相邻同类资源合并成一个点并显示数量",
                settings.radarClusters, checked ->
                {
                    settings.radarClusters = checked;
                    RadarSettings.putBoolean(this, "radarClusters", checked);
                });

        addIntSliderRow(content, "聚类半径", 10, 100, 5, settings.radarClusterRadius, " 码", value ->
        {
            settings.radarClusterRadius = value;
            RadarSettings.putInt(this, "radarClusterRadius", value);
        });

        addIntSliderRow(content, "聚类最小数量", 2, 10, 1, settings.radarClusterMinSize, " 个", value ->
        {
            settings.radarClusterMinSize = value;
            RadarSettings.putInt(this, "radarClusterMinSize", value);
        });

        addDivider(content);

        addCheckRow(content, "距离环", "以本机为中心的 10/20/40 码参考环",
                settings.radarShowRings, checked ->
                {
                    settings.radarShowRings = checked;
                    RadarSettings.putBoolean(this, "radarShowRings", checked);
                });

        addCheckRow(content, "HUD 统计", "左下角 资源/怪物/玩家 计数",
                settings.radarShowHud, checked ->
                {
                    settings.radarShowHud = checked;
                    RadarSettings.putBoolean(this, "radarShowHud", checked);
                });

        addCheckRow(content, "地图背景", "雷达底色（关闭后完全透明）",
                settings.radarShowBackground, checked ->
                {
                    settings.radarShowBackground = checked;
                    RadarSettings.putBoolean(this, "radarShowBackground", checked);
                });
    }

    // ------------------------------------------------------------------
    // 3. 资源（每类：类别总开关 + Static / Living 等级矩阵）
    // ------------------------------------------------------------------
    private void buildResourceSection(String kind)
    {
        boolean leftColumn = RESOURCE_DISPLAY_ORDER[0].equals(kind)
                || RESOURCE_DISPLAY_ORDER[1].equals(kind)
                || RESOURCE_DISPLAY_ORDER[2].equals(kind);

        String title = "资源 · " + resourceDisplayName(kind);
        LinearLayout content = newSection("res_" + kind, title, "类别总开关 + Static / Living 矩阵",
                false, leftColumn, 20);

        addCheckRow(content, "显示该类资源（总开关）", "关闭后该类别 Static + Living 全部隐藏",
                settings.isResourceKindEnabled(kind), checked ->
                        settings.setResourceKindEnabled(this, kind, checked));

        addDivider(content);

        addPlainLabel(content, "Static 静态资源（地图上的矿/树/草/石/皮点）", COLOR_TEXT, 13);
        addMatrixBlock(content, kind, false);

        addDivider(content);

        addPlainLabel(content, "Living 活体资源（资源怪 / 可采集生物，雷达上带金框）", COLOR_TEXT, 13);
        addMatrixBlock(content, kind, true);
    }

    private String resourceDisplayName(String kind)
    {
        for (int i = 0; i < RadarSettings.RESOURCE_KIND_KEYS.length; i++)
        {
            if (RadarSettings.RESOURCE_KIND_KEYS[i].equals(kind))
            {
                return RadarSettings.RESOURCE_KIND_NAMES[i];
            }
        }

        return kind;
    }

    /**
     * 等级矩阵：E0~E4 行 × T1~T8 列。
     * 点 T 列标题 = 整列开关，点 E 行标签 = 整行开关；底部 全开 / 全不选。
     */
    private void addMatrixBlock(LinearLayout parent, String kind, boolean living)
    {
        final boolean[][] matrix = settings.getResourceMatrix(living, kind);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundColor(COLOR_MATRIX_BG);
        block.setPadding(px(6), px(6), px(6), px(6));
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.topMargin = px(4);
        parent.addView(block, blockParams);

        // 表头：T1 ~ T8
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_MATRIX_HEADER);

        TextView corner = new TextView(this);
        corner.setText("T / E");
        corner.setTextColor(COLOR_TEXT_DIM);
        corner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        corner.setWidth(px(40));
        header.addView(corner);

        for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
        {
            final int column = tier;
            boolean columnOn = isColumnAllOn(matrix, column);

            TextView columnLabel = new TextView(this);
            columnLabel.setText("T" + (tier + 1));
            columnLabel.setTextColor(columnOn ? COLOR_ACCENT : COLOR_TEXT_DIM);
            columnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            columnLabel.setGravity(Gravity.CENTER);
            columnLabel.setLayoutParams(new LinearLayout.LayoutParams(px(32), px(26)));
            columnLabel.setOnClickListener(v ->
            {
                boolean next = !isColumnAllOn(matrix, column);
                settings.setResourceColumn(living, kind, column, next);
                settings.saveResourceMatrices(this, kind);
                rebuildSections();
            });
            header.addView(columnLabel);
        }

        block.addView(header);

        for (int enchant = 0; enchant < RadarSettings.MATRIX_ENCHANTS; enchant++)
        {
            final int row = enchant;
            boolean rowOn = isRowAllOn(matrix, row);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView rowLabel = new TextView(this);
            rowLabel.setText("E" + enchant);
            rowLabel.setTextColor(rowOn ? COLOR_ACCENT : COLOR_TEXT_DIM);
            rowLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            rowLabel.setGravity(Gravity.CENTER_VERTICAL);
            rowLabel.setWidth(px(40));
            rowLabel.setOnClickListener(v ->
            {
                boolean next = !isRowAllOn(matrix, row);
                settings.setResourceRow(living, kind, row, next);
                settings.saveResourceMatrices(this, kind);
                rebuildSections();
            });
            line.addView(rowLabel);

            for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
            {
                final int column = tier;
                CheckBox cell = makeMatrixCell();
                cell.setChecked(matrix[enchant][tier]);
                cell.setOnCheckedChangeListener((buttonView, isChecked) ->
                {
                    settings.setResourceCell(living, kind, row, column, isChecked);
                    settings.saveResourceMatrices(this, kind);
                });
                line.addView(cell);
            }

            block.addView(line);
        }

        addButtonRow(block, new String[]{"全开", "全不选"},
                new Runnable[]{
                        () ->
                        {
                            settings.setResourceMatrixAll(living, kind, true);
                            settings.saveResourceMatrices(this, kind);
                            rebuildSections();
                        },
                        () ->
                        {
                            settings.setResourceMatrixAll(living, kind, false);
                            settings.saveResourceMatrices(this, kind);
                            rebuildSections();
                        }
                });

        TextView hint = new TextView(this);
        hint.setText("点 T 列标题 / E 行标签 = 整列 / 整行开关");
        hint.setTextColor(COLOR_TEXT_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(0, px(2), 0, 0);
        block.addView(hint);
    }

    private boolean isColumnAllOn(boolean[][] matrix, int column)
    {
        for (int enchant = 0; enchant < RadarSettings.MATRIX_ENCHANTS; enchant++)
        {
            if (!matrix[enchant][column])
            {
                return false;
            }
        }

        return true;
    }

    private boolean isRowAllOn(boolean[][] matrix, int row)
    {
        for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
        {
            if (!matrix[row][tier])
            {
                return false;
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // 3.1 资源 · 其他
    // ------------------------------------------------------------------
    private void buildResourceOtherSection()
    {
        LinearLayout content = newSection("res_other", "资源 · 其他", "钓鱼点 / 血量条", false, false, 3);

        addCheckRow(content, "钓鱼点", "显示 Fishing Zone",
                settings.harvestingZoneFishing, checked ->
                {
                    settings.harvestingZoneFishing = checked;
                    settings.rebuildHarvestableTypes();
                    RadarSettings.putBoolean(this, "harvestingZoneFishing", checked);
                });

        addPlainLabel(content, "怪物 / 活体资源的血量条统一在「敌人」分组底部开关。", COLOR_TEXT_DIM, 11);
    }

    // ------------------------------------------------------------------
    // 4. 敌人：类别开关 → 等级矩阵 → 血条
    // ------------------------------------------------------------------
    private void buildEnemySection()
    {
        LinearLayout content = newSection("enemy", "敌人 / Enemies", "类别 → 矩阵 → 血条", false, false, 16);

        addPlainLabel(content, "类别开关", COLOR_TEXT, 13);

        addCheckRow(content, "Boss", "红色", settings.mobBoss, checked ->
        {
            settings.mobBoss = checked;
            RadarSettings.putBoolean(this, "mobBoss", checked);
        });

        addCheckRow(content, "MiniBoss", "橙色", settings.mobMiniBoss, checked ->
        {
            settings.mobMiniBoss = checked;
            RadarSettings.putBoolean(this, "mobMiniBoss", checked);
        });

        addCheckRow(content, "普通怪 / 附魔怪", "绿色 / 紫色", settings.mobEnemy, checked ->
        {
            settings.mobEnemy = checked;
            RadarSettings.putBoolean(this, "mobEnemy", checked);
        });

        addCheckRow(content, "无人机", "青色", settings.mobDrone, checked ->
        {
            settings.mobDrone = checked;
            RadarSettings.putBoolean(this, "mobDrone", checked);
        });

        addCheckRow(content, "迷雾 / 传送门", "粉色 / 紫色", settings.mobMist, checked ->
        {
            settings.mobMist = checked;
            RadarSettings.putBoolean(this, "mobMist", checked);
        });

        addCheckRow(content, "事件怪 / 宝箱怪", "白色", settings.mobEvents, checked ->
        {
            settings.mobEvents = checked;
            RadarSettings.putBoolean(this, "mobEvents", checked);
        });

        addCheckRow(content, "守卫", "金色", settings.mobGuard, checked ->
        {
            settings.mobGuard = checked;
            RadarSettings.putBoolean(this, "mobGuard", checked);
        });

        addCheckRow(content, "其他 / 未识别", "兜底开关", settings.mobOther, checked ->
        {
            settings.mobOther = checked;
            RadarSettings.putBoolean(this, "mobOther", checked);
        });

        addDivider(content);
        addPlainLabel(content, "活体资源主开关", COLOR_TEXT, 13);
        addPlainLabel(content, "细分类别由「资源 · 类别 · Living 矩阵」控制", COLOR_TEXT_DIM, 11);

        addCheckRow(content, "可采集（Harvestable）", "矿/木/草/石资源怪", settings.mobHarvestable, checked ->
        {
            settings.mobHarvestable = checked;
            RadarSettings.putBoolean(this, "mobHarvestable", checked);
        });

        addCheckRow(content, "可剥皮（Skinnable）", "兽皮资源怪", settings.mobSkinnable, checked ->
        {
            settings.mobSkinnable = checked;
            RadarSettings.putBoolean(this, "mobSkinnable", checked);
        });

        addDivider(content);
        addPlainLabel(content, "等级 / 附魔矩阵（未知等级默认显示）", COLOR_TEXT, 13);
        addMobMatrixBlock(content);

        addDivider(content);

        addCheckRow(content, "显示血量条", "怪物 / 活体资源血条（同一开关）", settings.mobHp, checked ->
        {
            settings.mobHp = checked;
            RadarSettings.putBoolean(this, "mobHp", checked);
        });
    }

    /** 怪物等级/附魔矩阵：T1~T8 一排、E0~E4 一排（表头清晰）。 */
    private void addMobMatrixBlock(LinearLayout parent)
    {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundColor(COLOR_MATRIX_BG);
        block.setPadding(px(6), px(6), px(6), px(6));
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockParams.topMargin = px(6);
        parent.addView(block, blockParams);

        addMobMatrixHeader(block, "等级", "T", RadarSettings.MATRIX_TIERS);
        addMobMatrixLine(block, "T", RadarSettings.MATRIX_TIERS, true);
        addMobMatrixHeader(block, "附魔", "E", RadarSettings.MATRIX_ENCHANTS);
        addMobMatrixLine(block, "E", RadarSettings.MATRIX_ENCHANTS, false);

        addButtonRow(block, new String[]{"等级全开", "等级全不选", "附魔全开", "附魔全不选"},
                new Runnable[]{
                        () -> setMobTiers(true),
                        () -> setMobTiers(false),
                        () -> setMobEnchants(true),
                        () -> setMobEnchants(false)
                });
    }

    private void addMobMatrixHeader(LinearLayout block, String label, String prefix, int count)
    {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_MATRIX_HEADER);

        TextView corner = new TextView(this);
        corner.setText(label);
        corner.setTextColor(COLOR_TEXT_DIM);
        corner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        corner.setGravity(Gravity.CENTER_VERTICAL);
        corner.setWidth(px(40));
        header.addView(corner);

        for (int i = 0; i < count; i++)
        {
            final int index = i;
            boolean on = "T".equals(prefix) ? settings.mobTiers[index] : settings.mobEnchants[index];

            TextView columnLabel = new TextView(this);
            columnLabel.setText(prefix + ("T".equals(prefix) ? (index + 1) : index));
            columnLabel.setTextColor(on ? COLOR_ACCENT : COLOR_TEXT_DIM);
            columnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            columnLabel.setGravity(Gravity.CENTER);
            columnLabel.setLayoutParams(new LinearLayout.LayoutParams(px(32), px(26)));
            columnLabel.setOnClickListener(v ->
            {
                if ("T".equals(prefix))
                {
                    settings.mobTiers[index] = !settings.mobTiers[index];
                    RadarSettings.putBoolean(this, "mobTier" + (index + 1), settings.mobTiers[index]);
                }
                else
                {
                    settings.mobEnchants[index] = !settings.mobEnchants[index];
                    RadarSettings.putBoolean(this, "mobEnchant" + index, settings.mobEnchants[index]);
                }

                rebuildSections();
            });
            header.addView(columnLabel);
        }

        block.addView(header);
    }

    private void addMobMatrixLine(LinearLayout block, String label, int count, boolean tiers)
    {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView rowLabel = new TextView(this);
        rowLabel.setText(label);
        rowLabel.setTextColor(COLOR_TEXT_DIM);
        rowLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        rowLabel.setGravity(Gravity.CENTER_VERTICAL);
        rowLabel.setWidth(px(40));
        line.addView(rowLabel);

        for (int i = 0; i < count; i++)
        {
            final int index = i;
            CheckBox cell = makeMatrixCell();
            cell.setChecked(tiers ? settings.mobTiers[index] : settings.mobEnchants[index]);
            cell.setOnCheckedChangeListener((buttonView, isChecked) ->
            {
                if (tiers)
                {
                    settings.mobTiers[index] = isChecked;
                    RadarSettings.putBoolean(this, "mobTier" + (index + 1), isChecked);
                }
                else
                {
                    settings.mobEnchants[index] = isChecked;
                    RadarSettings.putBoolean(this, "mobEnchant" + index, isChecked);
                }
            });
            line.addView(cell);
        }

        block.addView(line);
    }

    private void setMobTiers(boolean value)
    {
        for (int i = 0; i < settings.mobTiers.length; i++)
        {
            settings.mobTiers[i] = value;
            RadarSettings.putBoolean(this, "mobTier" + (i + 1), value);
        }

        rebuildSections();
    }

    private void setMobEnchants(boolean value)
    {
        for (int i = 0; i < settings.mobEnchants.length; i++)
        {
            settings.mobEnchants[i] = value;
            RadarSettings.putBoolean(this, "mobEnchant" + i, value);
        }

        rebuildSections();
    }

    // ------------------------------------------------------------------
    // 5. 玩家
    // ------------------------------------------------------------------
    private void buildPlayerSection()
    {
        LinearLayout content = newSection("player", "玩家 / Players", "显示 / 配色 / 数量", false, false, 11);

        addCheckRow(content, "显示玩家", "总开关", settings.playerDot, checked ->
        {
            settings.playerDot = checked;
            RadarSettings.putBoolean(this, "playerDot", checked);
        });

        addCheckRow(content, "昵称", "", settings.playerNickname, checked ->
        {
            settings.playerNickname = checked;
            RadarSettings.putBoolean(this, "playerNickname", checked);
        });

        addCheckRow(content, "公会名", "", settings.playerGuildName, checked ->
        {
            settings.playerGuildName = checked;
            RadarSettings.putBoolean(this, "playerGuildName", checked);
        });

        addDivider(content);

        addCheckRow(content, "公会 / 联盟配色", "同公会绿、同联盟紫", settings.playerAllianceColor, checked ->
        {
            settings.playerAllianceColor = checked;
            RadarSettings.putBoolean(this, "playerAllianceColor", checked);
        });

        addCheckRow(content, "阵营配色", "阵营玩家橙色", settings.playerFactionColor, checked ->
        {
            settings.playerFactionColor = checked;
            RadarSettings.putBoolean(this, "playerFactionColor", checked);
        });

        addCheckRow(content, "敌对玩家", "红名/可攻击玩家（红色）", settings.playerHostile, checked ->
        {
            settings.playerHostile = checked;
            RadarSettings.putBoolean(this, "playerHostile", checked);
        });

        addCheckRow(content, "被动 / 中立玩家", "蓝色", settings.playerPassive, checked ->
        {
            settings.playerPassive = checked;
            RadarSettings.putBoolean(this, "playerPassive", checked);
        });

        addDivider(content);

        addCheckRow(content, "血量条", "", settings.playerHealth, checked ->
        {
            settings.playerHealth = checked;
            RadarSettings.putBoolean(this, "playerHealth", checked);
        });

        addCheckRow(content, "距离", "", settings.playerDistance, checked ->
        {
            settings.playerDistance = checked;
            RadarSettings.putBoolean(this, "playerDistance", checked);
        });

        addCheckRow(content, "坐骑标记 M", "", settings.playerMounted, checked ->
        {
            settings.playerMounted = checked;
            RadarSettings.putBoolean(this, "playerMounted", checked);
        });

        addCheckRow(content, "已发现玩家面板", "坐标未解密时也能在右上角看到名字/阵营",
                settings.playerPanel, checked ->
                {
                    settings.playerPanel = checked;
                    RadarSettings.putBoolean(this, "playerPanel", checked);
                });

        addIntSliderRow(content, "最大显示数", 5, 100, 5, settings.playerMaxDisplay, " 个", value ->
        {
            settings.playerMaxDisplay = value;
            RadarSettings.putInt(this, "playerMaxDisplay", value);
        });
    }

    // ------------------------------------------------------------------
    // 6. 箱子
    // ------------------------------------------------------------------
    private void buildChestSection()
    {
        LinearLayout content = newSection("chest", "箱子 / Chests", "品质过滤", false, false, 5);

        addChestRow(content, "普通箱子", "standard", "chestStandard");
        addChestRow(content, "优秀箱子", "uncommon", "chestUncommon");
        addChestRow(content, "稀有箱子", "rare", "chestRare");
        addChestRow(content, "传说箱子", "legendary", "chestLegendary");
    }

    private void addChestRow(LinearLayout parent, String title, String chestKey, String prefKey)
    {
        Boolean stored = settings.chests.get(chestKey);
        boolean checked = stored != null && stored;

        addCheckRow(parent, title, "", checked, isChecked ->
        {
            settings.chests.put(chestKey, isChecked);
            RadarSettings.putBoolean(this, prefKey, isChecked);
        });
    }

    // ------------------------------------------------------------------
    // 7. 底部：恢复默认
    // ------------------------------------------------------------------
    private void buildResetSection()
    {
        LinearLayout content = newSection("reset", "其他 / 恢复默认", "只重置本页渲染项", false, false, 2);

        addPlainLabel(content, "把所有渲染开关恢复默认值；不会重置订阅、mihomo、悬浮窗位置与字号。",
                COLOR_TEXT_DIM, 11);

        addButtonRow(content, new String[]{"恢复渲染默认值"}, new Runnable[]{this::confirmResetDefaults});
    }

    private void confirmResetDefaults()
    {
        new AlertDialog.Builder(this)
                .setTitle("恢复渲染默认值")
                .setMessage("将本页所有渲染开关恢复默认，确定继续？")
                .setPositiveButton("恢复", (dialog, which) -> resetRenderDefaults())
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetRenderDefaults()
    {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();

        for (String key : RESET_KEYS)
        {
            editor.remove(key);
        }

        for (String kind : RadarSettings.RESOURCE_KIND_KEYS)
        {
            editor.remove("resStatic_" + kind);
            editor.remove("resLiving_" + kind);
        }

        for (Section section : sections)
        {
            editor.remove(PREF_SECTION_OPEN + section.id);
        }

        editor.apply();

        // 重新从（已清空的）持久化状态加载默认值，并重建页面
        settings.init(getApplicationContext());
        sections.clear();
        buildSections();
        layoutSections();

        Toast.makeText(this, "已恢复渲染默认值", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // 分组容器 / 折叠
    // ------------------------------------------------------------------
    private LinearLayout newSection(String id, String title, String subtitle,
                                    boolean defaultOpen, boolean leftColumn, int weight)
    {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean open = preferences.getBoolean(PREF_SECTION_OPEN + id, defaultOpen);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(COLOR_SECTION_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_SECTION_HEADER);
        header.setPadding(px(12), px(7), px(12), px(7));

        TextView arrow = new TextView(this);
        arrow.setText(open ? "▾" : "▸");
        arrow.setTextColor(COLOR_ACCENT);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        arrow.setWidth(px(22));
        header.addView(arrow);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_ACCENT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty())
        {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(COLOR_TEXT_DIM);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setGravity(Gravity.END);

            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            subtitleParams.leftMargin = px(8);
            header.addView(subtitleView, subtitleParams);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(10), px(4), px(10), px(10));
        content.setVisibility(open ? View.VISIBLE : View.GONE);

        section.addView(header);
        section.addView(content);

        final Section sectionModel = new Section(id, title, subtitle, leftColumn, weight,
                section, content, arrow, open);
        header.setOnClickListener(v -> toggleSection(sectionModel));
        sections.add(sectionModel);

        return content;
    }

    private void toggleSection(Section section)
    {
        section.open = !section.open;
        section.content.setVisibility(section.open ? View.VISIBLE : View.GONE);
        section.arrow.setText(section.open ? "▾" : "▸");

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(PREF_SECTION_OPEN + section.id, section.open)
                .apply();
    }

    /** 重建所有分组（矩阵整体操作后刷新勾选状态），并保持滚动位置不跳。 */
    private void rebuildSections()
    {
        final int scrollY = optionsScroll != null ? optionsScroll.getScrollY() : 0;

        sections.clear();
        buildSections();
        layoutSections();

        if (optionsScroll != null)
        {
            optionsScroll.post(() -> optionsScroll.scrollTo(0, scrollY));
        }
    }

    // ------------------------------------------------------------------
    // 通用控件
    // ------------------------------------------------------------------
    private void addChoiceRow(LinearLayout parent, String title, String subtitle, String[] labels,
                              int selected, IntConsumer onChange)
    {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, px(8), 0, px(4));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        wrapper.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty())
        {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(COLOR_TEXT_DIM);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            wrapper.addView(subtitleView);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(6), 0, 0);

        for (int i = 0; i < labels.length; i++)
        {
            final int index = i;
            final boolean active = i == selected;

            TextView button = new TextView(this);
            button.setText(labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(active ? Color.WHITE : COLOR_TEXT_DIM);
            button.setBackgroundColor(active ? COLOR_ACCENT : 0xFF3A3A3A);
            button.setPadding(px(10), px(7), px(10), px(7));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = px(8);
            row.addView(button, params);

            button.setOnClickListener(v ->
            {
                if (index == selected)
                {
                    return;
                }

                onChange.accept(index);
                rebuildSections();
            });
        }

        wrapper.addView(row);
        parent.addView(wrapper);
    }

    private void addCheckRow(LinearLayout parent, String title, String subtitle, boolean checked,
                             Consumer<Boolean> onChange)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(4), 0, px(4));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setChecked(checked);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        checkBox.setPadding(0, 0, 0, 0);
        row.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        texts.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty())
        {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(COLOR_TEXT_DIM);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            texts.addView(subtitleView);
        }

        row.addView(texts);

        // 点整行 = 切开关；只在这里改 CheckBox，避免 onChange 被触发两次
        row.setOnClickListener(v -> checkBox.toggle());
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));

        parent.addView(row);
    }

    private void addIntSliderRow(LinearLayout parent, String title, int min, int max, int step,
                                 int value, String suffix, IntConsumer onChange)
    {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, px(6), 0, px(6));

        TextView label = new TextView(this);
        label.setText(title + "：" + value + suffix);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        wrapper.addView(label);

        SeekBar seekBar = new SeekBar(this);
        int steps = Math.max(1, (max - min) / step);
        seekBar.setMax(steps);
        seekBar.setProgress(Math.max(0, Math.min(steps, (value - min) / step)));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser)
            {
                int current = min + progress * step;
                label.setText(title + "：" + current + suffix);

                if (fromUser)
                {
                    onChange.accept(current);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar)
            {
            }
        });
        wrapper.addView(seekBar);
        parent.addView(wrapper);
    }

    private void addFloatSliderRow(LinearLayout parent, String title, float min, float max, float step,
                                   float value, String suffix, Consumer<Float> onChange)
    {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, px(6), 0, px(6));

        int scale = Math.round(1f / step);
        int steps = Math.max(1, Math.round((max - min) / step));

        TextView label = new TextView(this);
        label.setText(title + "：" + formatFloat(value) + suffix);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        wrapper.addView(label);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(steps);
        seekBar.setProgress(Math.max(0, Math.min(steps, Math.round((value - min) / step))));
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser)
            {
                float current = min + progress * step;
                current = Math.round(current * scale) / (float) scale;
                label.setText(title + "：" + formatFloat(current) + suffix);

                if (fromUser)
                {
                    onChange.accept(current);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar)
            {
            }
        });
        wrapper.addView(seekBar);
        parent.addView(wrapper);
    }

    private static String formatFloat(float value)
    {
        return String.format(Locale.US, "%.1f", value);
    }

    /** dp -> px（用真实 density 换算；不能用 Math.round(density) 当倍率，高密度屏会整体偏大导致矩阵被裁）。 */
    private int px(float dpValue)
    {
        return Math.round(dpValue * density);
    }

    private void addPlainLabel(LinearLayout parent, String text, int color, int sizeSp)
    {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setPadding(0, px(6), 0, px(2));
        parent.addView(view);
    }

    private void addDivider(LinearLayout parent)
    {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(0.5f)));
        params.topMargin = px(6);
        params.bottomMargin = px(6);
        parent.addView(divider, params);
    }

    private CheckBox makeMatrixCell()
    {
        CheckBox cell = new CheckBox(this);
        cell.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_TEXT));
        cell.setPadding(0, 0, 0, 0);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(px(32), px(36)));
        return cell;
    }

    private void addButtonRow(LinearLayout parent, String[] labels, Runnable[] actions)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(4), 0, px(2));

        for (int i = 0; i < labels.length; i++)
        {
            final Runnable action = actions[i];
            TextView button = new TextView(this);
            button.setText(labels[i]);
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xFF3A3A3A);
            button.setPadding(px(10), px(7), px(10), px(7));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = px(8);
            row.addView(button, params);

            button.setOnClickListener(v -> action.run());
        }

        parent.addView(row);
    }
}
