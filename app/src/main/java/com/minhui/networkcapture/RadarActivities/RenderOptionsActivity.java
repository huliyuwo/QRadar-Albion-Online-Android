package com.minhui.networkcapture.RadarActivities;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarView.RadarSettings;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 渲染选项（PC 式分组页）。
 *
 * <p>结构对齐 PC 版 OpenRadar 的设置页（{@code web/scripts/utils/SettingsRegistry.js} +
 * {@code internal/templates/pages/resources|enemies|players|radar.gohtml}）：
 * <ul>
 *   <li>雷达 / 显示：图标大小、等级角标样式、数量/距离标签、聚类（半径/最小数量）、
 *       距离环、HUD 统计、背景；</li>
 *   <li>资源：Wood/Rock/Fiber/Hide/Ore 每类折叠，下面分 Static（静态资源）与
 *       Living（活体资源怪），各自 T1~T8 × E0~E4 等级矩阵，另加 全开/全不选；</li>
 *   <li>敌人：Boss/MiniBoss/普通/无人机/迷雾/事件/守卫 + 活体资源主开关 + 等级/附魔矩阵 + 血条；</li>
 *   <li>玩家：显示、昵称、公会、联盟/阵营配色、被动/敌对、血量、距离、骑乘、最大数量、已发现面板；</li>
 *   <li>箱子：普通/优秀/稀有/传说。</li>
 * </ul>
 *
 * <p>所有开关直接写 {@link RadarSettings} 单例（渲染线程每帧读取，雷达下一帧就变化），
 * 同时写 SharedPreferences 持久化。竖屏单列滚动，横屏两列。
 */
public class RenderOptionsActivity extends AppCompatActivity
{
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFF90A4AE;
    private static final int COLOR_ACCENT = 0xFFF4840B;
    private static final int COLOR_SECTION_BG = 0xFF232323;
    private static final int COLOR_SECTION_HEADER = 0xFF2E2E2E;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;

