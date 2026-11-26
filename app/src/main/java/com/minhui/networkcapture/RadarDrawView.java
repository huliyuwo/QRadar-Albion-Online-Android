package com.minhui.networkcapture;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.minhui.networkcapture.RadarActivities.RadarFloatingActivity;
import com.minhui.networkcapture.RadarActivities.RadarQuickOptionsPanel;
import com.minhui.networkcapture.RadarView.RadarSettings;
import com.minhui.networkcapture.RadarView.RadarView;
import com.minhui.vpn.Handlers.MainHandler;

public class RadarDrawView extends Service
{
    private static RadarDrawView sInstance;

    /** 正在运行的悬浮窗服务实例（未运行时为 null），供首页设置页实时调节悬浮窗。 */
    public static RadarDrawView getInstance()
    {
        return sInstance;
    }

    public RadarView getRadarView()
    {
        return radarDrawView;
    }

    private WindowManager windowManager;
    private View overlayView;

    /** 看门狗：VPN 停掉后自动关闭雷达渲染，避免关掉 VPN/游戏后屏幕上还留着标记。 */
    private final android.os.Handler watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable vpnWatchdog = new Runnable()
    {
        @Override
        public void run()
        {
            if (!com.minhui.vpn.utils.VpnServiceHelper.vpnRunningStatus())
            {
                stopSelf();
                return;
            }

            watchdogHandler.postDelayed(this, 3000);
        }
    };
    private View overlaySettingsView;
    View radarFloatingSettingsView;

    /** 内嵌「快速渲染选项」面板：独立悬浮 View，位置贴在雷达视窗旁边，打开时不遮挡整个屏幕。 */
    private View radarQuickOptionsView;
    private RadarQuickOptionsPanel quickOptionsPanel;
    private WindowManager.LayoutParams layoutParamsQuickOptions;
    private boolean quickOptionsAdded = false;
    boolean onLongMove = false;
    int screenWidth =0;
    int screenHeight =0;

    /** 上一次布局依据的屏幕尺寸：用于轮询检测横竖屏切换（部分 ROM 不给 Service 发 onConfigurationChanged）。 */
    private int lastScreenWidth;
    private int lastScreenHeight;

    /** 是否由用户手动定位过（拖动或设置页滑块）。false 时悬浮窗固定屏幕正上方居中 */
    private static final String PREF_USER_POSITIONED = "radarUserPositioned";
    private boolean userPositioned = false;

    // 拖动状态
    private int touchSlop;
    private float touchDownRawX;
    private float touchDownRawY;
    private int dragStartRadarX;
    private int dragStartRadarY;
    private int dragStartButtonX;
    private int dragStartButtonY;
    private boolean isDragging = false;
    RadarDrawView radarDrawViewPseudoSingleton;
    private static final String CHANNEL_ID = "my_channel_01";

    @Override
    public void onCreate()
    {
        super.onCreate();

        /*

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }

        Intent notificationIntent = new Intent(this, VPNCaptureActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("QRadar service")
                .setContentText("Radar is running...")
                .setSmallIcon(R.drawable.qradarlogo)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
        */

        radarDrawViewPseudoSingleton = this;
        sInstance = this;

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        refreshScreenMetrics();

        try
        {
            showOverlayWindow();
        }
        catch (Throwable e)
        {
            // 悬浮窗权限被收回 / 窗口被系统拒绝时不能把整个进程带崩，直接停止服务
            Log.e("RadarDiag", "showOverlayWindow failed: " + e);
            stopSelf();
            return;
        }

        watchdogHandler.postDelayed(vpnWatchdog, 3000);
    }

