package com.example.longscreenshot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ForegroundServiceStartNotAllowedException;
import android.content.ContentResolver;
import android.content.pm.ServiceInfo;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.Toast;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import android.util.Log;
import java.util.Locale;

public class ImprovedLongScreenshotService extends Service {

    private static final String TAG = "ImprovedLongScreenshotService";

    // 在类的顶部添加一个常量
    private static final int PERMISSION_BAR_WAIT_TIME = 3500; // 等待权限栏消失的时间（毫秒）

    // 窗口管理器，用于管理悬浮窗

    private WindowManager windowManager;
    // 悬浮窗视图
    private View floatingView;

    // 用于记录上次点击时间，判断双击
    private long lastClickTime = 0;
    // 是否正在截图模式
    private boolean isCapturing = false;
    // 用于处理延时任务
    private Handler handler = new Handler(Looper.getMainLooper());

    // 保存截屏的列表
    private List<Bitmap> capturedScreens = new ArrayList<>();
    // 截屏间隔 (毫秒)
    private static final int SCREENSHOT_INTERVAL = 900; // 增加间隔，确保滚动稳定
    // 滚动后等待时间
    private static final int SCROLL_DELAY = 1000;
    // 最大截图尝试次数
    private static final int MAX_SCREENSHOT_ATTEMPTS = 30; // 增加最大尝试次数
    // 当前截图尝试次数
    private int currentScreenshotAttempt = 0;

    // 保存媒体投影相关实例
    private MediaProjection mediaProjection;
    private ScreenshotTaker screenshotTaker;

    // 无障碍服务相关
    private ScrollScreenshotAccessibilityService accessibilityService;
    private AccessibilityNodeInfo currentScrollableNode;
    private int scrollCount = 0;
    private static final int MAX_SCROLL_COUNT = 30; // 防止无限滚动