    private RadarSettings settings;
    private final ArrayList<View> sectionViews = new ArrayList<>();
    private int dp;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.toolbar));
        setContentView(R.layout.activity_render_options);

        settings = RadarSettings.getInstance();
        // 与已持久化的开关对齐（渲染服务也在同一进程，改完立刻生效）
        settings.init(getApplicationContext());

        dp = Math.round(getResources().getDisplayMetrics().density);

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
        buildRadarSection();
        buildResourceSections();
        buildEnemySection();
        buildPlayerSection();
        buildChestSection();
    }

    /** 竖屏单列；横屏两列（左/右交替放分组，方便横屏一次看到更多）。 */
    private void layoutSections()
    {
        LinearLayout container = findViewById(R.id.optionsContainer);
        container.removeAllViews();

        boolean twoColumn = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        if (!twoColumn)
        {
            for (View section : sectionViews)
            {
                addToColumn(container, section);
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

        for (int i = 0; i < sectionViews.size(); i++)
        {
            addToColumn(i % 2 == 0 ? left : right, sectionViews.get(i));
        }

        columns.addView(left);
        columns.addView(right);
        container.addView(columns);
    }

    private void addToColumn(LinearLayout column, View section)
    {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 10 * dp;
        column.addView(section, params);
    }

    // ------------------------------------------------------------------
    // 雷达 / 显示
    // ------------------------------------------------------------------
    private void buildRadarSection()
    {
        LinearLayout content = createSection("雷达 / 显示", true);

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

        addCheckRow(content, "资源等级色块角标", "用类别色块 + T等级/附魔角标代替图标（PC 的 Tier badges）",
                settings.radarTierBadges, checked ->
                {
                    settings.radarTierBadges = checked;
                    RadarSettings.putBoolean(this, "radarTierBadges", checked);
                });

        addCheckRow(content, "资源数量标签", "在资源下方显示节点剩余数量",
                settings.radarResourceCount, checked ->
                {
                    settings.radarResourceCount = checked;
                    RadarSettings.putBoolean(this, "radarResourceCount", checked);
                });

        addCheckRow(content, "资源距离标签", "在资源下方显示到本机的码数",
                settings.radarResourceDistance, checked ->
                {
                    settings.radarResourceDistance = checked;
                    RadarSettings.putBoolean(this, "radarResourceDistance", checked);
                });

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
    // 资源（每类折叠：Static / Living 等级矩阵）
    // ------------------------------------------------------------------
    private void buildResourceSections()
    {
        for (int i = 0; i < RadarSettings.RESOURCE_KIND_KEYS.length; i++)
        {
            final String kind = RadarSettings.RESOURCE_KIND_KEYS[i];
            final String title = RadarSettings.RESOURCE_KIND_NAMES[i];
            LinearLayout content = createSection("资源 · " + title, false);

            addPlainLabel(content, "Static 静态资源（地图上的矿/树/草/石/皮点）", COLOR_TEXT, 13);
            addMatrixBlock(content, kind, false);

            addDivider(content);

            addPlainLabel(content, "Living 活体资源（资源怪 / 可采集生物）", COLOR_TEXT, 13);
            addMatrixBlock(content, kind, true);
        }

        LinearLayout content = createSection("资源 · 其他", true);

        addCheckRow(content, "钓鱼点", "显示 Fishing Zone",
                settings.harvestingZoneFishing, checked ->
                {
                    settings.harvestingZoneFishing = checked;
                    RadarSettings.putBoolean(this, "harvestingZoneFishing", checked);
                });

        addCheckRow(content, "显示血量条", "活体资源/怪物血条（同一开关）",
                settings.mobHp, checked ->
                {
                    settings.mobHp = checked;
                    RadarSettings.putBoolean(this, "mobHp", checked);
                });
    }

    /**
     * 等级矩阵：E0~E4 行 × T1~T8 列。
     * 点 T 列标题 = 整列开关，点 E 行标签 = 整行开关；底部 全开 / 全不选。
     */
    private void addMatrixBlock(LinearLayout parent, String kind, boolean living)
    {
        final boolean[][] matrix = settings.getResourceMatrix(living, kind);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView corner = new TextView(this);
        corner.setText("E\\T");
        corner.setTextColor(COLOR_TEXT_DIM);
        corner.setTextSize(10);
        corner.setWidth(34 * dp);
        header.addView(corner);

        for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
        {
            final int column = tier;
            TextView columnLabel = new TextView(this);
            columnLabel.setText("T" + (tier + 1));
            columnLabel.setTextColor(COLOR_TEXT);
            columnLabel.setTextSize(10);
            columnLabel.setGravity(Gravity.CENTER);
            columnLabel.setWidth(30 * dp);
            columnLabel.setOnClickListener(v ->
            {
                boolean next = !isColumnAllOn(matrix, column);
                settings.setResourceColumn(living, kind, column, next);
                settings.saveResourceMatrices(this, kind);
                rebuildSections();
            });
            header.addView(columnLabel);
        }

        parent.addView(header);

        for (int enchant = 0; enchant < RadarSettings.MATRIX_ENCHANTS; enchant++)
        {
            final int row = enchant;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView rowLabel = new TextView(this);
            rowLabel.setText("E" + enchant);
            rowLabel.setTextColor(COLOR_TEXT);
            rowLabel.setTextSize(10);
            rowLabel.setWidth(34 * dp);
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

            parent.addView(line);
        }

        addButtonRow(parent, new String[]{"全开", "全不选"},
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
    // 敌人
    // ------------------------------------------------------------------
    private void buildEnemySection()
    {
        LinearLayout content = createSection("敌人 / Enemies", true);

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
        addPlainLabel(content, "活体资源主开关（细分类别见「资源」分组）", COLOR_TEXT, 13);
        addPlainLabel(content, "Living 活体资源显示由「资源 · 类别 · Living 矩阵」控制", COLOR_TEXT_DIM, 11);

        addCheckRow(content, "可采集（Harvestable）", "矿/木/草/石资源怪",
                settings.mobHarvestable, checked ->
                {
                    settings.mobHarvestable = checked;
                    RadarSettings.putBoolean(this, "mobHarvestable", checked);
                });

        addCheckRow(content, "可剥皮（Skinnable）", "兽皮资源怪",
                settings.mobSkinnable, checked ->
                {
                    settings.mobSkinnable = checked;
                    RadarSettings.putBoolean(this, "mobSkinnable", checked);
                });

        addDivider(content);
        addPlainLabel(content, "等级 / 附魔矩阵（普通怪物，未知等级默认显示）", COLOR_TEXT, 13);

        LinearLayout tierLine = new LinearLayout(this);
        tierLine.setOrientation(LinearLayout.HORIZONTAL);
        tierLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView tierLabel = new TextView(this);
        tierLabel.setText("T");
        tierLabel.setTextColor(COLOR_TEXT_DIM);
        tierLabel.setTextSize(10);
        tierLabel.setWidth(34 * dp);
        tierLine.addView(tierLabel);

        for (int tier = 0; tier < 8; tier++)
        {
            final int index = tier;
            CheckBox cell = makeMatrixCell();
            cell.setChecked(settings.mobTiers[index]);
            cell.setOnCheckedChangeListener((buttonView, isChecked) ->
            {
                settings.mobTiers[index] = isChecked;
                RadarSettings.putBoolean(this, "mobTier" + (index + 1), isChecked);
            });
            tierLine.addView(cell);
        }

        content.addView(tierLine);

        LinearLayout enchantLine = new LinearLayout(this);
        enchantLine.setOrientation(LinearLayout.HORIZONTAL);
        enchantLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView enchantLabel = new TextView(this);
        enchantLabel.setText("E");
        enchantLabel.setTextColor(COLOR_TEXT_DIM);
        enchantLabel.setTextSize(10);
        enchantLabel.setWidth(34 * dp);
        enchantLine.addView(enchantLabel);

        for (int enchant = 0; enchant < 5; enchant++)
        {
            final int index = enchant;
            CheckBox cell = makeMatrixCell();
            cell.setChecked(settings.mobEnchants[index]);
            cell.setOnCheckedChangeListener((buttonView, isChecked) ->
            {
                settings.mobEnchants[index] = isChecked;
                RadarSettings.putBoolean(this, "mobEnchant" + index, isChecked);
            });
            enchantLine.addView(cell);
        }

        content.addView(enchantLine);

        addButtonRow(content, new String[]{"等级全开", "等级全不选", "附魔全开", "附魔全不选"},
                new Runnable[]{
                        () -> setMobTiers(true),
                        () -> setMobTiers(false),
                        () -> setMobEnchants(true),
                        () -> setMobEnchants(false)
                });

        addCheckRow(content, "显示怪物血量条", "", settings.mobHp, checked ->
        {
            settings.mobHp = checked;
            RadarSettings.putBoolean(this, "mobHp", checked);
        });
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
    // 玩家
    // ------------------------------------------------------------------
    private void buildPlayerSection()
    {
        LinearLayout content = createSection("玩家 / Players", false);

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
    // 箱子
    // ------------------------------------------------------------------
    private void buildChestSection()
    {
        LinearLayout content = createSection("箱子 / Chests", false);

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
    // 通用控件
    // ------------------------------------------------------------------
    private LinearLayout createSection(String title, boolean open)
    {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(COLOR_SECTION_BG);
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_SECTION_HEADER);
        header.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);

        TextView arrow = new TextView(this);
        arrow.setText(open ? "▾" : "▸");
        arrow.setTextColor(COLOR_ACCENT);
        arrow.setTextSize(14);
        arrow.setWidth(22 * dp);
        header.addView(arrow);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_ACCENT);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(10 * dp, 6 * dp, 10 * dp, 10 * dp);
        content.setVisibility(open ? View.VISIBLE : View.GONE);

        header.setOnClickListener(v ->
        {
            boolean visible = content.getVisibility() == View.VISIBLE;
            content.setVisibility(visible ? View.GONE : View.VISIBLE);
            arrow.setText(visible ? "▸" : "▾");
        });

        section.addView(header);
        section.addView(content);
        sectionViews.add(section);

        return content;
    }

    private void rebuildSections()
    {
        sectionViews.clear();
        buildSections();
        layoutSections();
    }

    private void addCheckRow(LinearLayout parent, String title, String subtitle, boolean checked,
                             Consumer<Boolean> onChange)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 4 * dp, 0, 4 * dp);

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
        titleView.setTextSize(14);
        texts.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty())
        {
            TextView subtitleView = new TextView(this);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(COLOR_TEXT_DIM);
            subtitleView.setTextSize(11);
            texts.addView(subtitleView);
        }

        row.addView(texts);

        View.OnClickListener toggle = v ->
        {
            checkBox.setChecked(!checkBox.isChecked());
            onChange.accept(checkBox.isChecked());
        };

        row.setOnClickListener(toggle);
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));

        parent.addView(row);
    }

    private void addIntSliderRow(LinearLayout parent, String title, int min, int max, int step,
                                 int value, String suffix, IntConsumer onChange)
    {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, 6 * dp, 0, 6 * dp);

        TextView label = new TextView(this);
        label.setText(title + "：" + value + suffix);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
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
        wrapper.setPadding(0, 6 * dp, 0, 6 * dp);

        int scale = Math.round(1f / step);
        int steps = Math.max(1, Math.round((max - min) / step));

        TextView label = new TextView(this);
        label.setText(title + "：" + formatFloat(value) + suffix);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
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
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private void addPlainLabel(LinearLayout parent, String text, int color, int sizeSp)
    {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setPadding(0, 6 * dp, 0, 2 * dp);
        parent.addView(view);
    }

    private void addDivider(LinearLayout parent)
    {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2));
        params.topMargin = 6 * dp;
        params.bottomMargin = 6 * dp;
        parent.addView(divider, params);
    }

    private CheckBox makeMatrixCell()
    {
        CheckBox cell = new CheckBox(this);
        cell.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_TEXT));
        cell.setPadding(0, 0, 0, 0);
        cell.setLayoutParams(new LinearLayout.LayoutParams(30 * dp, 34 * dp));
        return cell;
    }

    private void addButtonRow(LinearLayout parent, String[] labels, Runnable[] actions)
    {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4 * dp, 0, 2 * dp);

        for (int i = 0; i < labels.length; i++)
        {
            final Runnable action = actions[i];
            TextView button = new TextView(this);
            button.setText(labels[i]);
            button.setTextColor(Color.WHITE);
            button.setTextSize(12);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xFF3A3A3A);
            button.setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = 8 * dp;
            row.addView(button, params);

            button.setOnClickListener(v -> action.run());
        }

        parent.addView(row);
    }
}