    /** 用整块屏幕的真实尺寸作为坐标系（悬浮窗带 FLAG_LAYOUT_NO_LIMITS，坐标覆盖状态栏区域） */
    private void refreshScreenMetrics()
    {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    /** 状态栏高度：默认位置贴着状态栏下沿，否则点按会被状态栏窗口抢走 */
    private int topInset()
    {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBar = resId > 0 ? getResources().getDimensionPixelSize(resId) : dpToPx(24);
        return statusBar + dpToPx(4);
    }

    private void createNotificationChannel()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "My Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        watchdogHandler.removeCallbacks(vpnWatchdog);
        watchdogHandler.removeCallbacksAndMessages(null);

        sInstance = null;

        // removeView 在视图未附加/服务异常销毁时会抛 IllegalArgumentException，
        // 这里必须逐个兜底：onDestroy 抛异常会直接导致“停止 VPN 时闪退”。
        removeViewSafely(overlayView);
        removeViewSafely(overlaySettingsView);
        removeViewSafely(radarFloatingSettingsView);
        removeViewSafely(radarQuickOptionsView);
        quickOptionsAdded = false;

        try
        {
            MainHandler.getInstance().clearAll();
        }
        catch (Throwable e)
        {
            Log.e("RadarDiag", "clearAll failed: " + e);
        }

        overlayView = null;
        overlaySettingsView = null;
        radarFloatingSettingsView = null;
        radarQuickOptionsView = null;
        quickOptionsPanel = null;
        radarDrawView = null;
    }

    private void removeViewSafely(View view)
    {
        if (view == null || windowManager == null)
        {
            return;
        }

        try
        {
            windowManager.removeView(view);
        }
        catch (Throwable ignored)
        {
            // 未被添加/已被移除，忽略
        }
    }

    RadarView radarDrawView;
    private boolean showRadarActivitySettings = false;
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    SharedPreferences sharedPreferences;
    LayoutInflater layoutInflater;
    boolean isLongDragging = false;
    WindowManager.LayoutParams layoutParams;
    WindowManager.LayoutParams layoutParamsSettingsView;

    public void setRadarSize(int size)
    {
        if (layoutParams == null)
        {
            return;
        }

        layoutParams.width = size;
        layoutParams.height = size;
        RadarSettings.getInstance().radarSizeWidthHeightBar = size;

        if (sharedPreferences != null)
        {
            sharedPreferences.edit().putInt("radarSizeWidthHeightBar", size).apply();
        }
        layoutParams.x = clamp(layoutParams.x, 0, maxOffset(screenWidth, size));
        layoutParams.y = clamp(layoutParams.y, 0, maxOffset(screenHeight, size));

        windowManager.updateViewLayout(overlayView, layoutParams);

        if (radarDrawView != null)
        {
            radarDrawView.initMatrix();
        }
    }
    WindowManager.LayoutParams layoutParamsSettingsFloatingView;

    /** 设置页滑块调整过位置后，视为用户手动定位，横竖屏切换时不再自动居中 */
    private void markUserPositioned()
    {
        if (userPositioned)
        {
            return;
        }

        userPositioned = true;
        if (sharedPreferences != null)
        {
            sharedPreferences.edit().putBoolean(PREF_USER_POSITIONED, true).apply();
        }
    }

    public  void setRadarX(int progress)
    {
        layoutParams.x = progress;
        markUserPositioned();
        windowManager.updateViewLayout(overlayView,layoutParams);
    }

    public void setRadarY(int progress)
    {
        layoutParams.y = progress;
        markUserPositioned();
        windowManager.updateViewLayout(overlayView,layoutParams);
    }

    public void setSettingsHeightPost(int height)
    {
       // layoutParamsSettingsFloatingView.height = height;
       // windowManager.updateViewLayout(radarFloatingSettingsView,height);
    }

    public void setSettingsWidthPost(int widthPost)
    {
        // layoutParamsSettingsFloatingView.width = widthPost;
        // windowManager.updateViewLayout(radarFloatingSettingsView,widthPost);
    }

    public void setSettingsHeight(int progress)
    {
        /*
            layoutParamsSettingsFloatingView.height = progress;
            windowManager.updateViewLayout(radarFloatingSettingsView,layoutParamsSettingsFloatingView);
         */

        ViewGroup.LayoutParams layoutParams =settings_edit.getLayoutParams();
        layoutParams.height = progress;
        settings_edit.setLayoutParams(layoutParams);
    }

