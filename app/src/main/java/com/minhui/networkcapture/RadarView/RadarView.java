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

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.HashMap;

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

    private long lastDiagLogTime;
    private long lastTrimTime;
    private long lastDrawnLogTime;
    private long fpsWindowStart;
    private int frameCount;
    private int lastDrawnHarvest;
    private int lastDrawnMobs;
    private int lastDrawnPlayers;
    private int lastDrawnChests;
    private int lastDrawnFishing;

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

    /** 名称里的资源类别（与 PC 版 getResourceCategory 一致的语义）。 */
    private static String resourceKindOf(String name)
    {
        if (name == null)
        {
            return null;
        }

        String n = name.toLowerCase();

        if (n.contains("fiber"))
        {
            return "fiber";
        }

        if (n.contains("hide"))
        {
            return "hide";
        }

        if (n.contains("wood") || n.contains("log"))
        {
            return "log";
        }

        if (n.contains("ore"))
        {
            return "ore";
        }

        if (n.contains("rock"))
        {
            return "rock";
        }

        return null;
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

        switch (kind)
        {
            case "fiber":
                return COLOR_RES_FIBER;
            case "hide":
                return COLOR_RES_HIDE;
            case "log":
                return COLOR_RES_WOOD;
            case "ore":
                return COLOR_RES_ORE;
            case "rock":
                return COLOR_RES_ROCK;
            default:
                return COLOR_RES_UNKNOWN;
        }
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

        canvas.drawRoundRect(0, 0, width, height, radius, radius, backgroundPaint);
        canvas.drawRoundRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, radius, radius, borderPaint);

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

        drawRangeRings(canvas, cx, cy, k);

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

        // 自己：相机跟随，永远在视窗中心
        float selfRadius = Math.max(7f, width * 0.016f);
        canvas.drawCircle(cx, cy, selfRadius, selfPaint);
        canvas.drawCircle(cx, cy, selfRadius + Math.max(5f, width * 0.012f), selfRingPaint);

        lastDrawnHarvest = drawnHarvest;
        lastDrawnMobs = drawnMobs;
        lastDrawnPlayers = drawnPlayers;
        lastDrawnChests = drawnChests;
        lastDrawnFishing = drawnFishing;

        drawHud(canvas, width, height, drawnHarvest, drawnMobs, drawnPlayers);

        if (now - lastDrawnLogTime >= 2000)
        {
            lastDrawnLogTime = now;
            Log.d(TAG, "direct drawn h=" + drawnHarvest + " m=" + drawnMobs + " p=" + drawnPlayers
                    + " c=" + drawnChests + " f=" + drawnFishing
                    + " lp=" + lpX + "," + lpY + " k=" + k
                    + " zoom=" + RadarSettings.getInstance().radarScaleBar);
        }

        canvas.restore();
    }

    private String lastFailedSection;
    private long lastFailedLogTime;

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
            if (!isGroundPosition(harvestable.getPosX(), harvestable.getPosY()))
            {
                continue;
            }

            if (harvestable.getCharges() <= 0)
            {
                // PC 版同样跳过 size<=0 的已被采完的节点
                continue;
            }

            int type = harvestable.getType();
            String kind = resourceKindOfType(type);

            if (kind == null || !settings.isHarvestableTypeEnabled(type))
            {
                continue;
            }

            // 解析层 tier 是 0 基（T2 资源 tier=1），显示层统一换算成 1..8
            int tier = Math.max(1, Math.min(8, harvestable.getTier() + 1));
            int enchant = Math.max(0, Math.min(4, harvestable.getEnchant()));

            if (!settings.harvestingTiers[tier - 1]
                    || enchant >= settings.harvestingEnchants.length
                    || !settings.harvestingEnchants[enchant])
            {
                continue;
            }

            projectToScreen(harvestable.getPosX(), harvestable.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            Bitmap icon = getResourceIcon(kind, tier, enchant, (int) iconPx);

            if (icon != null)
            {
                tmpIconRect.set(tmpScreen[0] - half, tmpScreen[1] - half,
                        tmpScreen[0] + half, tmpScreen[1] + half);
                canvas.drawBitmap(icon, null, tmpIconRect, null);
            }
            else
            {
                // 缺图兜底：按资源类别画彩色方块，而不是什么都不画
                entityPaint.setColor(resourceCategoryColor(kind));
                tmpIconRect.set(tmpScreen[0] - half, tmpScreen[1] - half,
                        tmpScreen[0] + half, tmpScreen[1] + half);
                canvas.drawRect(tmpIconRect, entityPaint);
            }

            drawTierBadge(canvas, tmpScreen[0], tmpScreen[1], iconPx, resourceCategoryColor(kind), tier, enchant, false);

            if (settings.harvestingSize)
            {
                entityTextPaint.setColor(0xFFE0E0E0);
                entityTextPaint.setTextSize(Math.max(9f, iconPx * 0.38f));
                canvas.drawText(String.valueOf(harvestable.getCharges()),
                        tmpScreen[0], tmpScreen[1] + iconPx * 0.85f, entityTextPaint);
            }

            drawn++;
        }

        return drawn;
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

            boolean isLivingResource = type == MobCodes.Harvestable || type == MobCodes.Skinnable;
            int color;
            boolean show;

            if (isLivingResource)
            {
                boolean typeEnabled = type == MobCodes.Harvestable ? settings.mobHarvestable : settings.mobSkinnable;
                int tier = mob.getTier();
                int enchant = Math.max(0, Math.min(4, mob.getEnchantmentLevel()));
                boolean tierOk = tier >= 1 && tier <= settings.mobTiers.length && settings.mobTiers[tier - 1];
                boolean enchantOk = enchant < settings.mobEnchants.length && settings.mobEnchants[enchant];

                show = typeEnabled && tierOk && enchantOk;
                color = COLOR_MOB_LIVING;
            }
            else
            {
                switch (type)
                {
                    case Boss:
                        show = settings.mobBoss;
                        color = COLOR_MOB_BOSS;
                        break;
                    case MiniBoss:
                        show = settings.mobBoss || settings.mobOther;
                        color = COLOR_MOB_MINIBOSS;
                        break;
                    case EnchantedEnemy:
                        show = settings.mobOther;
                        color = COLOR_MOB_ENCHANTED;
                        break;
                    case MediumEnemy:
                        show = settings.mobOther;
                        color = COLOR_MOB_MEDIUM;
                        break;
                    case Drone:
                        show = settings.mobOther;
                        color = COLOR_MOB_DRONE;
                        break;
                    case MistBoss:
                        show = settings.mobOther;
                        color = COLOR_MOB_MISTBOSS;
                        break;
                    case Events:
                        show = settings.mobOther;
                        color = COLOR_MOB_EVENTS;
                        break;
                    case Guard:
                        show = settings.mobOther;
                        color = COLOR_MOB_GUARD;
                        break;
                    case MistPortal:
                        show = settings.mobOther;
                        color = COLOR_MOB_PORTAL;
                        break;
                    case Enemy:
                    default:
                        show = settings.mobOther;
                        color = COLOR_MOB_ENEMY;
                        break;
                }
            }

            if (!show)
            {
                continue;
            }

            projectToScreen(mob.getPosX(), mob.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            if (isLivingResource)
            {
                String kind = resourceKindOf(mob.getName());
                int tier = Math.max(1, Math.min(8, mob.getTier()));
                int enchant = Math.max(0, Math.min(4, mob.getEnchantmentLevel()));
                Bitmap icon = kind == null ? null : getResourceIcon(kind, tier, enchant, (int) iconPx);

                if (icon != null)
                {
                    tmpIconRect.set(tmpScreen[0] - half, tmpScreen[1] - half,
                            tmpScreen[0] + half, tmpScreen[1] + half);
                    canvas.drawBitmap(icon, null, tmpIconRect, null);
                }
                else
                {
                    entityPaint.setColor(COLOR_MOB_LIVING);
                    canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius * 1.4f, entityPaint);
                }

                // 活体资源金框（PC 版 isLiving 时的金色描边）
                borderPaint.setColor(COLOR_MOB_LIVING);
                tmpRect.set(tmpScreen[0] - half, tmpScreen[1] - half,
                        tmpScreen[0] + half, tmpScreen[1] + half);
                canvas.drawRect(tmpRect, borderPaint);
                borderPaint.setColor(getContext().getColor(R.color.colorBlue));

                drawTierBadge(canvas, tmpScreen[0], tmpScreen[1], iconPx, COLOR_MOB_LIVING, tier, enchant, true);
            }
            else
            {
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
            }

            // 血条：怪物开关打开时显示（活体资源同样适用）
            if (settings.mobHp && mob.getHealth() > 0)
            {
                float hpRatio = mobHealthRatio(mob);
                drawHealthBar(canvas, tmpScreen[0], tmpScreen[1] + iconPx * 0.62f, hpRatio, width);
            }

            drawn++;
        }

        return drawn;
    }

    // ------------------------------------------------------------------
    // 玩家
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
        int drawn = 0;

        for (Player player : players)
        {
            if (drawn > Utils.MaxPlayer)
            {
                break;
            }

            if (!isGroundPosition(player.getPosX(), player.getPosY()))
            {
                continue;
            }

            double distance = Utils.calculateDistance(player.getPosX(), player.getPosY(), lpX, lpY);

            if (distance > Utils.MaxDistance)
            {
                continue;
            }

            projectToScreen(player.getPosX(), player.getPosY(), lpX, lpY, k, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height, margin))
            {
                continue;
            }

            int color = COLOR_PLAYER_OTHER;

            if (localGuild != null && !localGuild.isEmpty() && localGuild.equals(player.getGuild()))
            {
                color = COLOR_PLAYER_GUILD;
            }
            else if (localAlliance != null && !localAlliance.isEmpty()
                    && player.getGuild() != null && localAlliance.contains(player.getGuild()))
            {
                color = COLOR_PLAYER_ALLIANCE;
            }
            else if (localFaction != 0f && localFaction == player.getFaction())
            {
                color = COLOR_PLAYER_FACTION;
            }

            // 外圈 + 实心点，和 PC 版“彩色分类 + 中心点”一致
            entityPaint.setColor(color);
            entityPaint.setAlpha(110);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius * 1.55f, entityPaint);
            entityPaint.setAlpha(255);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], baseRadius, entityPaint);

            float textY = tmpScreen[1] - baseRadius * 2.2f;
            entityTextPaint.setColor(0xFFFFFFFF);
            entityTextPaint.setTextSize(Math.max(10f, width * 0.026f));

            if (settings.playerNickname)
            {
                String nickname = player.getNickname();

                if (nickname != null && !nickname.isEmpty())
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
                canvas.drawText("M", tmpScreen[0] + baseRadius * 1.8f,
                        tmpScreen[1] + baseRadius * 0.6f, entityTextPaint);
            }

            if (settings.playerHealth && player.getMaxHealth() > 0)
            {
                drawHealthBar(canvas, tmpScreen[0], tmpScreen[1] + baseRadius * 1.9f,
                        player.currentHealth / player.getMaxHealth(), width);
            }

            drawn++;
        }

        return drawn;
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
        // PC: 32px * canvasScale(500 基准) * zoom，最小可读尺寸 20px
        float zoom = RadarSettings.getInstance().radarScaleBar;

        if (!Float.isFinite(zoom) || zoom <= 0.05f)
        {
            zoom = 1f;
        }

        zoom = Math.max(0.1f, Math.min(4f, zoom));

        return Math.max(20f, Math.min(64f, 32f * (width / PC_REFERENCE_CANVAS) * zoom));
    }

    /** 资源等级角标：图标左上角画 T{tier}，有附魔再画 +N（PC badge 语义）。 */
    private void drawTierBadge(Canvas canvas, float x, float y, float iconPx, int color,
                               int tier, int enchant, boolean living)
    {
        float badgeH = Math.max(11f, iconPx * 0.42f);
        float badgeW = badgeH * (enchant > 0 ? 2.5f : 1.35f);
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

        if (enchant > 0)
        {
            badgeTextPaint.setTextSize(badgeH * 0.82f);
            canvas.drawText("T" + tier, left + badgeW * 0.28f, top + badgeH * 0.82f, badgeTextPaint);
            canvas.drawText("+" + enchant, left + badgeW * 0.74f, top + badgeH * 0.82f, badgeTextPaint);
        }
        else
        {
            badgeTextPaint.setTextSize(badgeH * 0.86f);
            canvas.drawText("T" + tier, left + badgeW * 0.55f, top + badgeH * 0.84f, badgeTextPaint);
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
                         int harvest, int mobs, int players)
    {
        String text = "资源 " + harvest + "   怪物 " + mobs + "   玩家 " + players;
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
