package com.minhui.networkcapture.RadarView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.minhui.networkcapture.R;
import com.minhui.vpn.Handlers.HandlerItem.Harvestable;
import com.minhui.vpn.Handlers.HandlerItem.Mob;
import com.minhui.vpn.Handlers.HandlerItem.Player;
import com.minhui.vpn.Handlers.MainHandler;
import com.minhui.vpn.PhotonPackageParser.Utils;
import com.minhui.vpn.PhotonPackageParser.enumerations.MobCodes;
import com.minhui.vpn.PhotonPackageParser.enumerations.MobsResourceTable;
import com.minhui.vpn.zone.ZoneState;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * 雷达视窗（直绘渲染器）。
 *
 * 渲染逻辑对齐 PC 版 OpenRadar：
 *  - 等距投影：screen = center + rotate45(-posX + lpX, posY - lpY) * k，相机始终跟随本地玩家；
 *    与 PC 的 DrawingUtils.transformPoint 一致（angle=-45°，BASE_ZOOM=4）。
 *  - 资源：图标 + T 等级/附魔角标，缺图时按资源类别画彩色方块兜底。
 *  - 怪物：PC 的 getEnemyColor 配色（普通绿 / 附魔紫 / 小 Boss 橙 / Boss 红 / 无人机青 / 迷雾粉 / 事件白），
 *    活体资源（Skinnable/Harvestable）用资源图标 + 金框。
 *  - 玩家：公会绿 / 联盟紫 / 阵营橙 / 其他蓝，昵称、距离、坐骑、血条。
 *  - 自己：蓝点 + 外圈，固定在视窗中心（跟随相机）。
 * 保留 60fps vsync 重绘、累积上限（采集 5000 / 怪物 2000）。
 */
public class RadarView extends View
{
    private static final String TAG = "RadarDiag";

    /** PC 参考：BASE_ZOOM(4) * 0.785398(45°旋转系数的绝对值) = 3.14159 px / 码（画布 500px 时）。 */
    private static final float PC_PX_PER_UNIT_AT_500 = 3.1415927f;
    private static final float PC_REFERENCE_CANVAS = 500f;

    /** 保底上限：采集物 5000、怪物 2000（超出按距玩家远近淘汰）。 */
    private static final int MAX_HARVEST_ENTITIES = 5000;
    private static final int MAX_MOB_ENTITIES = 2000;

    /** PC 版 MobsDrawing.getEnemyColor 的配色。 */
    private static final int COLOR_MOB_ENEMY = 0xFF00FF00;
    private static final int COLOR_MOB_MEDIUM = 0xFF32CD32;
    private static final int COLOR_MOB_ENCHANTED = 0xFF9370DB;
    private static final int COLOR_MOB_MINIBOSS = 0xFFFF8C00;
    private static final int COLOR_MOB_BOSS = 0xFFFF0000;
    private static final int COLOR_MOB_DRONE = 0xFF00FFFF;
    private static final int COLOR_MOB_MISTBOSS = 0xFFFF1493;
    private static final int COLOR_MOB_EVENTS = 0xFFFFFFFF;
    private static final int COLOR_MOB_GUARD = 0xFFFFD700;
    private static final int COLOR_MOB_LIVING = 0xFFFFD700;
    private static final int COLOR_MOB_PORTAL = 0xFFAB47BC;

    private static final int COLOR_PLAYER_GUILD = 0xFF43A047;
    private static final int COLOR_PLAYER_ALLIANCE = 0xFF8E24AA;
    private static final int COLOR_PLAYER_FACTION = 0xFFFB8C00;
    private static final int COLOR_PLAYER_HOSTILE = 0xFFE53935;
    private static final int COLOR_PLAYER_OTHER = 0xFF2979FF;
    private static final int COLOR_SELF = 0xFF29B6F6;

    private static final int COLOR_RES_FIBER = 0xFF4CAF50;
    private static final int COLOR_RES_HIDE = 0xFFA1887F;
    private static final int COLOR_RES_WOOD = 0xFF8D6E63;
    private static final int COLOR_RES_ORE = 0xFF42A5F5;
    private static final int COLOR_RES_ROCK = 0xFF9C27B0;
    private static final int COLOR_RES_UNKNOWN = 0xFF4169E1;

    private static final java.util.concurrent.atomic.AtomicInteger INSTANCES = new java.util.concurrent.atomic.AtomicInteger();

    /** 60fps 刷新循环：跟随 vsync 重绘。 */
    private final Runnable frameLoop = new Runnable()
    {
        @Override
        public void run()
        {
            if (!frameLoopRunning)
            {
                return;
            }

            invalidate();
            postOnAnimation(this);
        }
    };

    private boolean frameLoopRunning;
    private boolean eventBusRegistered;