    public void setSettingsWidth(int progress)
    {
        //  layoutParamsSettingsFloatingView.width = progress;
        //  windowManager.updateViewLayout(radarFloatingSettingsView,layoutParamsSettingsFloatingView);

        ViewGroup.LayoutParams layoutParams =settings_edit.getLayoutParams();
        layoutParams.width = progress;
        settings_edit.setLayoutParams(layoutParams);
    }

    public void setFloatingX(int progress)
    {
        layoutParamsSettingsView.x = progress;
        markUserPositioned();
        windowManager.updateViewLayout(overlaySettingsView,layoutParamsSettingsView);
    }

    public void setFloatingY(int progress)
    {
        layoutParamsSettingsView.y = progress;
        markUserPositioned();
        windowManager.updateViewLayout(overlaySettingsView,layoutParamsSettingsView);
    }

    public void setFloatingSize(int progress)
    {
        layoutParamsSettingsView.width = progress;
        layoutParamsSettingsView.height = progress;
        windowManager.updateViewLayout(overlaySettingsView,layoutParamsSettingsView);
    }

    LinearLayout settings_edit;
    LinearLayout content_layout;

    public void reInitMatrix()
    {
        radarDrawView.initMatrix();
    }

    private void showOverlayWindow()
    {
        // Inflate the overlay view
        overlayView = LayoutInflater.from(this).inflate(R.layout.radar_draw_layout, null);
        overlaySettingsView = LayoutInflater.from(this).inflate(R.layout.radar_settings_view, null);
        radarFloatingSettingsView = LayoutInflater.from(this).inflate(R.layout.activity_radar_floating_settings, null);

        settings_edit =  radarFloatingSettingsView.findViewById(R.id.settings_edit);
        content_layout = radarFloatingSettingsView.findViewById(R.id.content_layout);
        layoutInflater=LayoutInflater.from(this);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        @SuppressLint("CommitPrefEdits") SharedPreferences.Editor editor = sharedPreferences.edit();

        // 视窗：正方形；尺寸用设置页滑块保存的值，没设置过时取屏高 1/3
        int viewportSize = RadarSettings.getInstance().radarSizeWidthHeightBar;

        if (viewportSize <= 0)
        {
            viewportSize = Math.max(dpToPx(160), (int) (screenHeight * 0.32f));
        }

        viewportSize = Math.max(dpToPx(120), Math.min(viewportSize, Math.min(screenWidth, screenHeight) - dpToPx(40)));

        layoutParams = new WindowManager.LayoutParams(
                viewportSize, viewportSize,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        userPositioned = sharedPreferences.getBoolean(PREF_USER_POSITIONED, false);

        if (userPositioned)
        {
            layoutParams.x = clamp(RadarSettings.getInstance().radarXBar, 0, maxOffset(screenWidth, viewportSize));
            layoutParams.y = clamp(RadarSettings.getInstance().radarYBar, 0, maxOffset(screenHeight, viewportSize));
        }
        else
        {
            // 默认落在人物模型（屏幕中心）偏左上
            layoutParams.x = clamp(screenWidth / 2 - (int) (viewportSize * 1.15f), 0, maxOffset(screenWidth, viewportSize));
            layoutParams.y = clamp(screenHeight / 2 - (int) (viewportSize * 1.15f), 0, maxOffset(screenHeight, viewportSize));
        }

        RadarSettings.getInstance().radarXBar = layoutParams.x;
        RadarSettings.getInstance().radarYBar = layoutParams.y;

        radarDrawView = overlayView.findViewById(R.id.radarView);

        // Add the overlay view to the window manager
        windowManager.addView(overlayView, layoutParams);

        // 注意：必须写到字段上，positionSettingsPanel() 依赖它把面板摆到齿轮正下方
        layoutParamsSettingsFloatingView = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // This type ensures the view overlays on other apps
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // This flag will allow events to be delivered to the window behind it
                PixelFormat.TRANSLUCENT);
        layoutParamsSettingsFloatingView.gravity = Gravity.TOP | Gravity.START;

        // Add radarFloatingSettingsView first, so it is below overlaySettingsView
        windowManager.addView(radarFloatingSettingsView, layoutParamsSettingsFloatingView);

        radarFloatingSettingsView.setVisibility(View.GONE);
         new RadarFloatingActivity(radarFloatingSettingsView, layoutInflater , radarDrawView, radarDrawViewPseudoSingleton);

        layoutParamsSettingsView = new WindowManager.LayoutParams(
                RadarSettings.getInstance().floatingWidthHeightBar,
                RadarSettings.getInstance().floatingWidthHeightBar,
                getWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |

                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);

        layoutParamsSettingsView.gravity = Gravity.TOP | Gravity.START;

        if (userPositioned)
        {
            layoutParamsSettingsView.x = clamp(RadarSettings.getInstance().floatingXBar, 0, maxOffset(screenWidth, layoutParamsSettingsView.width));
            layoutParamsSettingsView.y = clamp(RadarSettings.getInstance().floatingYBar, 0, maxOffset(screenHeight, layoutParamsSettingsView.height));
        }
        else
        {
            layoutParamsSettingsView.x = Math.max(0, (screenWidth - layoutParamsSettingsView.width) / 2);
            layoutParamsSettingsView.y = topInset();
        }

        RadarSettings.getInstance().floatingXBar = layoutParamsSettingsView.x;
        RadarSettings.getInstance().floatingYBar = layoutParamsSettingsView.y;

        ViewGroup.LayoutParams layoutParams = settings_edit.getLayoutParams();
        layoutParams.height = RadarSettings.getInstance().settingsHeightBar;
        layoutParams.width = RadarSettings.getInstance().settingsWidthBar;
        settings_edit.setLayoutParams(layoutParams);
        windowManager.addView(overlaySettingsView, layoutParamsSettingsView);

        content_layout.setBackgroundColor(Color.argb(RadarSettings.getInstance().settingsTransparencyBar,0,0,0));

        // 悬浮窗可拖动：按住小齿轮按钮拖动会带着雷达圈一起移动，松手保存坐标；
        // 没有产生位移则视为单击，照旧打开/收起设置面板。
        overlaySettingsView.setOnTouchListener((v, event) -> onFloatingTouch(event));
    }

