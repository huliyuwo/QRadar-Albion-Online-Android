package com.minhui.networkcapture.RadarView;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.minhui.networkcapture.R;
import com.minhui.vpn.Handlers.HandlerItem.Player;
import com.minhui.vpn.Handlers.MainHandler;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class RadarView extends View
{

    /** 60fps 刷新循环：跟随 vsync 重绘，保证高帧率；EventBus 的 refresh 仍会额外触发。 */
    private final Runnable frameLoop = new Runnable()
    {
        @Override
        public void run()
        {
            invalidate();
            postOnAnimation(this);
        }
    };

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        postOnAnimation(frameLoop);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        removeCallbacks(frameLoop);
        super.onDetachedFromWindow();
    }
    private static final java.util.concurrent.atomic.AtomicInteger INSTANCES = new java.util.concurrent.atomic.AtomicInteger();

    Paint paintCenterObject;
    Matrix transformationMatrix;
    public DrawPlayers drawPlayers = new DrawPlayers();
    public DrawMobs drawMobs = new DrawMobs();
    public HarvestingDraw harvestingDraw = new HarvestingDraw();
    public DrawChests drawChests = new DrawChests();
    public FishingZoneDraw fishingZoneDraw = new FishingZoneDraw();

    Paint borderPaint;
    BitmapCache bitmapCache = new BitmapCache();

    @Subscribe public void onMessage(String event)
    {
        //  Log.d("draw","call");
       invalidate();
    }

    public void init()
    {
        INSTANCES.incrementAndGet();
        Log.d("RadarDiag", "RadarView created instance=" + hashCode() + " live=" + INSTANCES.get());
        EventBus.getDefault().register(this);
        paintCenterObject =new Paint();
        drawPlayers.init(this);
        drawMobs.init(this);
        harvestingDraw.init(this);
        fishingZoneDraw.init(this);
        drawChests.init(this);

        borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(RadarSettings.getInstance().radarCircleSquareBorderBar);
        borderPaint.setColor(getContext().getColor(R.color.colorBlue));

        paintCenterObject.setColor(getContext().getColor(R.color.colorPrimary));
        paintCenterObject.setStyle(Paint.Style.FILL);

        this.post(() -> initMatrix());
    }
    public void setBorderSize(int size)
    {
        borderPaint.setStrokeWidth(size);
    }


    /** 分段绘制：某一段抛异常只丢这一段，并记录一次，避免整帧黑屏查不到原因。 */
    private void drawSection(String name, Runnable action)
    {
        try
        {
            action.run();
        }
        catch (Throwable e)
        {
            if (lastFailedSection == null || !lastFailedSection.equals(name))
            {
                lastFailedSection = name;
                Log.e("RadarDiag", "draw section failed: " + name + " : " + e);
            }
        }
    }

    private Paint entityPaint;
    private Paint entityTextPaint;

    /** 直接按"世界坐标 → 视窗像素"换算绘制所有实体（采集/怪物/玩家）。 */
    private final float[] tmpScreen = new float[2];
    private final float[] tmpScreen2 = new float[2];
    private final RectF tmpIconRect = new RectF();
    private long lastDirectLogTime;

    private void drawEntities(Canvas canvas, float baseX, float baseY, float scale, int width, int height)
    {
        ensureEntityPaints(width);

        final float cx = width / 2f;
        final float cy = height / 2f;
        final RadarSettings settings = RadarSettings.getInstance();
        final MainHandler handler = MainHandler.getInstance();
        final float iconSize = Math.max(30f, width * 0.075f);
        final float dotRadius = Math.max(5f, width * 0.012f);

        int drawnHarvest = 0;
        int drawnMobs = 0;
        int drawnPlayers = 0;
        int drawnChests = 0;
        int drawnFishing = 0;

        // ---- 采集物（类型/等级/附魔过滤，图标命名与 PC 版一致）----
        for (com.minhui.vpn.Handlers.HandlerItem.Harvestable harvestable : handler.harvestablesHandler.getHarvestableList())
        {
            if (!isDrawablePosition(harvestable.getPosX(), harvestable.getPosY()))
            {
                continue;
            }

            int type = harvestable.getType();
            String kind = resourceKindOf(type);

            if (kind == null)
            {
                continue;
            }

            // 采集包里的 tier 是 0 基（T2 区实测为 1），换算成 1..8
            int tier = Math.max(1, Math.min(8, harvestable.getTier() + 1));
            int enchant = harvestable.getEnchant();

            if (!settings.harvestingTiers[tier - 1]
                    || enchant < 0 || enchant >= settings.harvestingEnchants.length
                    || !settings.harvestingEnchants[enchant]
                    || !settings.isInHarvestable(
                            com.minhui.vpn.Handlers.HandlerItem.HarvestableType.values()[type]))
            {
                continue;
            }

            projectToScreen(harvestable.getPosX(), harvestable.getPosY(), baseX, baseY, scale, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height))
            {
                continue;
            }

            Bitmap icon = getResourceIcon(kind + "_" + tier + "_" + enchant);

            if (icon != null)
            {
                tmpIconRect.set(tmpScreen[0] - iconSize / 2f, tmpScreen[1] - iconSize / 2f,
                        tmpScreen[0] + iconSize / 2f, tmpScreen[1] + iconSize / 2f);
                canvas.drawBitmap(icon, null, tmpIconRect, null);
            }
            else
            {
                entityPaint.setColor(0xFF8D6E63);
                canvas.drawCircle(tmpScreen[0], tmpScreen[1], dotRadius, entityPaint);
            }

            drawnHarvest++;
        }

        // ---- 怪物（沿用旧 DrawMobs 的类型/等级/附魔过滤）----
        for (com.minhui.vpn.Handlers.HandlerItem.Mob mob : handler.mobsHandler.getMobList())
        {
            if (!isDrawablePosition(mob.getPosX(), mob.getPosY()))
            {
                continue;
            }

            com.minhui.vpn.PhotonPackageParser.enumerations.MobCodes mobType = mob.getType();
            int mobTier = mob.getTier();
            int mobEnchant = mob.getEnchantmentLevel();
            boolean tierOk = mobTier >= 1 && mobTier <= settings.mobTiers.length && settings.mobTiers[mobTier - 1];
            boolean enchantOk = mobEnchant >= 0 && mobEnchant < settings.mobEnchants.length
                    && settings.mobEnchants[mobEnchant];
            boolean show;
            int color;

            if (mobType == null)
            {
                show = settings.mobOther;
                color = 0xFFE53935;
            }
            else
            {
                switch (mobType)
                {
                    case Boss:
                        show = settings.mobBoss;
                        color = 0xFFD32F2F;
                        break;
                    case Enemy:
                        show = settings.mobOther;
                        color = 0xFFE53935;
                        break;
                    case MistPortal:
                        show = settings.mobOther;
                        color = 0xFFAB47BC;
                        break;
                    case Harvestable:
                        show = settings.mobHarvestable && tierOk && enchantOk;
                        color = 0xFF8BC34A;
                        break;
                    case Skinnable:
                        show = settings.mobSkinnable && tierOk && enchantOk;
                        color = 0xFFFFB300;
                        break;
                    default:
                        show = settings.mobOther;
                        color = 0xFFE53935;
                        break;
                }
            }

            if (!show)
            {
                continue;
            }

            projectToScreen(mob.getPosX(), mob.getPosY(), baseX, baseY, scale, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height))
            {
                continue;
            }

            entityPaint.setColor(color);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], Math.max(6f, width * 0.014f), entityPaint);
            drawnMobs++;
        }

        // ---- 玩家（距离/数量上限 + 公会/联盟/阵营配色）----
        final float lpX = handler.playersHandler.localPlayerPosX();
        final float lpY = handler.playersHandler.localPlayerPosY();
        final String localGuild = handler.playersHandler.localPlayerGuildName();
        final String localAlliance = handler.playersHandler.localPlayerAlliance();
        final float localFaction = handler.playersHandler.localPlayerFaction();
        int playerIndex = 0;

        for (Player player : handler.playersHandler.getPlayersInRange())
        {
            if (!settings.playerDot || playerIndex > com.minhui.vpn.PhotonPackageParser.Utils.MaxPlayer)
            {
                break;
            }

            if (!isDrawablePosition(player.getPosX(), player.getPosY()))
            {
                continue;
            }

            float distance = (float) com.minhui.vpn.PhotonPackageParser.Utils.calculateDistance(
                    player.getPosX(), player.getPosY(), lpX, lpY);

            if (distance > com.minhui.vpn.PhotonPackageParser.Utils.MaxDistance)
            {
                continue;
            }

            projectToScreen(player.getPosX(), player.getPosY(), baseX, baseY, scale, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height))
            {
                continue;
            }

            int color;

            if (localGuild != null && localGuild.equals(player.getGuild()))
            {
                color = 0xFF43A047;
            }
            else if (localAlliance != null && player.getGuild() != null && localAlliance.contains(player.getGuild()))
            {
                color = 0xFF8E24AA;
            }
            else if (localFaction == player.getFaction())
            {
                color = 0xFFF4840B;
            }
            else
            {
                color = 0xFF2979FF;
            }

            entityPaint.setColor(color);
            canvas.drawCircle(tmpScreen[0], tmpScreen[1], Math.max(7f, width * 0.016f), entityPaint);

            if (settings.playerNickname && player.getNickname() != null && !player.getNickname().isEmpty())
            {
                canvas.drawText(player.getNickname(), tmpScreen[0],
                        tmpScreen[1] - Math.max(10f, width * 0.022f), entityTextPaint);
            }

            if (settings.playerMounted && player.mounted)
            {
                canvas.drawText("M", tmpScreen[0] + Math.max(8f, width * 0.018f),
                        tmpScreen[1] + Math.max(4f, width * 0.008f), entityTextPaint);
            }

            playerIndex++;
            drawnPlayers++;
        }

        // ---- 箱子（按类型开关过滤）----
        for (com.minhui.vpn.Handlers.HandlerItem.Chest chest : handler.chestHandler.getChests())
        {
            if (!isDrawablePosition(chest.getPosX(), chest.getPosY()))
            {
                continue;
            }

            String chestName = chest.getName();

            if (chestName == null || !settings.isInChests(chestName))
            {
                continue;
            }

            projectToScreen(chest.getPosX(), chest.getPosY(), baseX, baseY, scale, cx, cy, tmpScreen);

            if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height))
            {
                continue;
            }

            entityPaint.setColor(chestName.toLowerCase().contains("legendary") ? 0xFFFFD54F
                    : chestName.toLowerCase().contains("rare") ? 0xFF29B6F6
                    : chestName.toLowerCase().contains("uncommon") ? 0xFF66BB6A
                    : 0xFFBDBDBD);
            canvas.drawRect(tmpScreen[0] - dotRadius, tmpScreen[1] - dotRadius,
                    tmpScreen[0] + dotRadius, tmpScreen[1] + dotRadius, entityPaint);
            drawnChests++;
        }

        // ---- 钓鱼区 ----
        if (settings.harvestingZoneFishing)
        {
            for (com.minhui.vpn.Handlers.HandlerItem.FishingZone zone : handler.fishingZoneHandler.getFishingZoneList())
            {
                if (!isDrawablePosition(zone.getPosX(), zone.getPosY()))
                {
                    continue;
                }

                projectToScreen(zone.getPosX(), zone.getPosY(), baseX, baseY, scale, cx, cy, tmpScreen);

                if (!isOnScreen(tmpScreen[0], tmpScreen[1], width, height))
                {
                    continue;
                }

                entityPaint.setColor(0xFF26C6DA);
                canvas.drawCircle(tmpScreen[0], tmpScreen[1], Math.max(6f, width * 0.013f), entityPaint);
                drawnFishing++;
            }
        }

        long now = System.currentTimeMillis();

        if (now - lastDirectLogTime >= 2000)
        {
            lastDirectLogTime = now;
            Log.d("RadarDiag", "direct drawn h=" + drawnHarvest + " m=" + drawnMobs + " p=" + drawnPlayers
                    + " c=" + drawnChests + " f=" + drawnFishing + " base=" + baseX + "," + baseY + " s=" + scale);
        }
    }

    private static String resourceKindOf(int type)
    {
        if (type < 0) return null;
        if (type <= 5) return "log";
        if (type <= 10) return "rock";
        if (type <= 15) return "fiber";
        if (type <= 22) return "hide";
        if (type <= 27) return "ore";
        return null;
    }

    private static boolean isOnScreen(float x, float y, int width, int height)
    {
        return x >= 0 && y >= 0 && x <= width && y <= height;
    }

    private void ensureEntityPaints(int width)
    {
        if (entityPaint == null)
        {
            entityPaint = new Paint();
            entityPaint.setAntiAlias(true);
            entityPaint.setStyle(Paint.Style.FILL);

            entityTextPaint = new Paint();
            entityTextPaint.setAntiAlias(true);
            entityTextPaint.setColor(0xFFFFFFFF);
            entityTextPaint.setTextAlign(Paint.Align.CENTER);
            entityTextPaint.setTextSize(Math.max(14f, width * 0.035f));
        }
    }
    private final java.util.HashMap<String, Bitmap> iconCache = new java.util.HashMap<>();

    /** 按 PC 版命名（log_2_0 / ore_8_4 ...）取 drawable 图标，缺失返回 null。 */
    private Bitmap getResourceIcon(String name)
    {
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
                bitmap = android.graphics.BitmapFactory.decodeResource(getResources(), resId);
            }
        }
        catch (Throwable ignored)
        {
        }

        iconCache.put(name, bitmap);
        return bitmap;
    }

    private static boolean isDrawablePosition(float x, float y)
    {
        return Float.isFinite(x) && Float.isFinite(y)
                && Math.abs(x) < 5000f && Math.abs(y) < 5000f
                && !(x == 0f && y == 0f);
    }
    /** 参考 PC 版 OpenRadar 的等距投影：screen = center + ((dx-dy), (dx+dy)) * k，dx = -x + baseX, dy = y - baseY。 */
    private static void projectToScreen(float x, float y, float baseX, float baseY, float k,
                                        float cx, float cy, float[] out)
    {
        float dx = -x + baseX;
        float dy = y - baseY;

        out[0] = cx - (dx - dy) * k;
        out[1] = cy - (dx + dy) * k;
    }
    private String lastFailedSection;
    public  void initMatrix()
    {
        transformationMatrix  = new Matrix();

        float radarCenterX = getWidth() / 2f;
        float radarCenterY = getHeight() / 2f;

        // 地图式视窗：不旋转；X 轴取负用来抵消各绘制类里的 -x，保证东西方向不镜像
        float scale = getWidth() / spanUnits();

        transformationMatrix.postTranslate(radarCenterX, radarCenterY);
        transformationMatrix.postScale(-scale, scale);

        Log.d("RadarDiag", "initMatrix " + getWidth() + "x" + getHeight()
                + " scale=" + scale + " span=" + spanUnits());

        // 首次 onDraw 发生在 matrix 就绪之前（matrix=null 直接 return），
        // 这里补一次重绘，否则要等下一个 "refresh" 事件雷达才会真正画出来
        postInvalidate();
    }

    /** 视窗横向覆盖多少世界单位（缩放滑块 25 时为默认 180 码）。 */
    private float spanUnits()
    {
        float zoom = RadarSettings.getInstance().radarScaleBar / 25f;
        zoom = Math.max(0.4f, Math.min(4f, zoom));

        return 180f / zoom;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);

        // 全屏覆盖后中心随屏幕尺寸变化（横竖屏切换），矩阵必须重算
        if (w > 0 && h > 0)
        {
            initMatrix();
        }
    }

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

    ArrayList<Player> playerList;

    private long lastDiagLogTime;
    private int frameCount;

    /** 保底上限：采集物 5000、怪物 2000（超出按距玩家远近淘汰） */
    private static final int MAX_HARVEST_ENTITIES = 5000;
    private static final int MAX_MOB_ENTITIES = 2000;
    private long fpsWindowStart;

    /** 临时诊断：确认 Handler 是否填充（验证通过后删除）。 */
    private void logDrawState(float lpX, float lpY)
    {
        long now = System.currentTimeMillis();

        if (now - lastDiagLogTime < 5000)
        {
            return;
        }

        lastDiagLogTime = now;

        // 保底上限：长时间跑图时只保留离玩家最近的一批，防止内存和绘制量无限增长
        MainHandler.getInstance().harvestablesHandler.trimToLimit(lpX, lpY, MAX_HARVEST_ENTITIES);
        MainHandler.getInstance().mobsHandler.trimToLimit(lpX, lpY, MAX_MOB_ENTITIES);

        MainHandler handler = MainHandler.getInstance();
        Log.d("RadarDiag", "lp=" + lpX + "," + lpY
                + " lpId=" + handler.playersHandler.localPlayerId()
                + " players=" + handler.playersHandler.getPlayersInRange().size()
                + " mobs=" + handler.mobsHandler.getMobList().size()
                + " harvest=" + handler.harvestablesHandler.getHarvestableList().size()
                + " chests=" + handler.chestHandler.getChests().size()
                + " samples=" + mapSampleCount + " locked=" + mapBoundsLocked
                + " drawn: h=" + harvestingDraw.drawnCount + " m=" + drawMobs.drawnCount
                + " p=" + drawPlayers.drawnCount + " c=" + drawChests.drawnCount + " f=" + fishingZoneDraw.drawnCount);

        StringBuilder sample = new StringBuilder("SAMPLE ");
        int shown = 0;

        for (com.minhui.vpn.Handlers.HandlerItem.Mob mob : handler.mobsHandler.getMobList())
        {
            if (shown++ >= 3)
            {
                break;
            }

            sample.append("mob{type=").append(mob.getType())
                    .append(",t=").append(mob.getTier())
                    .append(",e=").append(mob.getEnchantmentLevel())
                    .append(",d=").append(Math.round(com.minhui.vpn.PhotonPackageParser.Utils.calculateDistance(
                            mob.getPosX(), mob.getPosY(), lpX, lpY)))
                    .append("} ");
        }

        shown = 0;

        for (Player player : handler.playersHandler.getPlayersInRange())
        {
            if (shown++ >= 3)
            {
                break;
            }

            sample.append("plr{id=").append(player.getId())
                    .append(",x=").append(player.getPosX())
                    .append(",y=").append(player.getPosY())
                    .append(",d=").append(Math.round(com.minhui.vpn.PhotonPackageParser.Utils.calculateDistance(
                            player.getPosX(), player.getPosY(), lpX, lpY)))
                    .append("} ");
        }

        shown = 0;

        for (com.minhui.vpn.Handlers.HandlerItem.Harvestable harvestable : handler.harvestablesHandler.getHarvestableList())
        {
            if (shown++ >= 3)
            {
                break;
            }

            sample.append("harv{type=").append(harvestable.getType())
                    .append(",t=").append(harvestable.getTier())
                    .append(",c=").append(harvestable.getCharges())
                    .append(",e=").append(harvestable.getEnchant())
                    .append(",d=").append(Math.round(com.minhui.vpn.PhotonPackageParser.Utils.calculateDistance(
                            harvestable.getPosX(), harvestable.getPosY(), lpX, lpY)))
                    .append("} ");
        }

        Log.d("RadarDiag", sample.toString());

        java.util.List<com.minhui.vpn.Handlers.HandlerItem.Harvestable> hs = handler.harvestablesHandler.getHarvestableList();

        if (!hs.isEmpty())
        {
            com.minhui.vpn.Handlers.HandlerItem.Harvestable h0 = hs.get(0);
            Log.d("RadarDiag", "harv0 rawTier=" + h0.getTier() + " type=" + h0.getType()
                    + " enchant=" + h0.getEnchant() + " charges=" + h0.getCharges());
        }
    }

    private Paint backgroundPaint;
    private Paint viewBorderPaint;
    private Paint hintPaint;

    /** 地图包围盒：由已加载的实体推算，锁定后不再变化，保证"地图坐标系"渲染稳定。 */
    private float mapMinX = 0f, mapMaxX = 0f, mapMinY = 0f, mapMaxY = 0f;
    private int mapSampleCount = 0;
    private boolean mapBoundsLocked = false;

    /** 用当前所有已知实体推算地图包围盒；数据足够时锁定。 */
    private void updateMapBounds(float lpX, float lpY, boolean lpValid)
    {
        mapSampleCount = 0;

        MainHandler handler = MainHandler.getInstance();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        java.util.List<float[]> positions = new java.util.ArrayList<>();

        if (lpValid)
        {
            positions.add(new float[]{lpX, lpY});
        }

        // 玩家坐标来自 XOR 加密字段，可能是垃圾值；不参与地图包围盒计算，
        // 否则路过的玩家会把包围盒撑大、导致资源和怪物被压成一个点。

        for (com.minhui.vpn.Handlers.HandlerItem.Mob mob : handler.mobsHandler.getMobList())
        {
            positions.add(new float[]{mob.getPosX(), mob.getPosY()});
        }

        for (com.minhui.vpn.Handlers.HandlerItem.Harvestable harvestable : handler.harvestablesHandler.getHarvestableList())
        {
            positions.add(new float[]{harvestable.getPosX(), harvestable.getPosY()});
        }

        for (float[] position : positions)
        {
            if (position[0] == 0f && position[1] == 0f)
            {
                continue;
            }

            // 过滤解析失败产生的天文数字坐标（曾把包围盒撑到 1e30，缩放直接变成 0，整屏空白）
            if (!Float.isFinite(position[0]) || !Float.isFinite(position[1])
                    || Math.abs(position[0]) > 5000f || Math.abs(position[1]) > 5000f)
            {
                continue;
            }

            minX = Math.min(minX, position[0]);
            maxX = Math.max(maxX, position[0]);
            minY = Math.min(minY, position[1]);
            maxY = Math.max(maxY, position[1]);
            mapSampleCount++;
        }

        if (mapSampleCount == 0)
        {
            // 没数据时解除锁定，换地图后会重新计算包围盒
            mapBoundsLocked = false;
            return;
        }

        if (mapBoundsLocked && mapSampleCount < 3)
        {
            mapBoundsLocked = false;
        }

        mapMinX = minX;
        mapMaxX = maxX;
        mapMinY = minY;
        mapMaxY = maxY;

        // 数据足够才锁定；锁定后地图坐标系固定，资源不再跳动
        if (mapSampleCount >= 8 && !mapBoundsLocked
                && (maxX - minX) < 3000f && (maxY - minY) < 3000f)
        {
            float padX = Math.max(5f, (maxX - minX) * 0.05f);
            float padY = Math.max(5f, (maxY - minY) * 0.05f);

            mapMinX = minX - padX;
            mapMaxX = maxX + padX;
            mapMinY = minY - padY;
            mapMaxY = maxY + padY;
            mapBoundsLocked = true;

            Log.d("RadarDiag", "mapBounds locked " + mapMinX + "," + mapMinY + " .. " + mapMaxX + "," + mapMaxY);
        }
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

        frameCount++;
        long nowMs = System.currentTimeMillis();

        if (nowMs - fpsWindowStart >= 2000)
        {
            Log.d("RadarDiag", "fps=" + (frameCount * 1000L / Math.max(1, nowMs - fpsWindowStart)));
            frameCount = 0;
            fpsWindowStart = nowMs;
        }

        if (backgroundPaint == null)
        {
            backgroundPaint = new Paint();
            backgroundPaint.setStyle(Paint.Style.FILL);
            backgroundPaint.setColor(0xEE0E1114);
            backgroundPaint.setAntiAlias(true);

            viewBorderPaint = new Paint();
            viewBorderPaint.setStyle(Paint.Style.STROKE);
            viewBorderPaint.setStrokeWidth(3f);
            viewBorderPaint.setColor(0xFF29B6F6);
            viewBorderPaint.setAntiAlias(true);

            hintPaint = new Paint();
            hintPaint.setColor(0xFF90A4AE);
            hintPaint.setTextSize(Math.min(width, height) * 0.06f);
            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setAntiAlias(true);
        }

        float radius = Math.min(width, height) * 0.06f;
        canvas.drawRoundRect(0, 0, width, height, radius, radius, backgroundPaint);
        canvas.drawRoundRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, radius, radius, viewBorderPaint);

        // 所有绘制都被限制在视窗内（视窗只提供边界，渲染逻辑与窗口解耦）
        canvas.save();
        canvas.clipRect(0, 0, width, height);

        float lpX = MainHandler.getInstance().playersHandler.localPlayerPosX();
        float lpY = MainHandler.getInstance().playersHandler.localPlayerPosY();
        boolean lpValid = lpX != 0f || lpY != 0f;

        logDrawState(lpX, lpY);
        updateMapBounds(lpX, lpY, lpValid);

        float zoom = RadarSettings.getInstance().radarScaleBar;

        if (zoom < 0.1f)
        {
            zoom = 0.1f;
        }

        if (mapSampleCount == 0)
        {
            canvas.drawText("等待位置数据…", width / 2f, height / 2f + hintPaint.getTextSize() / 3f, hintPaint);
canvas.restore();
            return;
        }

        float spanX = Math.max(20f, mapMaxX - mapMinX);
        float spanY = Math.max(20f, mapMaxY - mapMinY);

        float baseX;
        float baseY;
        float scale;

        if (!mapBoundsLocked)
        {
            // 地图还没锁定：以已有实体的包围盒中心为基准，保证第一时间能看到东西
            baseX = (mapMinX + mapMaxX) / 2f;
            baseY = (mapMinY + mapMaxY) / 2f;

            float span = Math.max(180f, Math.max(spanX, spanY)) / zoom;
            scale = width / span;
        }
        else if (zoom <= 1.05f)
        {
            // 100%：整张地图等比缩放进视窗，地图坐标系固定，人物点移动
            baseX = (mapMinX + mapMaxX) / 2f;
            baseY = (mapMinY + mapMaxY) / 2f;
            scale = Math.min(width, height) * 0.95f / (spanX + spanY);
        }
        else
        {
            // 放大：以人物为基准；但窗口不能越出地图边界，越界时贴边（以地图坐标系为准）
            float halfX = spanX / zoom / 2f;
            float halfY = spanY / zoom / 2f;

            baseX = Math.max(mapMinX + halfX, Math.min(mapMaxX - halfX, lpX));
            baseY = Math.max(mapMinY + halfY, Math.min(mapMaxY - halfY, lpY));

            scale = Math.min(width, height) * 0.95f / (halfX * 2f + halfY * 2f);
        }

        Log.d("RadarDiag", "draw base=" + baseX + "," + baseY + " scale=" + scale + " zoom=" + zoom
                + " locked=" + mapBoundsLocked + " samples=" + mapSampleCount
                + " mobs=" + MainHandler.getInstance().mobsHandler.getMobList().size()
                + " harv=" + MainHandler.getInstance().harvestablesHandler.getHarvestableList().size());

        // 调试边框：确认背景之后仍在继续绘制
        canvas.drawRect(8, 8, width - 8, height - 8, viewBorderPaint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(width / 2f, height / 2f);
        matrix.postScale(-scale, scale);

        // 新的直绘实现：自己算屏幕坐标，不依赖历史的 Matrix/位图缓存链路
        drawEntities(canvas, baseX, baseY, scale, width, height);

        // 自己：蓝点（100% 时在地图坐标系里移动，放大时靠近视窗中心）
        if (lpValid)
        {
            // 自己：直绘公式，和实体保持同一套换算
            projectToScreen(lpX, lpY, baseX, baseY, scale, width / 2f, height / 2f, tmpScreen2);
            float[] selfScreen = tmpScreen2;
            float selfX = selfScreen[0];
            float selfY = selfScreen[1];

            if (Float.isFinite(selfX) && Float.isFinite(selfY)
                    && selfX > -50 && selfY > -50 && selfX < width + 50 && selfY < height + 50)
            {
                canvas.drawCircle(selfX, selfY, Math.max(8f, width * 0.018f), paintCenterObject);
                canvas.drawCircle(selfX, selfY, Math.max(12f, width * 0.026f), viewBorderPaint);
                Log.d("RadarDiag", "self dot at " + selfX + "," + selfY);
            }
        }

       canvas.restore();
    }
}