    // 服务销毁标志
    private boolean mIsDestroyed = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        String channelId = "floating_service_channel";
        String channelName = "长截屏服务";
        NotificationChannel channel = new NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW);

        channel.setDescription("用于保持长截屏服务在后台运行");

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 检查MediaProjection权限，并在需要时添加延迟
     * @param onPermissionReady 权限准备就绪后执行的操作
     */
    private void checkMediaProjectionPermission(Runnable onPermissionReady) {
        if (mediaProjection == null) {
            // 需要请求权限
            requestNewMediaProjection();
            return;
        }

        // 已有权限，但为了确保权限栏不会干扰，添加适当延迟
        // 根据截图次数动态调整延迟时间
        int delayTime = 5000; // 基础延迟

        // 每2-3次截图时增加延迟，因为这时更可能需要刷新权限
        if (scrollCount > 0) {
            delayTime = 5000; // 增加到1秒
        }

        // 对于iQOO Neo8设备特定优化
        if (Build.MODEL.toLowerCase().contains("neo8") ||
                Build.MANUFACTURER.toLowerCase().contains("iqoo")) {
            delayTime += 500; // 再增加500毫秒
        }

        // 在主线程上延迟执行
        final int finalDelayTime = delayTime;
        handler.postDelayed(() -> {
            // 再次检查服务是否还在运行
            if (isCapturing && !mIsDestroyed) {
                onPermissionReady.run();
            }
        }, finalDelayTime);
    }

    private Notification createNotification() {
        String channelId = "floating_service_channel";

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent, 0);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("智能长截屏服务")
                .setContentText("正在后台运行")
                .setSmallIcon(R.drawable.ic_screenshot)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent);

        return builder.build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ImprovedLongScreenshotService.onCreate()");
        mIsDestroyed = false;

        try {
            // 如果是Android 8.0以上，需要创建通知渠道并启动前台服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel();

                Notification notification = createNotification();

                // Android 10+需要明确指定前台服务类型
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                            // 对于 Android 14，在设置了 targetSdkVersion=34 的情况下，
                            // 需要在 Manifest 中声明 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限
                            startForeground(1, notification);
                        } else {
                            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                        }
                    } catch (SecurityException se) {
                        Log.e(TAG, "启动前台服务时出现安全异常: " + se.getMessage());
                        // 尝试不带类型启动
                        startForeground(1, notification);
                    } catch (Exception e) {
                        Log.e(TAG, "启动前台服务时出现异常: " + e.getMessage());
                        startForeground(1, notification);
                    }
                } else {
                    startForeground(1, notification);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "创建前台服务失败: " + e.getMessage(), e);
            Toast.makeText(this, "创建前台服务失败，应用可能无法正常工作", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        // 不要在 onCreate 中初始化悬浮窗，移至 onStartCommand 中
        // setupFloatingWindow();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ImprovedLongScreenshotService.onStartCommand()");

        if (intent == null) {
            Log.e(TAG, "服务启动时收到了空的Intent");
            stopSelf();
            return START_NOT_STICKY;
        }

        // 检查是否需要创建新的MediaProjection
        if (mediaProjection == null) {
            if (!intent.hasExtra("resultCode") || !intent.hasExtra("resultData")) {
                Log.e(TAG, "服务启动时缺少必要的媒体投影数据");
                Toast.makeText(this, "媒体投影权限数据缺失，无法启动服务", Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent resultData = intent.getParcelableExtra("resultData");

            if (resultCode == 0 || resultData == null) {
                Log.e(TAG, "无效的媒体投影权限数据");
                Toast.makeText(this, "媒体投影权限无效，无法启动服务", Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            // 记录详细日志以便调试
            Log.d(TAG, "收到媒体投影权限数据, resultCode: " + resultCode);
            Log.d(TAG, "开始创建MediaProjection");

            try {
                MediaProjectionManager projectionManager = (MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);

                // 创建新的媒体投影
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                Log.d(TAG, "MediaProjection创建成功: " + (mediaProjection != null));

                if (mediaProjection == null) {
                    Log.e(TAG, "创建的媒体投影为空");
                    Toast.makeText(this, "无法创建媒体投影，服务启动失败", Toast.LENGTH_SHORT).show();
                    stopSelf();
                    return START_NOT_STICKY;
                }

                // 创建截图器并设置媒体投影
                screenshotTaker = new ScreenshotTaker(this);
                screenshotTaker.setMediaProjection(mediaProjection);

                Toast.makeText(this, "长截图服务已成功启动", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "创建MediaProjection失败: " + e.getMessage(), e);
                Toast.makeText(this, "创建媒体投影失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // 显示悬浮窗
        setupFloatingWindow();

        return START_STICKY;
    }

    private void setupFloatingWindow() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            Log.d(TAG, "获取WindowManager: " + (windowManager != null));

            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
            Log.d(TAG, "悬浮窗视图创建: " + (floatingView != null));

            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 200;

            try {
                windowManager.addView(floatingView, params);
                Log.d(TAG, "成功添加悬浮窗视图");
            } catch (Exception e) {
                Log.e(TAG, "添加悬浮窗视图失败: " + e.getMessage());
                Toast.makeText(this, "无法创建悬浮窗: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }

            setupDragLogic(params);
        } catch (Exception e) {
            Log.e(TAG, "设置悬浮窗时出错: " + e.getMessage());
            Toast.makeText(this, "创建悬浮窗失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupDragLogic(final WindowManager.LayoutParams params) {
        floatingView.findViewById(R.id.root_container).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!isDragging && (Math.abs(event.getRawX() - initialTouchX) > 10 ||
                                Math.abs(event.getRawY() - initialTouchY) > 10)) {
                            isDragging = true;
                        }

                        if (isDragging) {
                            params.x = initialX + (int) (event.getRawX() - initialTouchX);
                            params.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            handleClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void handleClick() {
        long clickTime = System.currentTimeMillis();

        // 检测双击（1秒内点击两次）
        if (clickTime - lastClickTime < 1000) {
            // 双击操作 - 停止截图
            if (isCapturing) {
                Toast.makeText(this, "正在停止截图...", Toast.LENGTH_LONG).show();
                stopCapturing();
                // 更改图标为正常状态
                ImageView iconView = floatingView.findViewById(R.id.icon);
                iconView.setImageResource(R.drawable.ic_screenshot);
            }
            lastClickTime = 0; // 重置点击时间
        } else {
            // 单击操作 - 显示选项菜单或直接开始截图
            if (!isCapturing) {
                showCaptureOptions();
            }
            lastClickTime = clickTime; // 更新点击时间
        }
    }

    private void showCaptureOptions() {
        View optionsView = LayoutInflater.from(this).inflate(R.layout.capture_options, null);

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;

        windowManager.addView(optionsView, params);

        optionsView.findViewById(R.id.btnNormalScreenshot).setOnClickListener(v -> {
            windowManager.removeView(optionsView);
            startNormalCapturing(); // 使用原来的MediaProjection截屏
        });


        optionsView.findViewById(R.id.btnIntelligentScreenshot).setOnClickListener(v -> {
            windowManager.removeView(optionsView);
            startCapturing(); // 使用智能长截图
        });

        optionsView.findViewById(R.id.btnCancel).setOnClickListener(v -> {
            windowManager.removeView(optionsView);
        });
    }
    // 原始的截图方法，保留以便用户选择
    private void startNormalCapturing() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing");
            return;
        }

        isCapturing = true;
        capturedScreens.clear();
        currentScreenshotAttempt = 0;

        // 显示准备消息
        Toast.makeText(this, "准备普通长截图，请等待权限栏消失...", Toast.LENGTH_SHORT).show();

        // 添加延迟，等待权限栏消失
        handler.postDelayed(() -> {
            // 验证MediaProjection是否有效，但避免重复请求
            if (mediaProjection == null || (screenshotTaker != null && screenshotTaker.isProjectionStopped())) {
                Log.e(TAG, "MediaProjection不可用");
                Toast.makeText(this, "MediaProjection不可用，停止截图", Toast.LENGTH_SHORT).show();
                isCapturing = false;
                return;
            }

            // 确保截屏工具已初始化
            if (screenshotTaker == null) {
                screenshotTaker = new ScreenshotTaker(this);
                if (mediaProjection != null) {
                    screenshotTaker.setMediaProjection(mediaProjection);
                } else {
                    Toast.makeText(this, "未获取屏幕截图权限", Toast.LENGTH_LONG).show();
                    isCapturing = false;
                    return;
                }
            }

            // 更改图标为截图状态
            ImageView iconView = floatingView.findViewById(R.id.icon);
            iconView.setImageResource(R.drawable.ic_capturing);
            Toast.makeText(this, "开始普通长截图，双击停止", Toast.LENGTH_LONG).show();

            // 启动截图循环
            captureNextScreenshot();
        }, PERMISSION_BAR_WAIT_TIME); // 添加延迟，等待权限栏消失
    }

    // 普通截图的下一帧方法
    private void captureNextScreenshot() {
        if (!isCapturing) {
            Log.d(TAG, "Capture stopped");
            return;
        }

        // 检查尝试次数
        if (currentScreenshotAttempt >= MAX_SCREENSHOT_ATTEMPTS) {
            Log.d(TAG, "Reached max screenshot attempts");
            handler.post(() -> {
                Toast.makeText(this, "已达到最大截图次数", Toast.LENGTH_SHORT).show();
                stopCapturing();
            });
            return;
        }

        currentScreenshotAttempt++;

        // 异步截图
        try {
            Log.d(TAG, "Taking screenshot #" + currentScreenshotAttempt);

            // 验证MediaProjection是否有效，但避免重复请求
            if (mediaProjection == null || (screenshotTaker != null && screenshotTaker.isProjectionStopped())) {
                Log.e(TAG, "MediaProjection不可用");
                Toast.makeText(this, "MediaProjection不可用，停止截图", Toast.LENGTH_SHORT).show();
                stopCapturing();
                return;
            }

            screenshotTaker.takeScreenshotAsync(new ScreenshotTaker.ScreenshotCallback() {
                @Override
                public void onScreenshotTaken(Bitmap screenshot) {
                    if (screenshot != null && !mIsDestroyed && isCapturing) {
                        Log.d(TAG, "Screenshot taken successfully");
                        synchronized (capturedScreens) {
                            capturedScreens.add(screenshot);
                        }

                        // 在UI上显示进度
                        handler.post(() -> {
                            if (!mIsDestroyed) {
                                Toast.makeText(ImprovedLongScreenshotService.this,
                                        "已捕获 " + capturedScreens.size() + " 张截图",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });

                        // 执行自动滚动
                        performScrollSafely();

                        // 确保释放每次截图的资源，但保留MediaProjection
                        if (screenshotTaker != null) {
                            screenshotTaker.releaseVirtualDisplay();
                        }

                        // 安排下一次截图，增加延迟以确保滚动完成
                        handler.postDelayed(() -> {
                            if (isCapturing && !mIsDestroyed) {
                                captureNextScreenshot();
                            }
                        }, SCREENSHOT_INTERVAL + 500); // 增加延迟到1200毫秒
                    } else {
                        Log.e(TAG, "Screenshot is null or service destroyed");
                        if (!mIsDestroyed) {
                            handler.post(() -> {
                                Toast.makeText(ImprovedLongScreenshotService.this,
                                        "截图失败，停止捕获",
                                        Toast.LENGTH_SHORT).show();
                                stopCapturing();
                            });
                        }
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    if (mIsDestroyed) return;

                    Log.e(TAG, "Screenshot error: " + errorMessage);

                    // 如果是由于MediaProjection失效，请求新的权限
                    if (errorMessage.contains("MediaProjection")) {
                        Log.d(TAG, "MediaProjection error, requesting new permission");
                        requestNewMediaProjection();
                        return;
                    }

                    handler.post(() -> {
                        if (!mIsDestroyed) {
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "截图错误: " + errorMessage,
                                    Toast.LENGTH_SHORT).show();

                            // 尝试继续截图，而不是立即停止
                            if (isCapturing && currentScreenshotAttempt < MAX_SCREENSHOT_ATTEMPTS) {
                                handler.postDelayed(() -> captureNextScreenshot(), SCREENSHOT_INTERVAL);
                            } else {
                                stopCapturing();
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception taking screenshot", e);

            if (!mIsDestroyed) {
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "截图错误: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    stopCapturing();
                });
            }
        }
    }

    protected void startCapturing() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing");
            return;
        }

        // 清理之前的截图
        synchronized (capturedScreens) {
            capturedScreens.clear();
        }
        currentScrollableNode = null;
        scrollCount = 0;
        currentScreenshotAttempt = 0;

        // 显示准备截图的提示
        Toast.makeText(this, "准备开始截图，请等待权限栏消失...", Toast.LENGTH_SHORT).show();

        // 标记为捕获状态
        isCapturing = true;

        // 添加延迟，等待权限栏消失
        handler.postDelayed(() -> {
            // 获取无障碍服务实例
            accessibilityService = ScrollScreenshotAccessibilityService.getInstance();
            if (accessibilityService == null) {
                Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_LONG).show();

                // 打开无障碍设置
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                isCapturing = false;
                return;
            }

            // 验证MediaProjection是否有效，但避免重复请求
            if (mediaProjection == null || (screenshotTaker != null && screenshotTaker.isProjectionStopped())) {
                Log.e(TAG, "MediaProjection不可用");
                Toast.makeText(this, "MediaProjection不可用，停止截图", Toast.LENGTH_SHORT).show();
                isCapturing = false;
                return;
            }

            // 确保截屏工具已初始化
            if (screenshotTaker == null) {
                screenshotTaker = new ScreenshotTaker(this);
                if (mediaProjection != null) {
                    screenshotTaker.setMediaProjection(mediaProjection);
                } else {
                    Toast.makeText(this, "未获取屏幕截图权限", Toast.LENGTH_LONG).show();
                    isCapturing = false;
                    return;
                }
            }

            // 更改图标为截图状态
            ImageView iconView = floatingView.findViewById(R.id.icon);
            iconView.setImageResource(R.drawable.ic_capturing);
            Toast.makeText(this, "开始智能长截图，双击停止", Toast.LENGTH_LONG).show();

            // 通过无障碍服务开始截图流程
            accessibilityService.startLongScreenshot(new ScrollScreenshotAccessibilityService.LongScreenshotCallback() {
                @Override
                public void onReadyForCapture(AccessibilityNodeInfo scrollableNode) {
                    // 保存可滚动节点引用
                    currentScrollableNode = scrollableNode;

                    // 开始第一次截图 - 使用新的流程
                    captureFirstScreenshot(); // 修改这里：使用新实现的方法
                }

                @Override
                public void onError(String message) {
                    handler.post(() -> {
                        Toast.makeText(ImprovedLongScreenshotService.this,
                                "智能长截图错误: " + message, Toast.LENGTH_LONG).show();

                        // 如果无障碍服务不可用，尝试使用普通方式
                        if (message.contains("未找到可滚动内容")) {
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "尝试使用普通长截图...", Toast.LENGTH_SHORT).show();
                            startNormalCapturing();
                        }
                    });
                }
            });
        }, PERMISSION_BAR_WAIT_TIME); // 添加延迟，等待权限栏消失
    }

    /**
     * 截取第一张截图
     * 在启动智能长截图时立即执行
     */
    private void captureFirstScreenshot() {
        if (!isCapturing || screenshotTaker == null || mediaProjection == null) {
            stopCapturing();
            return;
        }

        // 执行截图
        screenshotTaker.takeScreenshotAsync(new ScreenshotTaker.ScreenshotCallback() {
            @Override
            public void onScreenshotTaken(Bitmap screenshot) {
                if (screenshot == null || !isCapturing) {
                    stopCapturing();
                    return;
                }

                // 保存截图
                synchronized (capturedScreens) {
                    capturedScreens.add(screenshot);
                }

                // 显示进度
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "已捕获第1张截图", Toast.LENGTH_SHORT).show();
                });

                // 进入"先滚动-再截图"的循环
                scrollAndCaptureNext();
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "截图错误: " + errorMessage, Toast.LENGTH_SHORT).show();
                });

                // 如果是MediaProjection错误，尝试重新请求权限
                if (errorMessage.contains("MediaProjection")) {
                    requestNewMediaProjection();
                    return;
                }

                stopCapturing();
            }
        });
    }

    /**
     * 执行滚动和截图循环
     * 先滚动再截图，避免权限栏问题
     */
    private void scrollAndCaptureNext() {
        if (!isCapturing) {
            stopCapturing();
            return;
        }

        // 检查是否需要继续滚动
        if (scrollCount >= MAX_SCROLL_COUNT ||
                (currentScrollableNode != null &&
                        accessibilityService.isScrolledToBottom(currentScrollableNode))) {

            // 已经到底部或达到最大滚动次数，停止截图
            stopCapturing();
            return;
        }

        // 先执行滚动
        handler.post(() -> {
            Toast.makeText(ImprovedLongScreenshotService.this,
                    "正在滚动...", Toast.LENGTH_SHORT).show();
        });

        // 执行滚动操作
        handler.postDelayed(() -> {
            // 使用无障碍服务执行精确滚动
            boolean scrolled = false;
            if (currentScrollableNode != null) {
                scrolled = accessibilityService.scrollDown(currentScrollableNode);
            }

            if (!scrolled) {
                // 当使用备用方法时，使用较小的滚动比例
                scrolled = accessibilityService.performGestureScroll(0.3f); // 改为0.4f，只滚动40%的屏幕高度
            }

            if (scrolled) {
                scrollCount++;

                // 根据滚动次数调整等待时间，每3次滚动后增加额外等待
                int waitTime = SCROLL_DELAY + 2000; // 基础等待增加到3秒
                if (scrollCount > 0) {
                    waitTime += 2000; // 每3次滚动额外增加2秒

                    handler.post(() -> {
                        Toast.makeText(ImprovedLongScreenshotService.this,
                                "检测到可能的权限刷新，额外等待中...", Toast.LENGTH_SHORT).show();
                    });
                }

                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "等待内容稳定...", Toast.LENGTH_SHORT).show();
                });

                // 等待一段时间后再截图，确保权限栏已消失
                handler.postDelayed(() -> {
                    // 滚动后执行截图
                    captureAfterScroll();
                }, waitTime);
            } else {
                // 无法滚动，结束截图
                stopCapturing();
            }
        }, 300); // 短暂延迟再滚动
    }

    /**
     * 滚动后执行截图
     * 确保权限栏已消失，并防止与下一次权限请求重叠
     */
    private void captureAfterScroll() {
        if (!isCapturing || screenshotTaker == null || mediaProjection == null) {
            stopCapturing();
            return;
        }

        // 执行截图
        screenshotTaker.takeScreenshotAsync(new ScreenshotTaker.ScreenshotCallback() {
            @Override
            public void onScreenshotTaken(Bitmap screenshot) {
                if (screenshot == null || !isCapturing) {
                    stopCapturing();
                    return;
                }

                // 保存截图
                synchronized (capturedScreens) {
                    capturedScreens.add(screenshot);
                }

                // 显示进度
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "已捕获 " + capturedScreens.size() + " 张截图",
                            Toast.LENGTH_SHORT).show();
                });

                // 在截图完成后添加额外延迟，避免与下一次权限请求重叠
                handler.postDelayed(() -> {
                    // 延迟后继续执行下一次滚动和截图
                    scrollAndCaptureNext();
                }, 1000); // 添加1秒额外延迟
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "截图错误: " + errorMessage, Toast.LENGTH_SHORT).show();
                });

                // 如果是MediaProjection错误，尝试重新请求权限
                if (errorMessage.contains("MediaProjection")) {
                    requestNewMediaProjection();
                    return;
                }

                stopCapturing();
            }
        });
    }

    // 智能截取当前屏幕
    private void captureCurrentScreen() {
        if (!isCapturing || screenshotTaker == null) {
            stopCapturing();
            return;
        }

        // 在非首次截图时，先等待权限栏消失
        if (scrollCount > 0) {
            // 首先提示用户
            handler.post(() -> {
                Toast.makeText(ImprovedLongScreenshotService.this,
                        "等待权限栏消失...", Toast.LENGTH_SHORT).show();
            });

            // 等待足够时间让权限栏消失
            handler.postDelayed(() -> {
                // 权限栏消失后，再检查权限并执行截图
                performActualScreenCapture();
            }, 3500); // 使用固定的权限栏等待时间
        } else {
            // 首次截图，直接执行
            performActualScreenCapture();
        }
    }
    /**
     * 执行实际的截图操作
     * 确保在权限栏消失后执行
     */
    private void performActualScreenCapture() {
        if (!isCapturing || screenshotTaker == null) {
            stopCapturing();
            return;
        }

        // 确认MediaProjection有效，但避免重复请求
        if (mediaProjection == null || screenshotTaker.isProjectionStopped()) {
            Log.e(TAG, "MediaProjection不可用");
            Toast.makeText(this, "MediaProjection不可用，停止截图", Toast.LENGTH_SHORT).show();
            stopCapturing();
            return;
        }

        // 执行实际截图操作
        screenshotTaker.takeScreenshotAsync(new ScreenshotTaker.ScreenshotCallback() {
            @Override
            public void onScreenshotTaken(Bitmap screenshot) {
                if (screenshot == null || !isCapturing) {
                    stopCapturing();
                    return;
                }

                // 保存截图
                synchronized (capturedScreens) {
                    capturedScreens.add(screenshot);
                }

                // 显示进度
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "已捕获 " + capturedScreens.size() + " 张截图",
                            Toast.LENGTH_SHORT).show();
                });

                // 检查是否需要继续滚动
                if (scrollCount >= MAX_SCROLL_COUNT ||
                        (currentScrollableNode != null &&
                                accessibilityService.isScrolledToBottom(currentScrollableNode))) {

                    // 已经到底部或达到最大滚动次数，停止截图
                    stopCapturing();
                    return;
                }

                // 执行滚动并等待
                handler.postDelayed(() -> {
                    // 使用无障碍服务执行精确滚动
                    boolean scrolled = false;
                    if (currentScrollableNode != null) {
                        scrolled = accessibilityService.scrollDown(currentScrollableNode);
                    }

                    if (!scrolled) {
                        // 备用滚动方法
                        scrolled = accessibilityService.performScroll();
                    }

                    if (scrolled) {
                        scrollCount++;

                        // 滚动后等待固定时间让内容稳定下来
                        handler.postDelayed(() -> {
                            // 递归调用，这次会先等待权限栏消失
                            captureCurrentScreen(); // 重新开始循环
                        }, SCROLL_DELAY);
                    } else {
                        // 无法滚动，结束截图
                        stopCapturing();
                    }
                }, 1000); // 短暂延迟再滚动
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    Toast.makeText(ImprovedLongScreenshotService.this,
                            "截图错误: " + errorMessage, Toast.LENGTH_SHORT).show();
                });

                // 如果是MediaProjection错误，尝试重新请求权限
                if (errorMessage.contains("MediaProjection")) {
                    requestNewMediaProjection();
                    return;
                }

                stopCapturing();
            }
        });
    }

    private void requestNewMediaProjection() {
        // 如果MediaProjection已经存在，且未停止，则不再请求新权限
        if (mediaProjection != null && screenshotTaker != null && !screenshotTaker.isProjectionStopped()) {
            Log.d(TAG, "MediaProjection已存在，无需请求新权限");

            // 如果正在截图过程中，可以继续截图
            if (isCapturing) {
                handler.postDelayed(() -> {
                    captureNextScreenshot();
                }, 500);
            }
            return;
        }

        // 向MainActivity请求新的媒体投影权限
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("com.example.longscreenshot.REQUEST_PROJECTION");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        // 显示等待消息
        Toast.makeText(this, "请授予屏幕录制权限", Toast.LENGTH_LONG).show();

        // 标记为非捕获状态，等待新权限
        isCapturing = false;
    }

    private void performScrollSafely() {
        try {
            // 在工作线程中执行滚动
            new Thread(() -> {
                try {
                    Log.d(TAG, "Performing screen scroll");

                    // 尝试使用无障碍服务滚动
                    boolean scrolled = false;
                    if (accessibilityService != null) {
                        scrolled = accessibilityService.performGestureScroll(0.3f); // 改为0.5f，默认滚动50%的屏幕高度
                        if (scrolled) {
                            Log.d(TAG, "Scroll performed via accessibility service");
                            return;
                        }
                    }

                    // 备用方案：使用shell命令滚动
                    String[] command = {
                            "input", "swipe",
                            "540", "700",  // 起始点(x,y) - 使用屏幕中间靠下位置
                            "540", "500",   // 结束点(x,y) - 向上滚动到屏幕上部
                            "300"           // 持续时间(毫秒) - 确保滚动操作完成
                    };

                    Process process = Runtime.getRuntime().exec(command);

                    // 等待命令执行完成
                    int exitCode = process.waitFor();
                    Log.d(TAG, "Scroll command executed with exit code: " + exitCode);

                    // 让屏幕有足够时间滚动和稳定
                    Thread.sleep(700);  // 增加等待时间到500毫秒

                    Log.d(TAG, "Scroll completed, screen stabilized");
                } catch (IOException e) {
                    Log.e(TAG, "Failed to execute scroll command: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error in performScrollSafely", e);
        }
    }

    private boolean isDestroyed() {
        return mIsDestroyed;
    }

    protected void stopCapturing() {
        if (!isCapturing) return;

        Log.d(TAG, "Stopping capture");
        isCapturing = false;

        // 通知无障碍服务停止
        if (accessibilityService != null) {
            accessibilityService.stopLongScreenshot();
        }

        // 处理捕获的屏幕图像
        if (!capturedScreens.isEmpty()) {
            // 显示正在处理的提示
            Toast.makeText(this, "正在处理截图...", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Processing " + capturedScreens.size() + " screenshots");

            // 在后台线程中处理图像拼接
            new Thread(() -> {
                try {
                    // 创建副本以避免同步问题
                    final List<Bitmap> screens;
                    synchronized (capturedScreens) {
                        screens = new ArrayList<>(capturedScreens);
                        capturedScreens.clear();
                    }

                    if (screens.isEmpty()) {
                        Log.e(TAG, "No screenshots to process");
                        handler.post(() -> {
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "没有可用的截图",
                                    Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    Log.d(TAG, "Stitching images");

                    // 使用智能拼接算法
                    final Bitmap result;
                    if (screens.size() > 1) {
                        result = smartStitchTextContent(screens);
                    } else {
                        result = screens.get(0);
                    }

                    if (result == null) {
                        Log.e(TAG, "Failed to stitch images");
                        handler.post(() -> {
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "拼接截图失败",
                                    Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    // 保存结果图像
                    Log.d(TAG, "Saving stitched image");
                    final String filename = saveImage(result);

                    // 在UI线程显示通知
                    handler.post(() -> {
                        if (filename != null) {
                            Log.d(TAG, "Image saved to: " + filename);
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "长截图已保存: " + filename,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Log.e(TAG, "Failed to save image");
                            Toast.makeText(ImprovedLongScreenshotService.this,
                                    "保存截图失败",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

                    // 清理屏幕截图释放内存
                    Log.d(TAG, "Cleaning up resources");
                    for (Bitmap bitmap : screens) {
                        if (bitmap != null && !bitmap.isRecycled()) {
                            try {
                                bitmap.recycle();
                            } catch (Exception e) {
                                Log.e(TAG, "Error recycling bitmap", e);
                            }
                        }
                    }
                    screens.clear();

                    // 清理结果位图
                    if (result != null && !result.isRecycled()) {
                        try {
                            result.recycle();
                        } catch (Exception e) {
                            Log.e(TAG, "Error recycling result bitmap", e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing screenshots: " + e.getMessage(), e);

                    handler.post(() -> {
                        Toast.makeText(ImprovedLongScreenshotService.this,
                                "处理截图时出错: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        } else {
            Log.w(TAG, "No screenshots captured");
            Toast.makeText(this, "没有捕获到任何截图", Toast.LENGTH_SHORT).show();
        }

        // 只释放VirtualDisplay和ImageReader，保留MediaProjection对象
        if (screenshotTaker != null) {
            Log.d(TAG, "Releasing virtual display");
            try {
                screenshotTaker.releaseVirtualDisplay(); // 只释放虚拟显示和ImageReader
            } catch (Exception e) {
                Log.e(TAG, "Error releasing virtual display", e);
            }
        }

        // 重置可滚动节点引用
        currentScrollableNode = null;

        // 更改图标为正常状态
        ImageView iconView = floatingView.findViewById(R.id.icon);
        iconView.setImageResource(R.drawable.ic_screenshot);

        Log.d(TAG, "Capture stopped");
    }

    /**
     * 针对文字内容优化的智能拼接算法 - 改进版
     * 主要改进：更精确地识别和删除重复内容
     */
    private Bitmap smartStitchTextContent(List<Bitmap> images) {
        if (images.isEmpty()) return null;
        if (images.size() == 1) return images.get(0);

        // 获取最大宽度
        int maxWidth = 0;
        for (Bitmap bitmap : images) {
            maxWidth = Math.max(maxWidth, bitmap.getWidth());
        }

        // 使用改进的边界检测算法
        List<OverlapInfo> overlapInfos = findOptimalStitchPointsAdvanced(images);

        // 计算总高度 - 现在更精确地考虑重叠部分
        int totalHeight = 0;
        for (int i = 0; i < images.size(); i++) {
            Bitmap bitmap = images.get(i);

            if (i == 0) {
                // 第一张图片完整添加
                totalHeight += bitmap.getHeight();
            } else {
                // 后续图片只添加非重叠部分的高度
                OverlapInfo info = overlapInfos.get(i-1);
                totalHeight += bitmap.getHeight() - info.overlapHeight;
            }
        }

        // 创建结果图像
        Bitmap result = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        paint.setAntiAlias(true); // 添加抗锯齿效果

        // 绘制图像 - 使用精确的重叠信息
        int yOffset = 0;
        for (int i = 0; i < images.size(); i++) {
            Bitmap bitmap = images.get(i);

            if (i == 0) {
                // 第一张图片完整绘制
                canvas.drawBitmap(bitmap, 0, 0, paint);
                yOffset = bitmap.getHeight();
            } else {
                // 后续图片只绘制非重叠部分，精确对齐
                OverlapInfo info = overlapInfos.get(i-1);

                // 计算源矩形（跳过当前图片顶部的重叠部分）
                Rect srcRect = new Rect(
                        0,
                        info.overlapHeight,
                        bitmap.getWidth(),
                        bitmap.getHeight()
                );

                // 计算目标矩形（放置在目前的偏移位置）
                Rect dstRect = new Rect(
                        0,
                        yOffset,
                        bitmap.getWidth(),
                        yOffset + bitmap.getHeight() - info.overlapHeight
                );

                // 绘制非重叠部分
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint);

                // 更新偏移
                yOffset += bitmap.getHeight() - info.overlapHeight;
            }
        }

        Log.d(TAG, "智能拼接完成: 处理了 " + images.size() + " 张图片，生成高度为 " + totalHeight + " 像素的长图");
        return result;
    }

    /**
     * 表示两张图片之间的重叠信息
     */
    private static class OverlapInfo {
        int overlapHeight;       // 重叠区域的高度
        int matchScore;          // 匹配分数（越低越好）
        boolean isParagraphBreak; // 是否在段落边界处

        OverlapInfo(int height, int score, boolean isParagraph) {
            this.overlapHeight = height;
            this.matchScore = score;
            this.isParagraphBreak = isParagraph;
        }
    }


    /**
     * 改进版的拼接点查找算法
     * 返回每对相邻图片之间的最佳重叠信息
     */
    private List<OverlapInfo> findOptimalStitchPointsAdvanced(List<Bitmap> images) {
        List<OverlapInfo> overlapInfos = new ArrayList<>();

        for (int i = 0; i < images.size() - 1; i++) {
            Bitmap current = images.get(i);
            Bitmap next = images.get(i + 1);

            // 默认重叠值（通常为屏幕高度的35-40%）
            int defaultOverlap = (int)(current.getHeight() * 0.38f);

            // 使用改进后的边界检测算法
            OverlapInfo info = findOptimalOverlap(current, next, defaultOverlap);
            overlapInfos.add(info);

            Log.d(TAG, "图片 " + i + " 和 " + (i+1) + " 之间的重叠像素: " +
                    info.overlapHeight + ", 匹配分数: " + info.matchScore +
                    (info.isParagraphBreak ? " (段落边界)" : ""));
        }

        return overlapInfos;
    }

    /**
     * 增强版计算重叠区域分数
     * 使用特征匹配和内容感知算法提高准确性
     */
    private int calculateAdvancedOverlapScore(Bitmap top, Bitmap bottom, int overlapHeight) {
        // 参数有效性检查
        if (overlapHeight <= 0 || top == null || bottom == null ||
                overlapHeight >= top.getHeight() || overlapHeight >= bottom.getHeight()) {
            return Integer.MAX_VALUE;
        }

        int width = Math.min(top.getWidth(), bottom.getWidth());

        // 将采样率提高到更高的水平以提高准确性
        int sampleStepX = Math.max(1, width / 200);  // 水平方向上更密集的采样
        int sampleStepY = Math.max(1, overlapHeight / 40);  // 垂直方向上更密集的采样

        // 创建滑动窗口来比较重叠区域
        int windowSize = 8;  // 特征窗口大小
        int totalDiff = 0;
        int samplesCount = 0;

        // 基于特征的比较，而不是简单的像素比较
        // 对重叠区域进行分块计算特征差异
        for (int y = 0; y < overlapHeight - windowSize; y += sampleStepY) {
            for (int x = 0; x < width - windowSize; x += sampleStepX) {
                // 计算top图像中窗口的特征
                int topFeature = calculateWindowFeature(top, x, top.getHeight() - overlapHeight + y, windowSize);

                // 计算bottom图像中窗口的特征
                int bottomFeature = calculateWindowFeature(bottom, x, y, windowSize);

                // 计算特征差异
                int featureDiff = Math.abs(topFeature - bottomFeature);
                totalDiff += featureDiff;
                samplesCount++;
            }
        }

        // 避免除以零
        if (samplesCount == 0) return Integer.MAX_VALUE;

        // 返回标准化的差异分数
        return totalDiff / samplesCount;
    }

    /**
     * 计算图像窗口区域的特征值
     * 使用简化的感知哈希算法
     */
    private int calculateWindowFeature(Bitmap image, int startX, int startY, int windowSize) {
        int feature = 0;
        int prevLuma = -1;

        for (int y = 0; y < windowSize; y++) {
            for (int x = 0; x < windowSize; x++) {
                try {
                    int pixel = image.getPixel(startX + x, startY + y);

                    // 计算亮度值 (简化的YUV转换)
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;
                    int luma = (r * 299 + g * 587 + b * 114) / 1000;

                    // 使用亮度梯度作为特征
                    if (prevLuma >= 0) {
                        // 将梯度方向编码为特征的一位
                        feature = (feature << 1) | (luma > prevLuma ? 1 : 0);
                    }

                    prevLuma = luma;
                } catch (Exception e) {
                    // 忽略越界错误
                }
            }
        }

        return feature;
    }

    /**
     * 查找两张图片间的最佳重叠区域
     * 采用多级滑动窗口算法，更精确地识别重复内容
     */
    private OverlapInfo findOptimalOverlap(Bitmap top, Bitmap bottom, int defaultOverlap) {
        // 安全检查
        if (top == null || bottom == null) {
            return new OverlapInfo(defaultOverlap, Integer.MAX_VALUE, false);
        }

        // 计算搜索范围
        int minOverlap = Math.max(100, defaultOverlap / 2); // 最小重叠增加到100像素
        int maxOverlap = Math.min(defaultOverlap * 2, Math.min(top.getHeight(), bottom.getHeight()) / 2); // 最大不超过图片高度的一半

        // 使用更精细的步长
        int coarseStep = 20;  // 粗略搜索的步长
        int fineStep = 2;     // 精细搜索的步长

        // 初始化最佳匹配变量
        int bestOverlap = defaultOverlap;
        int bestScore = Integer.MAX_VALUE;

        // 第一阶段：粗略搜索最佳重叠区域
        for (int overlapHeight = minOverlap; overlapHeight <= maxOverlap; overlapHeight += coarseStep) {
            int score = calculateAdvancedOverlapScore(top, bottom, overlapHeight);

            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlapHeight;
            }
        }

        // 第二阶段：在最佳粗略区域周围进行精细搜索
        int fineSearchStart = Math.max(minOverlap, bestOverlap - coarseStep);
        int fineSearchEnd = Math.min(maxOverlap, bestOverlap + coarseStep);

        bestScore = Integer.MAX_VALUE; // 重置为精细搜索

        for (int overlapHeight = fineSearchStart; overlapHeight <= fineSearchEnd; overlapHeight += fineStep) {
            int score = calculateAdvancedOverlapScore(top, bottom, overlapHeight);

            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlapHeight;
            }
        }

        // 检查是否在段落边界
        int isParagraphBreak = detectParagraphBoundaryAdvanced(top, bottom, bestOverlap);

        // 如果找到段落边界且分数合理，优先使用段落边界
        if (isParagraphBreak > 0) {
            return new OverlapInfo(bestOverlap, bestScore, true);
        }

        // 最后一步：精确像素级对齐
        int finalOverlap = findExactPixelAlignment(top, bottom, bestOverlap);

        return new OverlapInfo(finalOverlap, bestScore, false);
    }


    /**
     * 增强版的文本边界查找算法
     * 相比原来的算法，这个版本会先识别文本行，再找最佳拼接点
     */
    private int findTextBoundaryEnhanced(Bitmap top, Bitmap bottom, int defaultOverlap) {
        // 安全检查
        if (top == null || bottom == null) {
            return defaultOverlap;
        }

        // 计算搜索范围
        int minOverlap = Math.max(50, defaultOverlap / 2); // 最小50像素重叠
        int maxOverlap = Math.min(defaultOverlap * 2, top.getHeight() / 2); // 最大不超过图片高度的一半

        // 使用更精细的步长
        int step = 5;

        // 初始化最佳匹配变量
        int bestOverlap = defaultOverlap;
        int bestScore = Integer.MAX_VALUE;

        // 在每个可能的重叠区域内查找边界
        for (int overlapHeight = minOverlap; overlapHeight <= maxOverlap; overlapHeight += step) {
            // 计算当前重叠区域的分数
            int score = calculateOverlapScore(top, bottom, overlapHeight);

            // 如果找到更好的分数
            if (score < bestScore) {
                bestScore = score;
                bestOverlap = overlapHeight;

                // 如果分数足够好，可以提前退出循环
                if (score < 30) { // 阈值可以调整
                    break;
                }
            }
        }

        // 额外检查：尝试检测文本段落边界
        int paragraphBoundary = detectParagraphBoundaryAdvanced(top, bottom, bestOverlap);
        if (paragraphBoundary > 0) {
            // 如果找到段落边界，使用它代替像素匹配结果
            return paragraphBoundary;
        }

        return bestOverlap;
    }

    /**
     * 增强版的重叠区域分数计算
     * 与旧版相比，增加了对文本行的检测
     */
    private int calculateOverlapScore(Bitmap top, Bitmap bottom, int overlapHeight) {
        // 检查参数
        if (overlapHeight <= 0 || top == null || bottom == null ||
                overlapHeight >= top.getHeight() || overlapHeight >= bottom.getHeight()) {
            return Integer.MAX_VALUE;
        }

        int width = Math.min(top.getWidth(), bottom.getWidth());
        int totalDiff = 0;
        int samplesCount = 0;

        // 采样步长 - 减小可提高精度但增加计算量
        int sampleStepX = Math.max(1, width / 100);
        int sampleStepY = Math.max(1, overlapHeight / 20);

        // 增加水平线扫描 - 检测文本行
        boolean[] horizontalLines = new boolean[overlapHeight];
        int[] horizontalLineDiffs = new int[overlapHeight];

        // 计算每一行的差异
        for (int y = 0; y < overlapHeight; y++) {
            int rowDiff = 0;
            int rowSamples = 0;

            // 扫描这一行的像素
            for (int x = 0; x < width; x += sampleStepX) {
                int topY = top.getHeight() - overlapHeight + y;
                int bottomY = y;

                try {
                    int topColor = top.getPixel(x, topY);
                    int bottomColor = bottom.getPixel(x, bottomY);

                    // 计算RGB差异
                    int rDiff = Math.abs(((topColor >> 16) & 0xff) - ((bottomColor >> 16) & 0xff));
                    int gDiff = Math.abs(((topColor >> 8) & 0xff) - ((bottomColor >> 8) & 0xff));
                    int bDiff = Math.abs((topColor & 0xff) - (bottomColor & 0xff));

                    int pixelDiff = rDiff + gDiff + bDiff;
                    rowDiff += pixelDiff;
                    rowSamples++;
                } catch (Exception e) {
                    // 忽略越界错误
                    Log.e(TAG, "像素读取错误: " + e.getMessage());
                }
            }

            // 保存这一行的平均差异
            if (rowSamples > 0) {
                horizontalLineDiffs[y] = rowDiff / rowSamples;
            }
        }

        // 检测纯色行（可能是段落间的空白）
        for (int y = 0; y < overlapHeight; y++) {
            if (horizontalLineDiffs[y] < 30) { // 阈值可调整
                horizontalLines[y] = true;
            }
        }

        // 为空白行赋予更低的权重（更可能成为拼接点）
        int weightedScore = 0;
        for (int y = 0; y < overlapHeight; y++) {
            int weight = horizontalLines[y] ? 1 : 10; // 空白行权重更低
            weightedScore += horizontalLineDiffs[y] * weight;
        }

        return weightedScore / overlapHeight;
    }

    /**
     * 改进的段落边界检测
     * 更好地识别文本内容中的自然分割点
     * 如果找到段落边界，返回边界位置；否则返回-1
     */
    private int detectParagraphBoundaryAdvanced(Bitmap top, Bitmap bottom, int bestOverlap) {
        // 检查范围
        int searchRange = 100; // 增加搜索范围
        int startY = Math.max(0, top.getHeight() - bestOverlap - searchRange/2);
        int endY = Math.min(top.getHeight(), top.getHeight() - bestOverlap + searchRange/2);
        int width = top.getWidth();

        // 存储每行的空白度
        int[] emptyScores = new int[endY - startY];

        // 扫描上图底部区域寻找水平空白行
        for (int y = startY; y < endY; y++) {
            int whitePixels = 0;
            int samplesCount = 0;

            // 密集采样以提高准确性
            for (int x = 0; x < width; x += 5) {
                try {
                    int pixel = top.getPixel(x, y);

                    // 改进的空白检测 - 考虑近白色和浅灰色
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;

                    // 计算与白色的接近度
                    int whiteness = (r + g + b) / 3;
                    if (whiteness > 230) { // 更宽松的阈值
                        whitePixels++;
                    }

                    samplesCount++;
                } catch (Exception e) {
                    // 忽略越界
                }
            }

            // 计算此行的空白得分 (0-100)
            if (samplesCount > 0) {
                emptyScores[y - startY] = whitePixels * 100 / samplesCount;
            }
        }

        // 寻找空白行群集 - 段落间通常有连续几行空白
        int consecutiveWhiteThreshold = 3; // 需要至少3行连续空白
        int whiteScoreThreshold = 90;      // 空白得分阈值

        for (int i = 1; i < emptyScores.length - consecutiveWhiteThreshold; i++) {
            boolean isWhiteCluster = true;

            // 检查连续几行是否都是空白
            for (int j = 0; j < consecutiveWhiteThreshold; j++) {
                if (emptyScores[i + j] < whiteScoreThreshold) {
                    isWhiteCluster = false;
                    break;
                }
            }

            if (isWhiteCluster) {
                // 发现段落边界，计算对应的重叠高度
                return top.getHeight() - (startY + i);
            }
        }

        // 没有找到明显的段落边界
        return -1;
    }
    /**
     * 精确像素级对齐方法
     * 在确定大致重叠范围后，找到最精确的对齐位置
     */
    private int findExactPixelAlignment(Bitmap top, Bitmap bottom, int approximateOverlap) {
        // 定义搜索范围 - 在近似值附近上下20像素内搜索
        int searchRange = 20;
        int minOverlap = Math.max(50, approximateOverlap - searchRange);
        int maxOverlap = Math.min(approximateOverlap + searchRange, Math.min(top.getHeight(), bottom.getHeight()) - 10);

        int bestOverlap = approximateOverlap;
        double bestSimilarity = 0;

        // 对每个可能的重叠高度计算相似度
        for (int overlap = minOverlap; overlap <= maxOverlap; overlap++) {
            double similarity = calculateExactSimilarity(top, bottom, overlap);

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestOverlap = overlap;
            }
        }

        return bestOverlap;
    }

    /**
     * 计算两个区域的精确相似度
     * 使用归一化互相关(NCC)方法，更适合精确对齐
     */
    private double calculateExactSimilarity(Bitmap top, Bitmap bottom, int overlapHeight) {
        // 选择中间的一条垂直线进行比较
        int x = top.getWidth() / 2;
        int width = Math.min(30, top.getWidth() / 10); // 使用中间一小条区域

        int sampleCount = 0;
        int matchCount = 0;

        // 比较重叠区域的对应像素
        for (int i = 0; i < overlapHeight; i++) {
            for (int dx = 0; dx < width; dx++) {
                try {
                    int topY = top.getHeight() - overlapHeight + i;
                    int bottomY = i;

                    int topPixel = top.getPixel(x + dx, topY);
                    int bottomPixel = bottom.getPixel(x + dx, bottomY);

                    // 计算颜色差异
                    int rDiff = Math.abs(((topPixel >> 16) & 0xff) - ((bottomPixel >> 16) & 0xff));
                    int gDiff = Math.abs(((topPixel >> 8) & 0xff) - ((bottomPixel >> 8) & 0xff));
                    int bDiff = Math.abs((topPixel & 0xff) - (bottomPixel & 0xff));

                    // 总差异
                    int totalDiff = rDiff + gDiff + bDiff;

                    // 如果差异小于阈值，认为是匹配的
                    if (totalDiff < 30) {
                        matchCount++;
                    }

                    sampleCount++;
                } catch (Exception e) {
                    // 忽略越界
                }
            }
        }

        if (sampleCount == 0) return 0;

        // 返回匹配率
        return (double) matchCount / sampleCount;
    }

    /**
     * 查找图像间的最佳拼接点
     * 对于文字内容，尝试在段落分界处拼接
     */
    private List<Integer> findOptimalStitchPoints(List<Bitmap> images) {
        List<Integer> stitchPoints = new ArrayList<>();

        for (int i = 0; i < images.size() - 1; i++) {
            Bitmap current = images.get(i);
            Bitmap next = images.get(i + 1);

            // 默认重叠20%
            int defaultOverlap = (int)(current.getHeight() * 0.2f);

            // 分析两张图片找出最佳拼接点
            int overlap = findTextBoundary(current, next, defaultOverlap);
            stitchPoints.add(overlap);
        }

        return stitchPoints;
    }

    /**
     * 在两张图片中寻找文本分界点
     * 使用像素对比算法寻找相似区域
     */
    private int findTextBoundary(Bitmap top, Bitmap bottom, int defaultOverlap) {
        // 获取两张图片的重叠区域
        int searchHeight = Math.min(defaultOverlap * 2,
                Math.min(top.getHeight(), bottom.getHeight()));

        int bestOverlap = defaultOverlap;
        int bestDiffScore = Integer.MAX_VALUE;

        int topSearchStart = top.getHeight() - searchHeight;

        // 步长，可以调整以平衡性能和精度
        int step = 5;

        // 在可能的重叠范围内搜索最佳匹配位置
        for (int overlapHeight = defaultOverlap / 2;
             overlapHeight <= Math.min(defaultOverlap * 2, top.getHeight() / 2);
             overlapHeight += step) {

            // 计算当前重叠区域的差异分数
            int diffScore = compareOverlapRegion(top, bottom, overlapHeight);

            // 如果找到更好的匹配
            if (diffScore < bestDiffScore) {
                bestDiffScore = diffScore;
                bestOverlap = overlapHeight;
            }
        }

        return bestOverlap;
    }

    /**
     * 比较两张图片在特定重叠区域的差异
     * 返回差异分数，分数越低表示匹配度越高
     */
    private int compareOverlapRegion(Bitmap top, Bitmap bottom, int overlapHeight) {
        // 检查参数有效性
        if (overlapHeight <= 0 || overlapHeight >= top.getHeight() || overlapHeight >= bottom.getHeight()) {
            return Integer.MAX_VALUE;
        }

        int width = Math.min(top.getWidth(), bottom.getWidth());
        int totalDiff = 0;
        int samplesCount = 0;

        // 使用采样比较而不是逐像素比较，提高性能
        int sampleStep = 10;

        // 比较重叠区域
        for (int x = 0; x < width; x += sampleStep) {
            for (int y = 0; y < overlapHeight; y += sampleStep) {
                int topY = top.getHeight() - overlapHeight + y;
                int bottomY = y;

                // 获取像素
                int topPixel = top.getPixel(x, topY);
                int bottomPixel = bottom.getPixel(x, bottomY);

                // 计算RGB差异
                int rDiff = Math.abs(((topPixel >> 16) & 0xff) - ((bottomPixel >> 16) & 0xff));
                int gDiff = Math.abs(((topPixel >> 8) & 0xff) - ((bottomPixel >> 8) & 0xff));
                int bDiff = Math.abs((topPixel & 0xff) - (bottomPixel & 0xff));

                totalDiff += rDiff + gDiff + bDiff;
                samplesCount++;
            }
        }

        // 返回平均差异
        return samplesCount > 0 ? totalDiff / samplesCount : Integer.MAX_VALUE;
    }

    private String saveImage(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "Cannot save null bitmap");
            return null;
        }

        // 创建文件名
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "智能长截图_" + timeStamp + ".png";
        Log.d(TAG, "Saving image with name: " + fileName);

        // 根据Android版本确定保存路径
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore API
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots");

            ContentResolver resolver = getContentResolver();
            Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (imageUri != null) {
                try (OutputStream out = resolver.openOutputStream(imageUri)) {
                    if (out != null) {
                        boolean success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        Log.d(TAG, "Image saved to MediaStore: " + success);
                        if (success) {
                            return "Pictures/Screenshots/" + fileName;
                        }
                    } else {
                        Log.e(TAG, "Failed to open output stream");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error saving image: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.e(TAG, "Failed to create image URI");
            }
            return null;
        } else {
            // Android 9及以下直接使用文件
            File storageDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Screenshots");

            // 确保目录存在
            if (!storageDir.exists()) {
                boolean created = storageDir.mkdirs();
                Log.d(TAG, "Directory created: " + created);
            }

            // 创建文件
            File imageFile = new File(storageDir, fileName);
            Log.d(TAG, "Saving to file: " + imageFile.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                boolean success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                Log.d(TAG, "Image saved to file: " + success);

                if (success) {
                    // 通知媒体库更新
                    MediaScannerConnection.scanFile(this,
                            new String[]{imageFile.getAbsolutePath()}, null, null);

                    return imageFile.getAbsolutePath();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving image: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }

    @Override
    public void onDestroy() {
        mIsDestroyed = true;
        super.onDestroy();

        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }

        // 确保截图停止
        if (isCapturing) {
            stopCapturing();
        }

        // 完全释放资源
        if (screenshotTaker != null) {
            screenshotTaker.releaseCompletely(); // 此处才完全释放MediaProjection
            screenshotTaker = null;
        }

        mediaProjection = null;
        currentScrollableNode = null;
    }
}