    private boolean onFloatingTouch(MotionEvent event)
    {
        if (layoutParams == null || layoutParamsSettingsView == null)
        {
            return false;
        }

        switch (event.getActionMasked())
        {
            case MotionEvent.ACTION_DOWN:
                if (touchSlop == 0)
                {
                    touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
                }
                touchDownRawX = event.getRawX();
                touchDownRawY = event.getRawY();
                dragStartRadarX = layoutParams.x;
                dragStartRadarY = layoutParams.y;
                dragStartButtonX = layoutParamsSettingsView.x;
                dragStartButtonY = layoutParamsSettingsView.y;
                isDragging = false;
                return true;

            case MotionEvent.ACTION_MOVE:
            {
                int dx = (int) (event.getRawX() - touchDownRawX);
                int dy = (int) (event.getRawY() - touchDownRawY);

                if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop))
                {
                    isDragging = true;
                }

                if (isDragging)
                {
                    moveFloatingWindows(dx, dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (isDragging)
                {
                    saveFloatingPosition();
                }
                else if (isQuickRenderOptionsVisible())
                {
                    // 快速选项面板打开时，再点齿轮 = 关闭面板（不跳回小面板）
                    hideQuickRenderOptions();
                }
                else
                {
                    toggleSettingsPanel();
                }
                isDragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (isDragging)
                {
                    saveFloatingPosition();
                }
                isDragging = false;
                return true;

            default:
                return false;
        }
    }

    /** 拖动：雷达圈以屏幕边界为限，按钮跟随同样的实际位移，保持两者相对位置不变 */
    private void moveFloatingWindows(int dx, int dy)
    {
        int radarX = clamp(dragStartRadarX + dx, 0, maxOffset(screenWidth, layoutParams.width));
        int radarY = clamp(dragStartRadarY + dy, 0, maxOffset(screenHeight, layoutParams.height));

        int movedX = radarX - dragStartRadarX;
        int movedY = radarY - dragStartRadarY;

        layoutParams.x = radarX;
        layoutParams.y = radarY;

        layoutParamsSettingsView.x = dragStartButtonX + movedX;
        layoutParamsSettingsView.y = dragStartButtonY + movedY;

        windowManager.updateViewLayout(overlayView, layoutParams);
        windowManager.updateViewLayout(overlaySettingsView, layoutParamsSettingsView);
    }

    /** 松手后持久化坐标，并同步到设置页的滑块状态 */
    private void saveFloatingPosition()
    {
        userPositioned = true;

        RadarSettings.getInstance().radarXBar = layoutParams.x;
        RadarSettings.getInstance().radarYBar = layoutParams.y;
        RadarSettings.getInstance().floatingXBar = layoutParamsSettingsView.x;
        RadarSettings.getInstance().floatingYBar = layoutParamsSettingsView.y;

        sharedPreferences.edit()
                .putBoolean(PREF_USER_POSITIONED, true)
                .putInt("radarXBar", layoutParams.x)
                .putInt("radarYBar", layoutParams.y)
                .putInt("floatingXBar", layoutParamsSettingsView.x)
                .putInt("floatingYBar", layoutParamsSettingsView.y)
                .apply();

        if (showRadarActivitySettings)
        {
            positionSettingsPanel();
        }
    }

    private void toggleSettingsPanel()
    {
        showRadarActivitySettings = !showRadarActivitySettings;

        if (showRadarActivitySettings)
        {
            positionSettingsPanel();
            radarFloatingSettingsView.setVisibility(View.VISIBLE);
        }
        else
        {
            radarFloatingSettingsView.setVisibility(View.GONE);
        }
    }

    /** 悬浮面板里的「渲染选项」跳转后收起面板（设置项已全部迁移到 RenderOptionsActivity）。 */
    public void hideRadarSettingsPanel()
    {
        showRadarActivitySettings = false;

        if (radarFloatingSettingsView != null)
        {
            radarFloatingSettingsView.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // 内嵌快速渲染选项面板（悬浮窗内直接调参，不再跳转 Activity）
    // ------------------------------------------------------------------

    /** 快速渲染选项面板当前是否展开。 */
    public boolean isQuickRenderOptionsVisible()
    {
        return quickOptionsAdded && radarQuickOptionsView != null
                && radarQuickOptionsView.getVisibility() == View.VISIBLE;
    }

    /** 在雷达视窗旁边展开内嵌「快速渲染选项」面板；雷达照常渲染，不离开游戏。 */
    public void showQuickRenderOptions()
    {
        if (windowManager == null)
        {
            return;
        }

        // 先收起齿轮小面板，避免两个悬浮面板互相重叠
        hideRadarSettingsPanel();

        if (radarQuickOptionsView == null)
        {
            quickOptionsPanel = new RadarQuickOptionsPanel(this, new RadarQuickOptionsPanel.Listener()
            {
                @Override
                public void onClose()
                {
                    hideQuickRenderOptions();
                }

                @Override
                public void onOpenFullSettings()
                {
                    openRenderOptionsActivity();
                }
            });
            radarQuickOptionsView = quickOptionsPanel.getView();
        }

        // 每次打开都按最新 RadarSettings 重建，保证与完整设置页互相同步
        quickOptionsPanel.refresh();

        if (!quickOptionsAdded)
        {
            layoutParamsQuickOptions = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            layoutParamsQuickOptions.gravity = Gravity.TOP | Gravity.START;

            try
            {
                windowManager.addView(radarQuickOptionsView, layoutParamsQuickOptions);
                quickOptionsAdded = true;
            }
            catch (Throwable e)
            {
                Log.e("RadarDiag", "showQuickRenderOptions failed: " + e);
                radarQuickOptionsView = null;
                quickOptionsPanel = null;
                return;
            }
        }

        radarQuickOptionsView.setVisibility(View.VISIBLE);
        positionQuickOptionsPanel();
        Log.d("RadarDiag", "quick render options shown");
    }

    /** 收起内嵌快速选项面板（关闭按钮 / 再点齿轮），游戏与雷达不受影响。 */
    public void hideQuickRenderOptions()
    {
        if (radarQuickOptionsView != null && quickOptionsAdded)
        {
            removeViewSafely(radarQuickOptionsView);
            quickOptionsAdded = false;
        }
    }

    /** 打开完整渲染选项页（保留独立任务栈：返回直接回到游戏）。 */
    public void openRenderOptionsActivity()
    {
        hideQuickRenderOptions();
        hideRadarSettingsPanel();

        try
        {
            Intent intent = new Intent(this, com.minhui.networkcapture.RadarActivities.RenderOptionsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        catch (Throwable e)
        {
            Log.e("RadarDiag", "openRenderOptionsActivity failed: " + e);
        }
    }

    /** 把快速选项面板摆在雷达视窗旁边：优先右侧，放不下翻到左侧；竖向贴齐雷达并限制在屏幕内。 */
    private void positionQuickOptionsPanel()
    {
        if (!quickOptionsAdded || radarQuickOptionsView == null || layoutParamsQuickOptions == null)
        {
            return;
        }

        refreshScreenMetrics();

        int width = Math.min(dpToPx(300), Math.max(dpToPx(220), screenWidth - dpToPx(32)));
        int maxHeight = Math.max(dpToPx(240), screenHeight - 2 * topInset());
        int height = Math.min(dpToPx(420), maxHeight);

        int radarX = layoutParams != null ? layoutParams.x : 0;
        int radarY = layoutParams != null ? layoutParams.y : 0;
        int radarWidth = layoutParams != null ? layoutParams.width : 0;
        int gap = dpToPx(10);

        int x = radarX + radarWidth + gap;

        if (x + width > screenWidth)
        {
            x = radarX - gap - width;
        }

        if (x < 0)
        {
            x = clamp(screenWidth - width - dpToPx(8), 0, Math.max(0, screenWidth - width));
        }

        int y = clamp(radarY, 0, Math.max(0, screenHeight - height));

        layoutParamsQuickOptions.width = width;
        layoutParamsQuickOptions.height = height;
        layoutParamsQuickOptions.x = x;
        layoutParamsQuickOptions.y = y;

        try
        {
            windowManager.updateViewLayout(radarQuickOptionsView, layoutParamsQuickOptions);
        }
        catch (Throwable e)
        {
            Log.w("RadarDiag", "positionQuickOptionsPanel failed: " + e);
        }
    }

    /** 设置面板跟随按钮：显示在按钮正下方，越界时翻到按钮上方 */
    private void positionSettingsPanel()
    {
        if (radarFloatingSettingsView == null || layoutParamsSettingsFloatingView == null)
        {
            return;
        }

        int panelWidth = radarFloatingSettingsView.getMeasuredWidth();
        int panelHeight = radarFloatingSettingsView.getMeasuredHeight();

        if (panelWidth <= 0 || panelHeight <= 0)
        {
            radarFloatingSettingsView.measure(
                    View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));
            panelWidth = radarFloatingSettingsView.getMeasuredWidth();
            panelHeight = radarFloatingSettingsView.getMeasuredHeight();
        }

        int panelX = clamp(layoutParamsSettingsView.x, 0, maxOffset(screenWidth, panelWidth));

        int gap = dpToPx(8);
        int panelY = layoutParamsSettingsView.y + layoutParamsSettingsView.height + gap;
        if (panelY + panelHeight > screenHeight)
        {
            panelY = Math.max(0, layoutParamsSettingsView.y - panelHeight - gap);
        }

        layoutParamsSettingsFloatingView.x = panelX;
        layoutParamsSettingsFloatingView.y = panelY;
        windowManager.updateViewLayout(radarFloatingSettingsView, layoutParamsSettingsFloatingView);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        relayoutForCurrentScreen();
    }

    /**
     * 按当前屏幕尺寸重新计算悬浮窗尺寸与位置。
     *
     * <p>横竖屏切换后坐标系整体变化，沿用旧坐标会让悬浮窗跑到屏幕外或压住状态栏；
     * 而部分 ROM（ColorOS/realme 等）不会给后台 Service 派发 onConfigurationChanged，
     * 所以 watchdog 每 3 秒也会比对一次屏幕尺寸并调用本方法作为兜底。
     */
    private void relayoutForCurrentScreen()
    {
        if (windowManager == null || layoutParams == null || layoutParamsSettingsView == null || overlayView == null)
        {
            return;
        }

        refreshScreenMetrics();

        int size = (layoutParams.width > 0) ? layoutParams.width : (int) (Math.min(screenWidth, screenHeight) * 0.32f);

        // 竖屏时可用宽度可能小于横屏时代算出的尺寸，必须一起收敛
        int maxSide = Math.max(dpToPx(120), Math.min(screenWidth, screenHeight) - dpToPx(40));
        size = Math.max(dpToPx(120), Math.min(size, maxSide));

        layoutParams.width = size;
        layoutParams.height = size;

        if (userPositioned)
        {
            layoutParams.x = clamp(layoutParams.x, 0, maxOffset(screenWidth, size));
            layoutParams.y = clamp(layoutParams.y, 0, maxOffset(screenHeight, size));
            layoutParamsSettingsView.x = clamp(layoutParamsSettingsView.x, 0, maxOffset(screenWidth, layoutParamsSettingsView.width));
            layoutParamsSettingsView.y = clamp(layoutParamsSettingsView.y, 0, maxOffset(screenHeight, layoutParamsSettingsView.height));
        }
        else
        {
            layoutParams.x = clamp(screenWidth / 2 - (int) (size * 1.15f), 0, maxOffset(screenWidth, size));
            layoutParams.y = clamp(screenHeight / 2 - (int) (size * 1.15f), 0, maxOffset(screenHeight, size));
            layoutParamsSettingsView.x = Math.max(0, (screenWidth - layoutParamsSettingsView.width) / 2);
            layoutParamsSettingsView.y = topInset();
        }

        try
        {
            windowManager.updateViewLayout(overlayView, layoutParams);
            windowManager.updateViewLayout(overlaySettingsView, layoutParamsSettingsView);

            if (showRadarActivitySettings)
            {
                positionSettingsPanel();
            }

            if (isQuickRenderOptionsVisible())
            {
                positionQuickOptionsPanel();
            }
        }
        catch (Throwable e)
        {
            Log.w("RadarDiag", "relayoutForCurrentScreen failed: " + e);
        }

        RadarSettings.getInstance().radarSizeWidthHeightBar = size;
        lastScreenWidth = screenWidth;
        lastScreenHeight = screenHeight;
    }
    private static int maxOffset(int screenSize, int viewSize)
    {
        return Math.max(0, screenSize - viewSize);
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private int dpToPx(int dp)
    {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
    private static final String ANDROID_CHANNEL_ID = "com.minhui.Location.Channel";
    private static final int NOTIFICATION_ID = 555;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return super.onStartCommand(intent, flags, startId);
    }

    private int getWindowType()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        else
        {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }
    }

    public void setTransparencySettings(int color)
    {
        content_layout.setBackgroundColor(color);
    }
}