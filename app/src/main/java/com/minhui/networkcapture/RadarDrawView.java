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
    boolean onLongMove = false;
    int screenWidth =0;
    int screenHeight =0;

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

        showOverlayWindow();

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

        sInstance = null;

        windowManager.removeView(overlayView);

        if(overlaySettingsView!=null)
        {
            windowManager.removeView(overlaySettingsView);
        }

        if(radarFloatingSettingsView!=null)
        {
            try
            {
                windowManager.removeView(radarFloatingSettingsView);
            }
            catch (Exception e)
            {

            }
        }

        MainHandler.getInstance().clearAll();
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

        WindowManager.LayoutParams layoutParamsSettingsFloatingView = new WindowManager.LayoutParams(
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

        if (windowManager == null || layoutParams == null || layoutParamsSettingsView == null)
        {
            return;
        }

        // 横竖屏切换后显示坐标系整体变了，必须按新尺寸重新计算位置，
        // 否则沿用旧坐标会出现悬浮窗跑到屏幕外或不再居中。
        refreshScreenMetrics();

        int size = (layoutParams.width > 0) ? layoutParams.width : (int) (screenHeight * 0.32f);

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

        windowManager.updateViewLayout(overlayView, layoutParams);
        windowManager.updateViewLayout(overlaySettingsView, layoutParamsSettingsView);

        if (showRadarActivitySettings)
        {
            positionSettingsPanel();
        }
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