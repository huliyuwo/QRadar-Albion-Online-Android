package com.minhui.networkcapture.RadarActivities;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.minhui.networkcapture.MyApplication;
import com.minhui.networkcapture.R;
import com.minhui.networkcapture.RadarView.RadarSettings;
import com.minhui.vpn.zone.ZoneState;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 悬浮窗内嵌「快速渲染选项」面板。
 *
 * <p>由 {@link com.minhui.networkcapture.RadarDrawView} 用独立的 WindowManager 悬浮 View 承载，
 * 位置固定在雷达视窗旁边（打开时雷达照常渲染，不跳转任何 Activity）。面板结构：
 * <ol>
 *   <li>横向选项卡：雷达 / 资源 / 敌人 / 玩家 / 箱子；</li>
 *   <li>每个 tab 下一组紧凑开关（CheckBox 行 / 三档选择行 / 资源类别卡片），可上下滚动；</li>
 *   <li>底部：更多设置…（跳转 RenderOptionsActivity）与关闭。</li>
 * </ol>
 *
 * <p>所有开关直接读写 {@link RadarSettings} 单例（渲染线程每帧读取，下一帧即生效），
 * 并调用 {@link RadarSettings#putBoolean} 等持久化到 SharedPreferences。
 * key 与 {@link RenderOptionsActivity} 完全一致，两个入口互相同步。
 */
public class RadarQuickOptionsPanel
{
    /** 面板关闭 / 打开完整设置页的回调。 */
    public interface Listener
    {
        void onClose();

        void onOpenFullSettings();
    }

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFF90A4AE;
    private static final int COLOR_ACCENT = 0xFFF4840B;
    private static final int COLOR_TAB_BG = 0xFF2A2A2A;
    private static final int COLOR_CHIP_BG = 0xFF333333;
    private static final int COLOR_CARD_BG = 0xFF232323;
    private static final int COLOR_DIVIDER = 0xFF3A3A3A;

    private static final String[] TAB_TITLES = {"雷达", "资源", "敌人", "玩家", "箱子"};

    /** 资源 tab 的显示顺序：按使用频率 Wood / Hide / Ore / Rock / Fiber。 */
    private static final String[] RESOURCE_ORDER = {"wood", "hide", "ore", "rock", "fiber"};

    private final Context context;
    private final RadarSettings settings;
    private final Listener listener;
    private final float density;

    private final View root;
    private final LinearLayout tabRow;
    private final ScrollView quickScroll;
    private final LinearLayout quickContent;

    /** 资源 tab 里已展开等级矩阵的类别（重建列表时保持展开状态）。 */
    private final HashSet<String> expandedKinds = new HashSet<>();

    private int currentTab = 0;

    public RadarQuickOptionsPanel(Context context, Listener listener)
    {
        this.context = context;
        this.listener = listener;
        this.settings = RadarSettings.getInstance();
        this.density = context.getResources().getDisplayMetrics().density;

        root = LayoutInflater.from(context).inflate(R.layout.radar_quick_render_options, null, false);
        tabRow = root.findViewById(R.id.quickTabRow);
        quickScroll = root.findViewById(R.id.quickScroll);
        quickContent = root.findViewById(R.id.quickContent);

        root.findViewById(R.id.quickHeaderClose).setOnClickListener(v -> close());
        root.findViewById(R.id.quickClose).setOnClickListener(v -> close());
        root.findViewById(R.id.quickMore).setOnClickListener(v ->
        {
            if (listener != null)
            {
                listener.onOpenFullSettings();
            }
        });
    }

    public View getView()
    {
        return root;
    }

    /** 每次打开面板时调用：按当前 RadarSettings 重建选项卡与列表，保证与完整设置页同步。 */
    public void refresh()
    {
        buildTabs();
        buildTabContent();
        quickScroll.scrollTo(0, 0);
    }

    private void close()
    {
        if (listener != null)
        {
            listener.onClose();
        }
    }

    // ------------------------------------------------------------------
    // 选项卡
    // ------------------------------------------------------------------
    private void buildTabs()
    {
        tabRow.removeAllViews();

        for (int i = 0; i < TAB_TITLES.length; i++)
        {
            final int index = i;
            boolean active = i == currentTab;

            TextView tab = new TextView(context);
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(px(11), px(5), px(11), px(5));
            tab.setBackground(rounded(active ? COLOR_ACCENT : COLOR_TAB_BG, 6));
            tab.setTextColor(active ? Color.WHITE : COLOR_TEXT_DIM);

            if (active)
            {
                tab.setTypeface(Typeface.DEFAULT_BOLD);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = px(5);
            params.topMargin = px(5);
            tabRow.addView(tab, params);

            tab.setOnClickListener(v ->
            {
                if (currentTab == index)
                {
                    return;
                }

                currentTab = index;
                buildTabs();
                buildTabContent();
                quickScroll.scrollTo(0, 0);
            });
        }
    }

    private void buildTabContent()
    {
        quickContent.removeAllViews();

        switch (currentTab)
        {
            case 0:
                buildRadarTab();
                break;
            case 1:
                buildResourceTab();
                break;
            case 2:
                buildEnemyTab();
                break;
            case 3:
                buildPlayerTab();
                break;
            case 4:
                buildChestTab();
                break;
            default:
                break;
        }
    }

    /** 重建当前 tab 内容并保持滚动位置（点三档选择 / 资源矩阵全开全关后刷新显示）。 */
    private void rebuildContent()
    {
        final int scrollY = quickScroll.getScrollY();
        buildTabContent();
        quickScroll.post(() -> quickScroll.scrollTo(0, scrollY));
    }

    // ------------------------------------------------------------------
    // 雷达 tab
    // ------------------------------------------------------------------
    private void buildRadarTab()
    {
        addChoiceRow(quickContent, "主城暂停", new String[]{"关闭", "完全暂停", "仅暂停渲染"},
                clamp(settings.radarCityPauseMode, 0, 2), mode ->
                {
                    settings.radarCityPauseMode = mode;
                    RadarSettings.putInt(context, MyApplication.PREF_CITY_PAUSE_MODE, mode);
                    // 解析线程 / 渲染线程直接读 ZoneState，改完立刻生效
                    ZoneState.pauseMode = mode;
                });

        addChoiceRow(quickContent, "图标大小", new String[]{"100%", "150%", "200%"},
                iconScalePresetIndex(), index ->
                {
                    float value = index == 0 ? 1f : (index == 1 ? 1.5f : 2f);
                    settings.radarIconScale = value;
                    RadarSettings.putFloat(context, "radarIconScale", value);
                });

        addDivider(quickContent);

        addSwitchRow(quickContent, "等级色块角标", "T 等级 / 附魔角标代替资源图标", settings.radarTierBadges,
                checked ->
                {
                    settings.radarTierBadges = checked;
                    RadarSettings.putBoolean(context, "radarTierBadges", checked);
                });

        // 取证用：角标额外显示解析层原始 tier（T5r4 = 显示 T5 / 原始 4），默认关闭
        addSwitchRow(quickContent, "等级调试角标", "角标附带解析层原始 tier（T4r4），仅用于核对基准",
                settings.resTierDebugBadge, checked ->
                {
                    settings.resTierDebugBadge = checked;
                    RadarSettings.putBoolean(context, "resTierDebugBadge", checked);
                });

        addSwitchRow(quickContent, "资源数量标签", "资源下方显示剩余数量", settings.radarResourceCount,
                checked ->
                {
                    settings.radarResourceCount = checked;
                    // 旧采集页的 harvestingSize 与这里是同一个效果，一起写避免行为不一致
                    settings.harvestingSize = checked;
                    RadarSettings.putBoolean(context, "radarResourceCount", checked);
                    RadarSettings.putBoolean(context, "harvestingSize", checked);
                });

        addSwitchRow(quickContent, "资源距离标签", "资源下方显示到本机的码数", settings.radarResourceDistance,
                checked ->
                {
                    settings.radarResourceDistance = checked;
                    RadarSettings.putBoolean(context, "radarResourceDistance", checked);
                });

        addSwitchRow(quickContent, "资源聚类", "相邻同类资源合并成一个点并显示数量", settings.radarClusters,
                checked ->
                {
                    settings.radarClusters = checked;
                    RadarSettings.putBoolean(context, "radarClusters", checked);
                });

        addDivider(quickContent);

        addSwitchRow(quickContent, "距离环", "10 / 20 / 40 码参考环", settings.radarShowRings,
                checked ->
                {
                    settings.radarShowRings = checked;
                    RadarSettings.putBoolean(context, "radarShowRings", checked);
                });

        addSwitchRow(quickContent, "HUD 统计", "左下角资源 / 怪物 / 玩家计数", settings.radarShowHud,
                checked ->
                {
                    settings.radarShowHud = checked;
                    RadarSettings.putBoolean(context, "radarShowHud", checked);
                });

        addSwitchRow(quickContent, "地图背景", "关闭后雷达底色完全透明", settings.radarShowBackground,
                checked ->
                {
                    settings.radarShowBackground = checked;
                    RadarSettings.putBoolean(context, "radarShowBackground", checked);
                });
    }

    private int iconScalePresetIndex()
    {
        if (settings.radarIconScale < 1.25f)
        {
            return 0;
        }

        if (settings.radarIconScale < 1.75f)
        {
            return 1;
        }

        return 2;
    }

    // ------------------------------------------------------------------
    // 资源 tab
    // ------------------------------------------------------------------
    private void buildResourceTab()
    {
        addHint(quickContent, "五类总开关 + 静态/活体全开全关；展开「等级矩阵」可按 T1–T8 × E0–E4 "
                + "逐格勾选是否显示（与完整设置页同一套 key，双向同步）");

        for (String kind : RESOURCE_ORDER)
        {
            addResourceKindBlock(quickContent, kind);
        }

        addDivider(quickContent);

        addSwitchRow(quickContent, "钓鱼点", "Fishing Zone 显示开关", settings.harvestingZoneFishing,
                checked ->
                {
                    settings.harvestingZoneFishing = checked;
                    settings.rebuildHarvestableTypes();
                    RadarSettings.putBoolean(context, "harvestingZoneFishing", checked);
                });
    }

    /** 一类资源：类别总开关 + Static / Living 全开全关 + 可展开的 T1–T8 × E0–E4 矩阵。 */
    private void addResourceKindBlock(LinearLayout parent, final String kind)
    {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD_BG, 6));
        card.setPadding(px(7), px(4), px(7), px(5));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = px(5);

        // 第一行：类别名 + 总开关
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(resourceDisplayName(kind));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);

        final CheckBox total = new CheckBox(context);
        total.setChecked(settings.isResourceKindEnabled(kind));
        total.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
        total.setMinWidth(0);
        total.setMinimumWidth(0);
        total.setPadding(0, 0, 0, 0);
        total.setOnCheckedChangeListener((buttonView, checked) ->
                settings.setResourceKindEnabled(context, kind, checked));
        titleRow.addView(total, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(titleRow);

        // 第二行：Static / Living 子开关（点一下 = 该类矩阵全开/全关）
        LinearLayout subRow = new LinearLayout(context);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setPadding(0, px(3), 0, 0);

        TextView staticChip = makeMatrixChip("静态", false, kind);
        TextView livingChip = makeMatrixChip("活体", true, kind);

        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chipParams.rightMargin = px(5);
        subRow.addView(staticChip, chipParams);
        subRow.addView(livingChip, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(subRow);

        // 第三行：展开 / 收起等级矩阵
        final boolean expanded = expandedKinds.contains(kind);

        TextView expander = new TextView(context);
        expander.setText(expanded ? "▾ 收起等级矩阵" : "▸ 等级矩阵 T1–T8 × E0–E4");
        expander.setTextColor(COLOR_ACCENT);
        expander.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        expander.setGravity(Gravity.CENTER_VERTICAL);
        expander.setPadding(px(2), px(4), px(2), px(2));
        expander.setOnClickListener(v ->
        {
            if (!expandedKinds.remove(kind))
            {
                expandedKinds.add(kind);
            }

            rebuildContent();
        });
        card.addView(expander, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (expanded)
        {
            addResourceMatrix(card, kind, false);
            addResourceMatrix(card, kind, true);
        }

        parent.addView(card, cardParams);
    }

    /**
     * 紧凑等级矩阵：E0~E4 行 × T1~T8 列，每格一个小色块开关。
     * 点格子 = 单格开关；点 T 列标题 = 整列；点 E 行标签 = 整行。
     * 直接读写 {@link RadarSettings#getResourceMatrix} 并用 {@link RadarSettings#saveResourceMatrices}
     * 持久化到与完整设置页相同的 resStatic_/resLiving_ key。
     */
    private void addResourceMatrix(LinearLayout parent, final String kind, final boolean living)
    {
        final boolean[][] matrix = settings.getResourceMatrix(living, kind);
        final int cellW = px(27);
        final int cellH = px(22);
        final int labelW = px(26);

        TextView label = new TextView(context);
        label.setText((living ? "活体 Living" : "静态 Static")
                + "（点 T 列 / E 行 = 整列 / 整行）");
        label.setTextColor(COLOR_TEXT_DIM);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        label.setPadding(px(2), px(5), 0, px(1));
        parent.addView(label);

        // 表头：T1 ~ T8（点一下 = 整列开关）
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView corner = new TextView(context);
        corner.setLayoutParams(new LinearLayout.LayoutParams(labelW, px(20)));
        header.addView(corner);

        for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
        {
            final int column = tier;
            boolean columnOn = isColumnAllOn(matrix, column);

            TextView columnLabel = new TextView(context);
            columnLabel.setText("T" + (tier + 1));
            columnLabel.setTextColor(columnOn ? COLOR_ACCENT : COLOR_TEXT_DIM);
            columnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            columnLabel.setGravity(Gravity.CENTER);
            columnLabel.setBackground(rounded(columnOn ? 0xFF3A2A10 : COLOR_CARD_BG, 3));
            columnLabel.setLayoutParams(new LinearLayout.LayoutParams(cellW, px(20)));
            columnLabel.setOnClickListener(v ->
            {
                boolean next = !isColumnAllOn(matrix, column);
                settings.setResourceColumn(living, kind, column, next);
                settings.saveResourceMatrices(context, kind);
                rebuildContent();
            });
            header.addView(columnLabel);
        }

        parent.addView(header);

        for (int enchant = 0; enchant < RadarSettings.MATRIX_ENCHANTS; enchant++)
        {
            final int row = enchant;
            boolean rowOn = isRowAllOn(matrix, row);

            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, px(1), 0, 0);

            TextView rowLabel = new TextView(context);
            rowLabel.setText("E" + enchant);
            rowLabel.setTextColor(rowOn ? COLOR_ACCENT : COLOR_TEXT_DIM);
            rowLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            rowLabel.setGravity(Gravity.CENTER_VERTICAL);
            rowLabel.setLayoutParams(new LinearLayout.LayoutParams(labelW, cellH));
            rowLabel.setOnClickListener(v ->
            {
                boolean next = !isRowAllOn(matrix, row);
                settings.setResourceRow(living, kind, row, next);
                settings.saveResourceMatrices(context, kind);
                rebuildContent();
            });
            line.addView(rowLabel);

            for (int tier = 0; tier < RadarSettings.MATRIX_TIERS; tier++)
            {
                final int column = tier;
                final boolean on = matrix[row][column];

                final TextView cell = new TextView(context);
                cell.setBackground(rounded(on ? COLOR_ACCENT : COLOR_CHIP_BG, 2));

                // 每格之间留 1dp 缝隙，8 个小开关不会连成一条色带（关闭时是暗格，一眼可辨）
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(cellW, cellH - px(2));
                cellParams.rightMargin = px(1);
                cellParams.topMargin = px(1);
                cellParams.bottomMargin = px(1);
                cell.setLayoutParams(cellParams);
                cell.setOnClickListener(v ->
                {
                    boolean next = !matrix[row][column];
                    settings.setResourceCell(living, kind, row, column, next);
                    settings.saveResourceMatrices(context, kind);
                    // 单格切换即时反映颜色，无需重建整页
                    cell.setBackground(rounded(next ? COLOR_ACCENT : COLOR_CHIP_BG, 2));
                });
                line.addView(cell);
            }

            parent.addView(line);
        }
    }

    private static boolean isColumnAllOn(boolean[][] matrix, int column)
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

    private static boolean isRowAllOn(boolean[][] matrix, int row)
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

    /** 资源矩阵子开关：显示全开 / 部分 / 全关，点一下在「全开」与「全关」之间切换。 */
    private TextView makeMatrixChip(String label, boolean living, final String kind)
    {
        final boolean[][] matrix = settings.getResourceMatrix(living, kind);
        boolean allOn = true;
        boolean anyOn = false;

        for (boolean[] row : matrix)
        {
            for (boolean cell : row)
            {
                allOn = allOn && cell;
                anyOn = anyOn || cell;
            }
        }

        String state = allOn ? "全开" : (anyOn ? "部分" : "全关");

        TextView chip = new TextView(context);
        chip.setText(label + " " + state);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(px(6), px(4), px(6), px(4));
        chip.setTextColor(anyOn ? Color.WHITE : COLOR_TEXT_DIM);
        chip.setBackground(rounded(anyOn ? 0xFF8A5A10 : COLOR_CHIP_BG, 5));

        final boolean hasAny = anyOn;
        chip.setOnClickListener(v ->
        {
            boolean next = !hasAny;
            settings.setResourceMatrixAll(living, kind, next);
            settings.saveResourceMatrices(context, kind);
            rebuildContent();
        });
        return chip;
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

    // ------------------------------------------------------------------
    // 敌人 tab
    // ------------------------------------------------------------------
    private void buildEnemyTab()
    {
        addSwitchRow(quickContent, "Boss", "红色", settings.mobBoss, checked ->
        {
            settings.mobBoss = checked;
            RadarSettings.putBoolean(context, "mobBoss", checked);
        });

        addSwitchRow(quickContent, "MiniBoss", "橙色", settings.mobMiniBoss, checked ->
        {
            settings.mobMiniBoss = checked;
            RadarSettings.putBoolean(context, "mobMiniBoss", checked);
        });

        addSwitchRow(quickContent, "普通 / 附魔怪", "绿色 / 紫色", settings.mobEnemy, checked ->
        {
            settings.mobEnemy = checked;
            RadarSettings.putBoolean(context, "mobEnemy", checked);
        });

        addSwitchRow(quickContent, "活资源（可采集）", "矿 / 木 / 草 / 石资源怪", settings.mobHarvestable, checked ->
        {
            settings.mobHarvestable = checked;
            RadarSettings.putBoolean(context, "mobHarvestable", checked);
        });

        addSwitchRow(quickContent, "可剥皮", "兽皮资源怪", settings.mobSkinnable, checked ->
        {
            settings.mobSkinnable = checked;
            RadarSettings.putBoolean(context, "mobSkinnable", checked);
        });

        addSwitchRow(quickContent, "其他 / 未识别", "兜底开关", settings.mobOther, checked ->
        {
            settings.mobOther = checked;
            RadarSettings.putBoolean(context, "mobOther", checked);
        });

        addDivider(quickContent);

        addSwitchRow(quickContent, "显示血量条", "怪物 / 活体资源血条", settings.mobHp, checked ->
        {
            settings.mobHp = checked;
            RadarSettings.putBoolean(context, "mobHp", checked);
        });
    }

    // ------------------------------------------------------------------
    // 玩家 tab
    // ------------------------------------------------------------------
    private void buildPlayerTab()
    {
        addSwitchRow(quickContent, "显示玩家", "总开关", settings.playerDot, checked ->
        {
            settings.playerDot = checked;
            RadarSettings.putBoolean(context, "playerDot", checked);
        });

        addSwitchRow(quickContent, "昵称", "", settings.playerNickname, checked ->
        {
            settings.playerNickname = checked;
            RadarSettings.putBoolean(context, "playerNickname", checked);
        });

        addSwitchRow(quickContent, "公会名", "", settings.playerGuildName, checked ->
        {
            settings.playerGuildName = checked;
            RadarSettings.putBoolean(context, "playerGuildName", checked);
        });

        addSwitchRow(quickContent, "血量条", "", settings.playerHealth, checked ->
        {
            settings.playerHealth = checked;
            RadarSettings.putBoolean(context, "playerHealth", checked);
        });

        addSwitchRow(quickContent, "距离", "", settings.playerDistance, checked ->
        {
            settings.playerDistance = checked;
            RadarSettings.putBoolean(context, "playerDistance", checked);
        });

        addSwitchRow(quickContent, "坐骑标记 M", "", settings.playerMounted, checked ->
        {
            settings.playerMounted = checked;
            RadarSettings.putBoolean(context, "playerMounted", checked);
        });

        addSwitchRow(quickContent, "已发现玩家面板", "右上角玩家列表", settings.playerPanel, checked ->
        {
            settings.playerPanel = checked;
            RadarSettings.putBoolean(context, "playerPanel", checked);
        });

        addDivider(quickContent);

        addSwitchRow(quickContent, "敌对玩家", "红名 / 可攻击（红色）", settings.playerHostile, checked ->
        {
            settings.playerHostile = checked;
            RadarSettings.putBoolean(context, "playerHostile", checked);
        });

        addSwitchRow(quickContent, "被动 / 中立玩家", "蓝色", settings.playerPassive, checked ->
        {
            settings.playerPassive = checked;
            RadarSettings.putBoolean(context, "playerPassive", checked);
        });

        addSwitchRow(quickContent, "公会 / 联盟配色", "同公会绿、同联盟紫", settings.playerAllianceColor, checked ->
        {
            settings.playerAllianceColor = checked;
            RadarSettings.putBoolean(context, "playerAllianceColor", checked);
        });

        addSwitchRow(quickContent, "阵营配色", "阵营玩家橙色", settings.playerFactionColor, checked ->
        {
            settings.playerFactionColor = checked;
            RadarSettings.putBoolean(context, "playerFactionColor", checked);
        });
    }

    // ------------------------------------------------------------------
    // 箱子 tab
    // ------------------------------------------------------------------
    private void buildChestTab()
    {
        addChestRow(quickContent, "普通箱子", "standard", "chestStandard");
        addChestRow(quickContent, "优秀箱子", "uncommon", "chestUncommon");
        addChestRow(quickContent, "稀有箱子", "rare", "chestRare");
        addChestRow(quickContent, "传说箱子", "legendary", "chestLegendary");
    }

    private void addChestRow(LinearLayout parent, String title, final String chestKey, final String prefKey)
    {
        Boolean stored = settings.chests.get(chestKey);
        boolean checked = stored != null && stored;

        addSwitchRow(parent, title, "", checked, isChecked ->
        {
            settings.chests.put(chestKey, isChecked);
            RadarSettings.putBoolean(context, prefKey, isChecked);
        });
    }

    // ------------------------------------------------------------------
    // 通用控件
    // ------------------------------------------------------------------
    /** 一行「标题（可选副标题）+ 右侧开关」，点整行也能切换。 */
    private void addSwitchRow(LinearLayout parent, String title, String subtitle, boolean checked,
                              Consumer<Boolean> onChange)
    {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(6), px(2), px(4), px(2));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty())
        {
            TextView subtitleView = new TextView(context);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(COLOR_TEXT_DIM);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            texts.addView(subtitleView);
        }

        row.addView(texts);

        final CheckBox checkBox = new CheckBox(context);
        checkBox.setChecked(checked);
        checkBox.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
        checkBox.setMinWidth(0);
        checkBox.setMinimumWidth(0);
        checkBox.setPadding(0, 0, 0, 0);
        row.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 点整行 = 切换开关；只在这里改 CheckBox，避免回调被触发两次
        row.setOnClickListener(v -> checkBox.toggle());
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> onChange.accept(isChecked));

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 一行「标题 + 若干三档按钮」（主城暂停 / 图标大小）。 */
    private void addChoiceRow(LinearLayout parent, String title, String[] labels, int selected,
                              IntConsumer onChange)
    {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(px(6), px(4), px(6), px(3));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        wrapper.addView(titleView);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(4), 0, 0);

        for (int i = 0; i < labels.length; i++)
        {
            final int index = i;
            boolean active = i == selected;

            TextView chip = new TextView(context);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(px(9), px(5), px(9), px(5));
            chip.setTextColor(active ? Color.WHITE : COLOR_TEXT_DIM);
            chip.setBackground(rounded(active ? COLOR_ACCENT : COLOR_CHIP_BG, 6));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = px(6);
            row.addView(chip, params);

            chip.setOnClickListener(v ->
            {
                if (index == selected)
                {
                    return;
                }

                onChange.accept(index);
                rebuildContent();
            });
        }

        wrapper.addView(row);
        parent.addView(wrapper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addHint(LinearLayout parent, String text)
    {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(COLOR_TEXT_DIM);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setPadding(px(6), px(4), px(6), px(4));
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDivider(LinearLayout parent)
    {
        View divider = new View(context);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(0.5f)));
        params.topMargin = px(3);
        params.bottomMargin = px(3);
        parent.addView(divider, params);
    }

    private GradientDrawable rounded(int color, float radiusDp)
    {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(px(radiusDp));
        return drawable;
    }

    private int px(float dp)
    {
        return Math.round(dp * density);
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