    public DrawPlayers drawPlayers = new DrawPlayers();
    public DrawMobs drawMobs = new DrawMobs();
    public HarvestingDraw harvestingDraw = new HarvestingDraw();
    public DrawChests drawChests = new DrawChests();
    public FishingZoneDraw fishingZoneDraw = new FishingZoneDraw();
    BitmapCache bitmapCache = new BitmapCache();

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint entityTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hpFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selfRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] tmpScreen = new float[2];
    private final float[] tmpScreen2 = new float[2];
    private final RectF tmpIconRect = new RectF();
    private final RectF tmpRect = new RectF();

    private final HashMap<String, Bitmap> iconCache = new HashMap<>();
    private int iconCacheSizePx;

    /** 资源聚类：cell key -> {count, sumX, sumY, kindIndex, living}（按帧重建）。 */
    private final HashMap<Long, float[]> clusterStats = new HashMap<>();
    private final HashSet<Long> drawnClusterKeys = new HashSet<>();

    private final Paint clusterTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long lastDiagLogTime;
    private long lastTrimTime;
    private long lastDrawnLogTime;
    /** 主城暂停时“render skipped”日志限流。 */
    private long lastCityPauseLogTime;
    private long fpsWindowStart;
    private int frameCount;
    private int lastDrawnHarvest;
    private int lastDrawnMobs;
    private int lastDrawnPlayers;
    private int lastDrawnChests;
    private int lastDrawnFishing;
    /** 活体资源点位日志限流。 */
    private int livingDiagCount;
    private long lastPlayerDiagTime;

    /** 怪物血量没有 max 字段：用首次观测值作为满血基准，便于画比例血条。 */
    private final HashMap<Integer, Integer> mobMaxHealth = new HashMap<>();

    private final Matrix transformationMatrix = new Matrix();

    public RadarView(Context context)
    {
        super(context);
        init();
    }

    public RadarView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        init();
    }

    public RadarView(Context context, AttributeSet attrs, int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init()
    {
        INSTANCES.incrementAndGet();

        try
        {
            EventBus.getDefault().register(this);
            eventBusRegistered = true;
        }
        catch (Throwable ignored)
        {
            // EventBus 只是锦上添花，注册失败不能影响渲染
        }

        backgroundPaint.setStyle(Paint.Style.FILL);
        backgroundPaint.setColor(0xEE0E1114);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(2f, RadarSettings.getInstance().radarCircleSquareBorderBar));
        borderPaint.setColor(getContext().getColor(R.color.colorBlue));

        entityPaint.setStyle(Paint.Style.FILL);
        entityPaint.setAntiAlias(true);

        entityTextPaint.setAntiAlias(true);
        entityTextPaint.setColor(0xFFFFFFFF);
        entityTextPaint.setTextAlign(Paint.Align.CENTER);
        entityTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        badgeTextPaint.setAntiAlias(true);
        badgeTextPaint.setColor(0xFFFFFFFF);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        hpBackPaint.setColor(0xCC000000);
        hpBackPaint.setStyle(Paint.Style.FILL);
        hpFillPaint.setStyle(Paint.Style.FILL);
        hpFillPaint.setColor(0xFF00FF00);

        selfPaint.setColor(COLOR_SELF);
        selfPaint.setStyle(Paint.Style.FILL);
        selfPaint.setAntiAlias(true);

        selfRingPaint.setStyle(Paint.Style.STROKE);
        selfRingPaint.setStrokeWidth(3f);
        selfRingPaint.setColor(0xFFFFFFFF);
        selfRingPaint.setAntiAlias(true);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.2f);
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setAntiAlias(true);

        hintPaint.setColor(0xFF90A4AE);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setAntiAlias(true);

        hudPaint.setColor(0xFFFFFFFF);
        hudPaint.setAntiAlias(true);
        hudPaint.setTextSize(12f);
        hudPaint.setTypeface(Typeface.MONOSPACE);

        hudBackPaint.setColor(0x99000000);
        hudBackPaint.setStyle(Paint.Style.FILL);

        clusterTextPaint.setAntiAlias(true);
        clusterTextPaint.setColor(0xFFFFFFFF);
        clusterTextPaint.setTextAlign(Paint.Align.CENTER);
        clusterTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        panelBackPaint.setColor(0xB3000000);
        panelBackPaint.setStyle(Paint.Style.FILL);

        panelTextPaint.setAntiAlias(true);
        panelTextPaint.setColor(0xFFFFFFFF);
        panelTextPaint.setTextAlign(Paint.Align.LEFT);
        panelTextPaint.setTypeface(Typeface.MONOSPACE);

        playerRingPaint.setStyle(Paint.Style.STROKE);
        playerRingPaint.setStrokeWidth(2.5f);
        playerRingPaint.setAntiAlias(true);

        drawPlayers.init(this);
        drawMobs.init(this);
        harvestingDraw.init(this);
        fishingZoneDraw.init(this);
        drawChests.init(this);

        Log.d(TAG, "RadarView created instance=" + hashCode() + " live=" + INSTANCES.get());
    }

    @Subscribe
    public void onMessage(String event)
    {
        // EventBus 不保证在 UI 线程回调，用 postInvalidate 保证线程安全
        postInvalidate();
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        frameLoopRunning = true;
        postOnAnimation(frameLoop);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        frameLoopRunning = false;
        removeCallbacks(frameLoop);
        // 释放图标缓存，避免服务反复创建时 Bitmap 泄漏
        iconCache.clear();

        if (eventBusRegistered)
        {
            try
            {
                EventBus.getDefault().unregister(this);
            }
            catch (Throwable ignored)
            {
            }

            eventBusRegistered = false;
        }

        super.onDetachedFromWindow();
    }

    /** 兼容旧接口：旧渲染链路用矩阵，新实现直接算屏幕坐标。 */
    public void initMatrix()
    {
        int width = getWidth();
        int height = getHeight();

        transformationMatrix.reset();

        if (width > 0 && height > 0)
        {
            float scale = getPxPerUnit(width);

            // 旧绘制类（DrawMobs/DrawPlayers 等）仍按矩阵投影，保留同一套相机换算
            transformationMatrix.postTranslate(width / 2f, height / 2f);
            transformationMatrix.postScale(-scale, scale);
        }

        postInvalidate();
    }

    public void setBorderSize(int size)
    {
        borderPaint.setStrokeWidth(Math.max(1f, size));
        invalidate();
    }

    private float getPxPerUnit(int viewSize)
    {
        float zoom = RadarSettings.getInstance().radarScaleBar;

        if (!Float.isFinite(zoom) || zoom <= 0.05f)
        {
            zoom = 1f;
        }

        zoom = Math.max(0.1f, Math.min(4f, zoom));

        return (viewSize / PC_REFERENCE_CANVAS) * PC_PX_PER_UNIT_AT_500 * zoom;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);

        if (w > 0 && h > 0)
        {
            iconCache.clear();
            iconCacheSizePx = 0;
            initMatrix();
        }
    }

    /** 工具：世界坐标 -> 视窗像素（PC 版等距投影，相机在本地玩家）。 */
    private static void projectToScreen(float x, float y, float lpX, float lpY, float k,
                                        float cx, float cy, float[] out)
    {
        float hx = -x + lpX;
        float hy = y - lpY;

        // PC: newX = angle * (hx - hy); newY = angle * (hx + hy)，angle = -0.785398
        out[0] = cx - (hx - hy) * k;
        out[1] = cy - (hx + hy) * k;
    }

    private static boolean isGroundPosition(float x, float y)
    {
        return Float.isFinite(x) && Float.isFinite(y)
                && Math.abs(x) < 5000f && Math.abs(y) < 5000f
                && !(x == 0f && y == 0f);
    }

    /** 采集物 type 数字 -> 资源类别（PC 版 HarvestablesDrawing 的 type 区间回退逻辑）。 */
    private static String resourceKindOfType(int type)
    {
        if (type >= 0 && type <= 5)
        {
            return "log";
        }

        if (type >= 6 && type <= 10)
        {
            return "rock";
        }

        if (type >= 11 && type <= 14)
        {
            return "fiber";
        }

        if (type >= 15 && type <= 22)
        {
            return "hide";
        }

        if (type >= 23 && type <= 27)
        {
            return "ore";
        }

        return null;
    }

    private static int resourceCategoryColor(String kind)
    {
        if (kind == null)
        {
            return COLOR_RES_UNKNOWN;
        }

        switch (RadarSettings.normalizeKind(kind) == null ? "" : RadarSettings.normalizeKind(kind))
        {
            case "fiber":
                return COLOR_RES_FIBER;
            case "hide":
                return COLOR_RES_HIDE;
            case "wood":
                return COLOR_RES_WOOD;
            case "ore":
                return COLOR_RES_ORE;
            case "rock":
                return COLOR_RES_ROCK;
            default:
                return COLOR_RES_UNKNOWN;
        }
    }

    /** 类别 -> RadarSettings.RESOURCE_KIND_KEYS 下标；未知返回 -1。 */
    private static int kindIndex(String kind)
    {
        String normalized = RadarSettings.normalizeKind(kind);

        if (normalized == null)
        {
            return -1;
        }

        for (int i = 0; i < RadarSettings.RESOURCE_KIND_KEYS.length; i++)
        {
            if (RadarSettings.RESOURCE_KIND_KEYS[i].equals(normalized))
            {
                return i;
            }
        }

        return -1;
    }

    private static int kindColor(int kindIndex)
    {
        if (kindIndex < 0 || kindIndex >= RadarSettings.RESOURCE_KIND_KEYS.length)
        {
            return COLOR_RES_UNKNOWN;
        }

        return resourceCategoryColor(RadarSettings.RESOURCE_KIND_KEYS[kindIndex]);
    }

    // ------------------------------------------------------------------
    // 资源聚类（PC settingRadarResourceClusters 语义）
    // ------------------------------------------------------------------
    private long clusterKey(float worldX, float worldY, int kindIdx, boolean living)
    {
        int radius = Math.max(5, RadarSettings.getInstance().radarClusterRadius);
        long cellX = (long) Math.floor(worldX / radius) & 0xFFFFFL;
        long cellY = (long) Math.floor(worldY / radius) & 0xFFFFFL;
        long key = cellX | (cellY << 20) | (((long) (kindIdx + 1)) & 0x7L) << 40;

        if (living)
        {
            key |= 1L << 44;
        }

        return key;
    }

    /** 按帧预统计聚类单元（静态资源 + 活体资源按类别、分静态/活体统计）。 */
    private void prepareResourceClusters(RadarSettings settings)
    {
        clusterStats.clear();
        drawnClusterKeys.clear();

        if (!settings.radarClusters)
        {
            return;
        }

        MainHandler handler = MainHandler.getInstance();

        for (Harvestable harvestable : handler.harvestablesHandler.getHarvestableList())
        {
            String kind = visibleStaticKind(settings, harvestable);

            if (kind == null)
            {
                continue;
            }

            accumulateCluster(harvestable.getPosX(), harvestable.getPosY(), kindIndex(kind), false);
        }

        for (Mob mob : handler.mobsHandler.getMobList())
        {
            MobCodes type = mob.getType() == null ? MobCodes.Enemy : mob.getType();

            if (!isLivingResourceType(type) || !isGroundPosition(mob.getPosX(), mob.getPosY())
                    || MobsResourceTable.isTier1LivingHidden(mob.getTier())
                    || !isLivingResourceVisible(settings, mob, type))
            {
                continue;
            }

            accumulateCluster(mob.getPosX(), mob.getPosY(), kindIndex(livingKindOf(mob)), true);
        }
    }

    private void accumulateCluster(float worldX, float worldY, int kindIdx, boolean living)
    {
        long key = clusterKey(worldX, worldY, kindIdx, living);
        float[] stats = clusterStats.get(key);

        if (stats == null)
        {
            stats = new float[]{0f, 0f, 0f, kindIdx, living ? 1f : 0f};
            clusterStats.put(key, stats);
        }

        stats[0] += 1f;
        stats[1] += worldX;
        stats[2] += worldY;
    }

    /** 该实体是否被聚类吸收（聚类开启且同格数量 >= 阈值时不在原位单独画）。 */
    private boolean isClusterMember(RadarSettings settings, float worldX, float worldY,
                                    int kindIdx, boolean living)
    {
        if (!settings.radarClusters)
        {
            return false;
        }

        float[] stats = clusterStats.get(clusterKey(worldX, worldY, kindIdx, living));

        return stats != null && stats[0] >= Math.max(2, settings.radarClusterMinSize);
    }

    /** 画聚类点（一个类别色块 + 数量），并返回画了几个。 */
    private int drawResourceClusters(Canvas canvas, RadarSettings settings, float lpX, float lpY,
                                     float k, float cx, float cy, int width, int height)
    {
        if (!settings.radarClusters || clusterStats.isEmpty())
        {
            return 0;
        }

        float iconPx = iconSize(width);
        float half = iconPx / 2f;
        float margin = iconPx + 8f;
        int drawn = 0;

        for (HashMap.Entry<Long, float[]> entry : clusterStats.entrySet())
        {
            float[] stats = entry.getValue();

            if (stats[0] < Math.max(2, settings.radarClusterMinSize))
            {
                continue;
            }

            float worldX = stats[1] / stats[0];
            float worldY = stats[2] / stats[0];
            int kindIdx = (int) stats[3];
            boolean living = stats[4] > 0.5f;

            projectToScreen(worldX, worldY, lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            int color = kindColor(kindIdx);
            entityPaint.setColor(color);
            tmpIconRect.set(tmpScreen[0] - half, tmpScreen[1] - half,
                    tmpScreen[0] + half, tmpScreen[1] + half);
            canvas.drawRect(tmpIconRect, entityPaint);

            if (living)
            {
                borderPaint.setColor(COLOR_MOB_LIVING);
                canvas.drawRect(tmpIconRect, borderPaint);
                borderPaint.setColor(getContext().getColor(R.color.colorBlue));
            }

            clusterTextPaint.setTextSize(Math.max(10f, iconPx * 0.55f));
            clusterTextPaint.setColor(0xFFFFFFFF);
            canvas.drawText("x" + (int) stats[0], tmpScreen[0], tmpScreen[1] + iconPx * 0.24f,
                    clusterTextPaint);

            drawn++;
        }

        return drawn;
    }

    /** 按 PC 命名（log_4_0 / ore_8_2 ...）取 drawable，缺失返回 null。 */
    private Bitmap getResourceIcon(String kind, int tier, int enchant, int iconPx)
    {
        String name = kind + "_" + tier + "_" + enchant;

        if (iconPx != iconCacheSizePx)
        {
            iconCache.clear();
            iconCacheSizePx = iconPx;
        }

        if (iconCache.containsKey(name))
        {
            return iconCache.get(name);
        }

        Bitmap bitmap = null;

        try
        {
            int resId = getResources().getIdentifier(name, "drawable", getContext().getPackageName());

            if (resId != 0)
            {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inSampleSize = iconPx > 48 ? 1 : 2;

                Bitmap decoded = BitmapFactory.decodeResource(getResources(), resId, options);

                if (decoded != null)
                {
                    // 统一缩放到 2x 显示尺寸，避免每帧由 GPU 缩放原图，也避免缓存超大位图
                    int cachePx = Math.max(32, iconPx * 2);
                    bitmap = Bitmap.createScaledBitmap(decoded, cachePx, cachePx, true);

                    if (bitmap != decoded)
                    {
                        decoded.recycle();
                    }
                }
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "load resource icon failed: " + name + " : " + e);
            bitmap = null;
        }

        iconCache.put(name, bitmap);

        return bitmap;
    }

    private void ensurePaints(int width)
    {
        entityTextPaint.setTextSize(Math.max(11f, width * 0.028f));
        badgeTextPaint.setTextSize(Math.max(9f, width * 0.026f));
        hintPaint.setTextSize(Math.max(14f, width * 0.052f));
        hudPaint.setTextSize(Math.max(10f, width * 0.027f));
        borderPaint.setStrokeWidth(Math.max(2f, RadarSettings.getInstance().radarCircleSquareBorderBar));
    }

    @SuppressLint("DrawAllocation")
    @Override
    protected void onDraw(Canvas canvas)
    {
        int width = getWidth();
        int height = getHeight();

        if (width <= 0 || height <= 0)
        {
            return;
        }

        long now = System.currentTimeMillis();
        frameCount++;

        if (fpsWindowStart == 0)
        {
            fpsWindowStart = now;
        }
        else if (now - fpsWindowStart >= 5000)
        {
            Log.d(TAG, "fps=" + (frameCount * 1000L / Math.max(1, now - fpsWindowStart)));
            frameCount = 0;
            fpsWindowStart = now;
        }

        ensurePaints(width);

        final float cx = width / 2f;
        final float cy = height / 2f;
        final float radius = Math.min(width, height) * 0.06f;
        final RadarSettings settings = RadarSettings.getInstance();

        if (settings.radarShowBackground)
        {
            canvas.drawRoundRect(0, 0, width, height, radius, radius, backgroundPaint);
        }

        canvas.drawRoundRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, radius, radius, borderPaint);

        // 主城暂停：只画背景 + 居中文案，直接 return —— 不遍历实体、不画图标/玩家/HUD。
        // 60fps 重绘循环照旧（一帧只有一个 drawText，开销可忽略），离开主城下一帧自动恢复。
        if (ZoneState.shouldSkipRender())
        {
            drawCityPauseHint(canvas, width, height);
            return;
        }

        canvas.save();
        canvas.clipRect(0, 0, width, height);

        final MainHandler handler = MainHandler.getInstance();
        final float lpX = handler.playersHandler.localPlayerPosX();
        final float lpY = handler.playersHandler.localPlayerPosY();
        final boolean lpValid = isGroundPosition(lpX, lpY);

        // 长时间跑图时只保留离玩家最近的一批，防止内存和绘制量无限增长（每 5s 一次）
        if (now - lastTrimTime >= 5000)
        {
            lastTrimTime = now;

            try
            {
                handler.harvestablesHandler.trimToLimit(lpX, lpY, MAX_HARVEST_ENTITIES);
                handler.mobsHandler.trimToLimit(lpX, lpY, MAX_MOB_ENTITIES);
            }
            catch (Throwable ignored)
            {
            }
        }

        if (!lpValid)
        {
            canvas.drawText("等待位置数据…", cx, cy + hintPaint.getTextSize() / 3f, hintPaint);
            canvas.restore();
            return;
        }

        final float k = getPxPerUnit(width);

        if (settings.radarShowRings)
        {
            drawRangeRings(canvas, cx, cy, k);
        }

        // 聚类预统计（开启时才遍历，60fps 下开销可控）
        prepareResourceClusters(settings);

        int drawnHarvest = 0;
        int drawnMobs = 0;
        int drawnPlayers = 0;
        int drawnChests = 0;
        int drawnFishing = 0;

        try
        {
            drawnHarvest = drawHarvestables(canvas, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("harvest", e);
        }

        try
        {
            drawnMobs = drawMobs2(canvas, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("mobs", e);
        }

        try
        {
            drawnPlayers = drawPlayers2(canvas, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("players", e);
        }

        try
        {
            drawnChests = drawChests2(canvas, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("chests", e);
        }

        try
        {
            drawnFishing = drawFishingZones(canvas, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("fishing", e);
        }

        try
        {
            drawnHarvest += drawResourceClusters(canvas, settings, lpX, lpY, k, cx, cy, width, height);
        }
        catch (Throwable e)
        {
            logSectionFailure("clusters", e);
        }

        // 自己：相机跟随，永远在视窗中心
        float selfRadius = Math.max(7f, width * 0.016f);
        canvas.drawCircle(cx, cy, selfRadius, selfPaint);
        canvas.drawCircle(cx, cy, selfRadius + Math.max(5f, width * 0.012f), selfRingPaint);

        ArrayList<Player> detectedPlayers = handler.playersHandler.getPlayersInRange();

        try
        {
            drawPlayerPanel(canvas, width, height, detectedPlayers);
        }
        catch (Throwable e)
        {
            logSectionFailure("playerPanel", e);
        }

        lastDrawnHarvest = drawnHarvest;
        lastDrawnMobs = drawnMobs;
        lastDrawnPlayers = drawnPlayers;
        lastDrawnChests = drawnChests;
        lastDrawnFishing = drawnFishing;

        if (settings.radarShowHud)
        {
            drawHud(canvas, width, height, drawnHarvest, drawnMobs, detectedPlayers.size(),
                    drawnPlayers);
        }

        if (now - lastDrawnLogTime >= 2000)
        {
            lastDrawnLogTime = now;
            Log.d(TAG, "direct drawn h=" + drawnHarvest + " m=" + drawnMobs + " p=" + drawnPlayers
                    + " c=" + drawnChests + " f=" + drawnFishing
                    + " detectedPlayers=" + detectedPlayers.size()
                    + " xorReady=" + com.minhui.vpn.PhotonPackageParser.classes.PlayerMoveDecoder.isReady()
                    + " lp=" + lpX + "," + lpY + " k=" + k
                    + " zoom=" + settings.radarScaleBar
                    + " badges=" + settings.radarTierBadges + " icon=" + settings.radarIconScale);
        }

        canvas.restore();
    }

    private String lastFailedSection;
    private long lastFailedLogTime;

    /** 主城暂停提示：背景（调用处已画）之上居中的“主城 · 雷达已暂停”，字号随视窗缩放。 */
    private void drawCityPauseHint(Canvas canvas, int width, int height)
    {
        final float cx = width / 2f;
        final float cy = height / 2f;

        entityTextPaint.setColor(0xFFFFFFFF);
        entityTextPaint.setTextSize(Math.max(12f, width * 0.072f));
        canvas.drawText("主城 · 雷达已暂停", cx, cy, entityTextPaint);

        long now = System.currentTimeMillis();

        if (now - lastCityPauseLogTime >= 3000)
        {
            lastCityPauseLogTime = now;
            Log.d(TAG, "CITY PAUSE: render skipped (mode=" + ZoneState.pauseMode
                    + " zone=" + ZoneState.zoneId + " name=" + ZoneState.zoneName + ")");
        }
    }

    private void logSectionFailure(String section, Throwable e)
    {
        long now = System.currentTimeMillis();

        if (now - lastFailedLogTime > 5000 || !section.equals(lastFailedSection))
        {
            lastFailedSection = section;
            lastFailedLogTime = now;
            Log.e(TAG, "draw section failed: " + section + " : " + e);
        }
    }

    private void drawRangeRings(Canvas canvas, float cx, float cy, float k)
    {
        // PC 版 HUD 距离环：10 / 20 码（超出视窗自动跳过）
        float[] distances = new float[]{10f, 20f, 40f};

        for (float distance : distances)
        {
            float ringRadius = distance * k;

            if (ringRadius < 8f || ringRadius > Math.min(cx, cy) - 4f)
            {
                continue;
            }

            canvas.drawCircle(cx, cy, ringRadius, gridPaint);
        }

        // 中间十字准星
        float arm = 6f;
        canvas.drawLine(cx - arm, cy, cx + arm, cy, gridPaint);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, gridPaint);
    }

    // ------------------------------------------------------------------
    // 采集物
    // ------------------------------------------------------------------
    private int drawHarvestables(Canvas canvas, float lpX, float lpY, float k,
                                 float cx, float cy, int width, int height)
    {
        final RadarSettings settings = RadarSettings.getInstance();
        final MainHandler handler = MainHandler.getInstance();
        final ArrayList<Harvestable> harvestables = handler.harvestablesHandler.getHarvestableList();
        final float iconPx = iconSize(width);
        final float half = iconPx / 2f;
        final float margin = iconPx + 8f;
        int drawn = 0;

        for (Harvestable harvestable : harvestables)
        {
            String kind = visibleStaticKind(settings, harvestable);

            if (kind == null)
            {
                continue;
            }

            // 解析层 tier 已是 1 基（T4 资源 = 4，2026-11-27 实测 HARV1/HARV2 与游戏内地图 IV-VI 吻合），
            // 显示层直接使用，不再 +1（此前 +1 导致整体高一级：T4 显示 T5）。
            int tier = Math.max(1, Math.min(8, harvestable.getTier()));
            int enchant = Math.max(0, Math.min(4, harvestable.getEnchant()));

            projectToScreen(harvestable.getPosX(), harvestable.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            if (isClusterMember(settings, harvestable.getPosX(), harvestable.getPosY(),
                    kindIndex(kind), false))
            {
                continue;
            }

            drawResourceMarker(canvas, tmpScreen[0], tmpScreen[1], iconPx, kind, tier, enchant,
                    false, width, harvestable.getCharges(), lpX, lpY,
                    harvestable.getPosX(), harvestable.getPosY(), harvestable.getTier());

            drawn++;
        }

        return drawn;
    }

    /** 画一个资源/活体资源标记（图标或色块 + 等级角标 + 可选数量/距离）。 */
    private void drawResourceMarker(Canvas canvas, float screenX, float screenY, float iconPx,
                                    String kind, int tier, int enchant, boolean living, int width,
                                    int charges, float lpX, float lpY, float worldX, float worldY,
                                    int rawTier)
    {
        RadarSettings settings = RadarSettings.getInstance();
        float half = iconPx / 2f;
        int categoryColor = resourceCategoryColor(kind);

        if (settings.radarTierBadges)
        {
            // PC 的“等级色块角标”模式：不画图标，直接按类别色块 + T/E 角标
            entityPaint.setColor(categoryColor);
            tmpIconRect.set(screenX - half, screenY - half, screenX + half, screenY + half);
            canvas.drawRect(tmpIconRect, entityPaint);
        }
        else
        {
            Bitmap icon = getResourceIcon(kind, tier, enchant, (int) iconPx);

            if (icon != null)
            {
                tmpIconRect.set(screenX - half, screenY - half, screenX + half, screenY + half);
                canvas.drawBitmap(icon, null, tmpIconRect, null);
            }
            else
            {
                // 缺图兜底：按资源类别画彩色方块，而不是什么都不画
                entityPaint.setColor(categoryColor);
                tmpIconRect.set(screenX - half, screenY - half, screenX + half, screenY + half);
                canvas.drawRect(tmpIconRect, entityPaint);
            }
        }

        if (living)
        {
            // 活体资源金框（PC 版 isLiving 的金色描边）
            borderPaint.setColor(COLOR_MOB_LIVING);
            tmpRect.set(screenX - half, screenY - half, screenX + half, screenY + half);
            canvas.drawRect(tmpRect, borderPaint);
            borderPaint.setColor(getContext().getColor(R.color.colorBlue));
        }

        drawTierBadge(canvas, screenX, screenY, iconPx, living ? COLOR_MOB_LIVING : categoryColor,
                tier, enchant, living, rawTier);

        float textY = screenY + iconPx * 0.85f;
        entityTextPaint.setColor(0xFFE0E0E0);
        entityTextPaint.setTextSize(Math.max(9f, iconPx * 0.38f));

        if (settings.radarResourceCount || settings.harvestingSize)
        {
            if (charges > 0)
            {
                canvas.drawText(String.valueOf(charges), screenX, textY, entityTextPaint);
                textY += iconPx * 0.32f;
            }
        }

        if (settings.radarResourceDistance)
        {
            float distance = (float) Math.hypot(worldX - lpX, worldY - lpY);
            entityTextPaint.setColor(0xFFB0BEC5);
            canvas.drawText(Math.round(distance) + "m", screenX, textY, entityTextPaint);
        }
    }

    /** 静态资源可见性：不可见返回 null，可见返回统一类别 key（wood/rock/fiber/hide/ore）。 */
    private String visibleStaticKind(RadarSettings settings, Harvestable harvestable)
    {
        if (!isGroundPosition(harvestable.getPosX(), harvestable.getPosY()))
        {
            return null;
        }

        if (harvestable.getCharges() <= 0)
        {
            // PC 版同样跳过 size<=0 的已被采完的节点
            return null;
        }

        int type = harvestable.getType();
        String kind = RadarSettings.normalizeKind(resourceKindOfType(type));

        if (kind == null || !settings.isHarvestableTypeEnabled(type))
        {
            return null;
        }

        // 解析层 tier 已是 1 基（T4=4），显示层与过滤层都直接使用，不再 +1。
        int tier = Math.max(1, Math.min(8, harvestable.getTier()));
        int enchant = Math.max(0, Math.min(4, harvestable.getEnchant()));

        return settings.isStaticResourceEnabled(kind, tier, enchant) ? kind : null;
    }

    // ------------------------------------------------------------------
    // 怪物
    // ------------------------------------------------------------------
    private int drawMobs2(Canvas canvas, float lpX, float lpY, float k,
                          float cx, float cy, int width, int height)
    {
        final RadarSettings settings = RadarSettings.getInstance();
        final MainHandler handler = MainHandler.getInstance();
        final ArrayList<Mob> mobs = handler.mobsHandler.getMobList();
        final float iconPx = iconSize(width);
        final float half = iconPx / 2f;
        final float margin = iconPx + 8f;
        final float baseRadius = Math.max(5f, width * 0.012f);
        int drawn = 0;

        for (Mob mob : mobs)
        {
            if (!isGroundPosition(mob.getPosX(), mob.getPosY()))
            {
                continue;
            }

            MobCodes type = mob.getType();

            if (type == null)
            {
                type = MobCodes.Enemy;
            }

            int tier = mob.getTier();
            int enchant = mob.getEnchantmentLevel();
            int clampedTier = Math.max(0, Math.min(8, tier));
            int clampedEnchant = Math.max(0, Math.min(4, enchant));

            if (isLivingResourceType(type))
            {
                // 防御性过滤：T1 活体资源默认完全不渲染（解析层 MobsHandler 已经丢弃，
                // 这里再兜底一次，避免其它途径加入的 T1 活体资源被画出来）。
                if (MobsResourceTable.isTier1LivingHidden(clampedTier))
                {
                    continue;
                }

                // 活体资源（资源怪）：PC 语义 = 资源页的 Living 子项，按 类别 + 等级/附魔矩阵 控制；
                // 未知 tier/enchant 默认显示（此前套用怪物等级矩阵，会把这类实体静默滤掉）。
                if (!isLivingResourceVisible(settings, mob, type))
                {
                    continue;
                }

                String kind = livingKindOf(mob);

                projectToScreen(mob.getPosX(), mob.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

                if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
                {
                    continue;
                }

                if (isClusterMember(settings, mob.getPosX(), mob.getPosY(), kindIndex(kind), true))
                {
                    continue;
                }

                if (kind != null)
                {
                    drawResourceMarker(canvas, tmpScreen[0], tmpScreen[1], iconPx, kind,
                            clampedTier, clampedEnchant, true, width, 0, lpX, lpY,
                            mob.getPosX(), mob.getPosY(), tier);
                }
                else
                {
                    // 未知类别（权威表未收录的活体资源）：金色圆点 + 已知等级角标兜底
                    entityPaint.setColor(COLOR_MOB_LIVING);
                    canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius * 1.4f, entityPaint);

                    if (clampedTier >= 1)
                    {
                        drawTierBadge(canvas, tmpScreen[0], tmpScreen[1], iconPx,
                                COLOR_MOB_LIVING, clampedTier, clampedEnchant, true, tier);
                    }
                }

                if (settings.mobHp && mob.getHealth() > 0)
                {
                    drawHealthBar(canvas, tmpScreen[0], tmpScreen[1] + iconPx * 0.62f,
                            mobHealthRatio(mob), width);
                }

                if (livingDiagCount++ < 25)
                {
                    Log.d(TAG, "living drawn id=" + mob.getId() + " typeId=" + mob.getTypeId()
                            + " kind=" + kind + " tier=" + tier + " enchant=" + enchant
                            + " pos=" + mob.getPosX() + "," + mob.getPosY());
                }

                drawn++;
                continue;
            }

            // 普通怪物：PC 的类别开关 + 等级/附魔矩阵（未知等级默认显示）
            if (!isHostileVisible(settings, type))
            {
                continue;
            }

            boolean tierOk = clampedTier < 1 || settings.mobTiers[clampedTier - 1];
            boolean enchantOk = enchant < 0 || enchant >= settings.mobEnchants.length
                    || settings.mobEnchants[clampedEnchant];

            if (!tierOk || !enchantOk)
            {
                continue;
            }

            projectToScreen(mob.getPosX(), mob.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            int color = hostileColor(type);
            float radius = baseRadius;

            if (type == MobCodes.Boss || type == MobCodes.MiniBoss)
            {
                radius = baseRadius * 1.6f;
            }
            else if (type == MobCodes.EnchantedEnemy)
            {
                radius = baseRadius * 1.3f;
            }
            else if (type == MobCodes.MistBoss)
            {
                radius = baseRadius * 1.5f;
            }

            entityPaint.setColor(0xB3000000);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], radius + 2.5f, entityPaint);
            entityPaint.setColor(color);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], radius, entityPaint);

            // 血条：怪物开关打开时显示
            if (settings.mobHp && mob.getHealth() > 0)
            {
                drawHealthBar(canvas, tmpScreen[0], tmpScreen[1] + baseRadius * 2.4f,
                        mobHealthRatio(mob), width);
            }

            drawn++;
        }

        return drawn;
    }

    private static boolean isLivingResourceType(MobCodes type)
    {
        return type == MobCodes.Harvestable || type == MobCodes.Skinnable;
    }

    /** 活体资源是否显示（资源页 Living 语义 + 类别/等级矩阵，未知等级默认显示）。 */
    private boolean isLivingResourceVisible(RadarSettings settings, Mob mob, MobCodes type)
    {
        boolean typeEnabled = type == MobCodes.Harvestable ? settings.mobHarvestable : settings.mobSkinnable;

        if (!typeEnabled)
        {
            return false;
        }

        String kind = livingKindOf(mob);

        if (kind == null)
        {
            // 权威表未收录但被标成活体资源：默认显示，避免“解析到了却看不到”
            return true;
        }

        // 「资源 · 类别 · 总开关」对 Static 与 Living 同时生效
        if (!settings.isResourceKindEnabled(kind))
        {
            return false;
        }

        return settings.isLivingResourceEnabled(kind, mob.getTier(), mob.getEnchantmentLevel());
    }

    private static String livingKindOf(Mob mob)
    {
        return RadarSettings.normalizeKind(mob.getName());
    }

    /** 普通怪物类别开关（PC Enemies 分组）。 */
    private static boolean isHostileVisible(RadarSettings settings, MobCodes type)
    {
        switch (type)
        {
            case Boss:
                return settings.mobBoss;
            case MiniBoss:
                return settings.mobMiniBoss;
            case EnchantedEnemy:
            case MediumEnemy:
            case Enemy:
                return settings.mobEnemy;
            case Drone:
                return settings.mobDrone;
            case MistBoss:
            case MistPortal:
                return settings.mobMist;
            case Events:
                return settings.mobEvents;
            case Guard:
                return settings.mobGuard;
            default:
                return settings.mobOther;
        }
    }

    private static int hostileColor(MobCodes type)
    {
        switch (type)
        {
            case Boss:
                return COLOR_MOB_BOSS;
            case MiniBoss:
                return COLOR_MOB_MINIBOSS;
            case EnchantedEnemy:
                return COLOR_MOB_ENCHANTED;
            case MediumEnemy:
                return COLOR_MOB_MEDIUM;
            case Drone:
                return COLOR_MOB_DRONE;
            case MistBoss:
                return COLOR_MOB_MISTBOSS;
            case Events:
                return COLOR_MOB_EVENTS;
            case Guard:
                return COLOR_MOB_GUARD;
            case MistPortal:
                return COLOR_MOB_PORTAL;
            case Enemy:
            default:
                return COLOR_MOB_ENEMY;
        }
    }

    // ------------------------------------------------------------------
    // 玩家（对齐 PC PlayersDrawing：公会绿 / 联盟紫 / 阵营橙 / 敌对红 / 中立蓝）
    // ------------------------------------------------------------------
    private int drawPlayers2(Canvas canvas, float lpX, float lpY, float k,
                             float cx, float cy, int width, int height)
    {
        final RadarSettings settings = RadarSettings.getInstance();

        if (!settings.playerDot)
        {
            return 0;
        }

        final MainHandler handler = MainHandler.getInstance();
        final ArrayList<Player> players = handler.playersHandler.getPlayersInRange();
        final String localGuild = handler.playersHandler.localPlayerGuildName();
        final String localAlliance = handler.playersHandler.localPlayerAlliance();
        final float localFaction = handler.playersHandler.localPlayerFaction();
        final float baseRadius = Math.max(5f, width * 0.013f);
        final float margin = baseRadius * 3f;
        final int maxDisplay = Math.max(1, settings.playerMaxDisplay);
        int drawn = 0;

        for (Player player : players)
        {
            if (drawn >= maxDisplay)
            {
                break;
            }

            // 坐标还没解出来（玩家坐标是 XOR 加密的）：不瞎画，交给右上角“已发现玩家”面板
            if (!player.positionKnown || !isGroundPosition(player.getPosX(), player.getPosY()))
            {
                continue;
            }

            double distance = Utils.calculateDistance(player.getPosX(), player.getPosY(), lpX, lpY);

            if (distance > Utils.MaxDistance)
            {
                continue;
            }

            int playerColor = playerColor(player, localGuild, localAlliance, localFaction);

            if (!isPlayerGroupVisible(settings, playerColor))
            {
                continue;
            }

            projectToScreen(player.getPosX(), player.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            // 外圈 + 实心点（PC：彩色分类 + 中心点）
            entityPaint.setColor(playerColor);
            entityPaint.setAlpha(100);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius * 1.7f, entityPaint);
            entityPaint.setAlpha(255);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius, entityPaint);

            playerRingPaint.setColor(playerColor);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius * 1.7f, playerRingPaint);

            float textY = tmpScreen[1] - baseRadius * 2.4f;
            entityTextPaint.setColor(0xFFFFFFFF);
            entityTextPaint.setTextSize(Math.max(10f, width * 0.026f));

            if (settings.playerNickname)
            {
                String nickname = player.getNickname();

                if (nickname != null && !nickname.isEmpty() && !"null".equals(nickname))
                {
                    canvas.drawText(ellipsize(nickname, 16), tmpScreen[0], textY, entityTextPaint);
                    textY -= entityTextPaint.getTextSize() * 1.15f;
                }
            }

            if (settings.playerGuildName)
            {
                String guild = player.getGuild();

                if (guild != null && !guild.isEmpty() && !"null".equals(guild))
                {
                    canvas.drawText(ellipsize(guild, 16), tmpScreen[0], textY, entityTextPaint);
                    textY -= entityTextPaint.getTextSize() * 1.15f;
                }
            }

            if (settings.playerDistance)
            {
                canvas.drawText(Math.round(distance) + "m", tmpScreen[0], textY, entityTextPaint);
            }

            if (settings.playerMounted && player.mounted)
            {
                canvas.drawText("M", tmpScreen[0] + baseRadius * 1.9f,
                        tmpScreen[1] + baseRadius * 0.7f, entityTextPaint);
            }

            if (settings.playerHealth && player.getMaxHealth() > 0)
            {
                drawHealthBar(canvas, tmpScreen[0], tmpScreen[1] + baseRadius * 2.1f,
                        player.currentHealth / player.getMaxHealth(), width);
            }

            drawn++;
        }

        if (System.currentTimeMillis() - lastPlayerDiagTime > 3000)
        {
            lastPlayerDiagTime = System.currentTimeMillis();
            Log.d(TAG, "players detected=" + players.size() + " drawn=" + drawn
                    + " xorReady=" + com.minhui.vpn.PhotonPackageParser.classes.PlayerMoveDecoder.isReady());
        }

        return drawn;
    }

    /** PC 配色：敌对红 / 公会绿 / 联盟紫 / 阵营橙 / 中立蓝。 */
    private static int playerColor(Player player, String localGuild, String localAlliance,
                                   float localFaction)
    {
        float faction = player.getFaction();

        if (faction == 255f)
        {
            return COLOR_PLAYER_HOSTILE;
        }

        if (localGuild != null && !localGuild.isEmpty() && localGuild.equals(player.getGuild()))
        {
            return COLOR_PLAYER_GUILD;
        }

        if (localAlliance != null && !localAlliance.isEmpty() && player.getGuild() != null
                && localAlliance.contains(player.getGuild()))
        {
            return COLOR_PLAYER_ALLIANCE;
        }

        if (faction >= 1 && faction <= 6 && localFaction != 0f && localFaction == faction)
        {
            return COLOR_PLAYER_FACTION;
        }

        if (faction >= 1 && faction <= 6)
        {
            return COLOR_PLAYER_FACTION;
        }

        return COLOR_PLAYER_OTHER;
    }

    /** 玩家分组过滤（被动 / 阵营 / 敌对）。 */
    private static boolean isPlayerGroupVisible(RadarSettings settings, int color)
    {
        if (color == COLOR_PLAYER_HOSTILE)
        {
            return settings.playerHostile;
        }

        if (color == COLOR_PLAYER_FACTION)
        {
            return settings.playerFactionColor;
        }

        if (color == COLOR_PLAYER_ALLIANCE)
        {
            return settings.playerAllianceColor;
        }

        return settings.playerPassive;
    }

    /** 右上角“已发现玩家”面板：坐标解不出来的玩家也能被看到（PC 也只能做到“发现”）。 */
    private void drawPlayerPanel(Canvas canvas, int width, int height, ArrayList<Player> players)
    {
        final RadarSettings settings = RadarSettings.getInstance();

        if (!settings.playerPanel || players.isEmpty())
        {
            return;
        }

        float textSize = Math.max(10f, width * 0.024f);
        int maxRows = Math.min(players.size(), 7);
        float rowHeight = textSize * 1.35f;
        float padding = Math.max(4f, width * 0.01f);
        float boxWidth = Math.max(width * 0.32f, 120f);
        float boxHeight = rowHeight * (maxRows + 1) + padding * 2f;
        float left = width - boxWidth - padding;
        float top = padding;

        tmpRect.set(left, top, left + boxWidth, top + boxHeight);
        canvas.drawRoundRect(tmpRect, 6f, 6f, panelBackPaint);

        panelTextPaint.setTextSize(textSize);
        panelTextPaint.setColor(0xFFFFD54F);
        canvas.drawText("已发现玩家 " + players.size(), left + padding, top + padding + textSize, panelTextPaint);

        panelTextPaint.setTextSize(textSize * 0.92f);

        for (int i = 0; i < maxRows; i++)
        {
            Player player = players.get(i);
            int color = playerColor(player, MainHandler.getInstance().playersHandler.localPlayerGuildName(),
                    MainHandler.getInstance().playersHandler.localPlayerAlliance(),
                    MainHandler.getInstance().playersHandler.localPlayerFaction());

            float dotY = top + padding + textSize * 1.9f + i * rowHeight;
            entityPaint.setColor(color);
            canvas.drawCircle(left + padding + textSize * 0.35f, dotY - textSize * 0.3f,
                    textSize * 0.35f, entityPaint);

            String name = player.getNickname();

            if (name == null || name.isEmpty() || "null".equals(name))
            {
                name = "#" + player.getId();
            }

            panelTextPaint.setColor(player.positionKnown ? 0xFFE0E0E0 : 0xFF9E9E9E);
            canvas.drawText(ellipsize(name, 14), left + padding + textSize * 1.0f, dotY, panelTextPaint);
        }
    }

    // ------------------------------------------------------------------
    // 箱子 / 钓鱼区
    // ------------------------------------------------------------------
    private int drawChests2(Canvas canvas, float lpX, float lpY, float k,
                            float cx, float cy, int width, int height)
    {
        final RadarSettings settings = RadarSettings.getInstance();
        final MainHandler handler = MainHandler.getInstance();
        final float size = Math.max(6f, width * 0.018f);
        int drawn = 0;

        for (com.minhui.vpn.Handlers.HandlerItem.Chest chest : handler.chestHandler.getChests())
        {
            if (!isGroundPosition(chest.getPosX(), chest.getPosY()))
            {
                continue;
            }

            String chestName = chest.getName();

            if (chestName == null || !settings.isInChests(chestName))
            {
                continue;
            }

            projectToScreen(chest.getPosX(), chest.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, size))
            {
                continue;
            }

            String lower = chestName.toLowerCase();
            entityPaint.setColor(lower.contains("legendary") ? 0xFFFFD54F
                    : lower.contains("rare") ? 0xFF29B6F6
                    : lower.contains("uncommon") ? 0xFF66BB6A
                    : 0xFFBDBDBD);
            canvas.drawRect(tmpScreen[0] - size, tmpScreen[1] - size,
                    tmpScreen[0] + size, tmpScreen[1] + size, entityPaint);

            drawn++;
        }

        return drawn;
    }

    private int drawFishingZones(Canvas canvas, float lpX, float lpY, float k,
                                 float cx, float cy, int width, int height)
    {
        final RadarSettings settings = RadarSettings.getInstance();

        if (!settings.harvestingZoneFishing)
        {
            return 0;
        }

        final MainHandler handler = MainHandler.getInstance();
        final float radius = Math.max(5f, width * 0.014f);
        int drawn = 0;

        for (com.minhui.vpn.Handlers.HandlerItem.FishingZone zone : handler.fishingZoneHandler.getFishingZoneList())
        {
            if (!isGroundPosition(zone.getPosX(), zone.getPosY()))
            {
                continue;
            }

            projectToScreen(zone.getPosX(), zone.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, radius * 2f))
            {
                continue;
            }

            entityPaint.setColor(0xFF26C6DA);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], radius, entityPaint);
            drawn++;
        }

        return drawn;
    }

    // ------------------------------------------------------------------
    // 通用绘制辅助
    // ------------------------------------------------------------------
    private float iconSize(int width)
    {
        // PC: 32px * canvasScale(500 基准) * zoom * settingRadarIconSize，最小可读尺寸 20px
        float zoom = RadarSettings.getInstance().radarScaleBar;

        if (!Float.isFinite(zoom) || zoom <= 0.05f)
        {
            zoom = 1f;
        }

        zoom = Math.max(0.1f, Math.min(4f, zoom));

        float iconScale = RadarSettings.getInstance().radarIconScale;

        if (!Float.isFinite(iconScale) || iconScale <= 0.05f)
        {
            iconScale = 1f;
        }

        iconScale = Math.max(0.5f, Math.min(2f, iconScale));

        return Math.max(14f, Math.min(80f, 32f * (width / PC_REFERENCE_CANVAS) * zoom * iconScale));
    }

    /** 资源等级角标：图标左上角画 T{tier}，有附魔再画 +N（PC badge 语义）。
     *  {@code rawTier} 只用于「等级调试角标」（{@link RadarSettings#resTierDebugBadge}）显示解析层原值。 */
    private void drawTierBadge(Canvas canvas, float x, float y, float iconPx, int color,
                               int tier, int enchant, boolean living, int rawTier)
    {
        final boolean debugTier = RadarSettings.getInstance().resTierDebugBadge;
        float badgeH = Math.max(11f, iconPx * 0.42f);
        float badgeW = badgeH * (enchant > 0 ? 2.5f : 1.35f);

        if (debugTier)
        {
            // 额外显示原始 tier（例如 T5r4），需要更宽的底
            badgeW = badgeH * (enchant > 0 ? 4.2f : 2.6f);
        }

        float left = x - iconPx / 2f;
        float top = y - iconPx / 2f;

        tmpRect.set(left, top, left + badgeW, top + badgeH);
        entityPaint.setColor(0xCC000000);
        canvas.drawRect(tmpRect, entityPaint);

        entityPaint.setColor(color);
        tmpRect.set(left, top, left + 3f, top + badgeH);
        canvas.drawRect(tmpRect, entityPaint);

        badgeTextPaint.setColor(0xFFFFFFFF);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);

        String tierText = tier >= 1 ? "T" + tier : "T?";

        if (debugTier)
        {
            tierText = tierText + "r" + rawTier;
        }

        if (enchant > 0)
        {
            badgeTextPaint.setTextSize(badgeH * (debugTier ? 0.62f : 0.82f));
            canvas.drawText(tierText, left + badgeW * (debugTier ? 0.32f : 0.28f),
                    top + badgeH * 0.82f, badgeTextPaint);
            canvas.drawText("+" + enchant, left + badgeW * (debugTier ? 0.82f : 0.74f),
                    top + badgeH * 0.82f, badgeTextPaint);
        }
        else
        {
            badgeTextPaint.setTextSize(badgeH * (debugTier ? 0.62f : 0.86f));
            canvas.drawText(tierText, left + badgeW * 0.55f, top + badgeH * 0.84f, badgeTextPaint);
        }

        if (living)
        {
            // 活体资源：金色描边已在外部画过，这里补一个角标金边
            entityPaint.setColor(COLOR_MOB_LIVING);
            canvas.drawRect(left, top, left + 3f, top + badgeH, entityPaint);
        }
    }

    /** 怪物血条比例：解析层只有当前血量，把首次观测值当作满血基准。 */
    private float mobHealthRatio(Mob mob)
    {
        int current = mob.getHealth();

        if (current <= 0)
        {
            return 0f;
        }

        Integer max = mobMaxHealth.get(mob.getId());

        if (max == null || current > max)
        {
            max = current;
            mobMaxHealth.put(mob.getId(), max);

            if (mobMaxHealth.size() > 4000)
            {
                mobMaxHealth.clear();
            }
        }

        return Math.max(0f, Math.min(1f, (float) current / (float) max));
    }

    /** 通用血条：ratio 为 0..1。 */
    private void drawHealthBar(Canvas canvas, float x, float y, float ratio, int width)
    {
        float barWidth = Math.max(20f, width * 0.09f);
        float barHeight = Math.max(3f, width * 0.008f);

        if (Float.isNaN(ratio) || Float.isInfinite(ratio))
        {
            return;
        }

        float percent = Math.max(0f, Math.min(1f, ratio));

        hpBackPaint.setColor(0xCC000000);
        canvas.drawRect(x - barWidth / 2f, y, x + barWidth / 2f, y + barHeight, hpBackPaint);

        if (percent > 0.75f)
        {
            hpFillPaint.setColor(0xFF00E676);
        }
        else if (percent > 0.5f)
        {
            hpFillPaint.setColor(0xFFCDDC39);
        }
        else if (percent > 0.25f)
        {
            hpFillPaint.setColor(0xFFFF9800);
        }
        else
        {
            hpFillPaint.setColor(0xFFFF1744);
        }

        canvas.drawRect(x - barWidth / 2f, y, x - barWidth / 2f + barWidth * percent, y + barHeight, hpFillPaint);
    }

    private void drawHud(Canvas canvas, int width, int height,
                         int harvest, int mobs, int detectedPlayers, int drawnPlayers)
    {
        String text = "资源 " + harvest + "   怪物 " + mobs + "   玩家 " + detectedPlayers;

        if (drawnPlayers < detectedPlayers)
        {
            // 坐标加密、只能确认存在的玩家：标注“定位中”
            text += "（定位 " + drawnPlayers + "）";
        }

        float textSize = hudPaint.getTextSize();
        float padding = Math.max(4f, width * 0.012f);
        float textWidth = hudPaint.measureText(text);
        float boxW = textWidth + padding * 2f;
        float boxH = textSize + padding * 1.4f;
        float left = padding;
        float top = height - boxH - padding;

        tmpRect.set(left, top, left + boxW, top + boxH);
        canvas.drawRoundRect(tmpRect, boxH / 3f, boxH / 3f, hudBackPaint);
        canvas.drawText(text, left + padding, top + textSize + padding * 0.2f, hudPaint);
    }

    private static boolean isOnScreen(float x, float y, int width, int height, float margin)
    {
        return x >= -margin && y >= -margin && x <= width + margin && y <= height + margin;
    }

    private static String ellipsize(String text, int max)
    {
        if (text == null)
        {
            return "";
        }

        if (text.length() <= max)
        {
            return text;
        }

        return text.substring(0, max - 3) + "...";
    }

    /** 兼容旧设置页：文本大小/边框大小等历史接口。 */
    public void setTextSize(int size)
    {
        entityTextPaint.setTextSize(Math.max(8f, size));
    }
}
