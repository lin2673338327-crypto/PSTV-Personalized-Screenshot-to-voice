package com.example.longscreenshot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final int REQUEST_CODE_OVERLAY = 102;
    private static final int REQUEST_CODE_ALL_FILES = 104;
    private static final int REQUEST_CODE_ACCESSIBILITY = 105;
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 106;
    private static final int REQUEST_CODE_NOTIFICATION = 107;

    private static ScrollView scrollView;
    private Button startFloatingButton;
    private boolean isRequestingPermissions = false;

    private boolean isMediaProjectionPermissionRequested = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化ScrollView
        scrollView = findViewById(R.id.scrollView);
        startFloatingButton = findViewById(R.id.startFloatingButton);

        // 添加一些示例内容到ScrollView
        TextView contentView = findViewById(R.id.contentView);
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            contentBuilder.append("这是第 ").append(i).append(" 行内容\n");
            // 每10行添加一些更长的内容
            if (i % 10 == 0) {
                contentBuilder.append("这是一段较长的文本内容，用于测试长截图功能。")
                        .append("长截图可以捕获超出屏幕范围的内容。")
                        .append("通过滚动和截取多个图像，然后将它们拼接在一起。\n\n");
            }
        }
        contentView.setText(contentBuilder.toString());

        // 设置启动悬浮窗按钮
        startFloatingButton.setOnClickListener(v -> {
            // 检查所有权限
            isRequestingPermissions = true;
            checkAndRequestAllPermissions();
        });
    }

    private void checkAndRequestAllPermissions() {
        if (!isRequestingPermissions) return;

        // 1. 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }

        // 2. 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission();
                return;
            }
        }

        // 3. 检查存储权限（Android 10及以下）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermissions();
                return;
            }
        }

        // 4. 检查文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccessPermission();
                return;
            }
        }

        // 5. 检查无障碍服务权限
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission();
            return;
        }

        // 6. 检查前台服务媒体投影权限（Android 14+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestForegroundServiceMediaProjectionPermission();
                return;
            }
        }

        // 7. 请求媒体投影权限
        requestMediaProjectionPermission();
    }

    // 请求悬浮窗权限.
    private void requestOverlayPermission() {
        Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_CODE_OVERLAY);
    }

    // 请求通知权限
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "请授予通知权限", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_NOTIFICATION);
        } else {
            // 继续检查下一个权限
            checkAndRequestAllPermissions();
        }
    }

    // 请求存储权限
    private void requestStoragePermissions() {
        Toast.makeText(this, "请授予存储权限", Toast.LENGTH_LONG).show();
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_CODE_PERMISSIONS);
    }

    // 请求所有文件访问权限
    private void requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "请授予文件访问权限", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivityForResult(intent, REQUEST_CODE_ALL_FILES);
            } catch (Exception e) {
                // 某些设备可能不支持这个Intent
                Toast.makeText(this, "无法请求文件访问权限，请手动授予", Toast.LENGTH_LONG).show();
                // 继续检查下一个权限
                checkAndRequestAllPermissions();
            }
        } else {
            // 继续检查下一个权限
            checkAndRequestAllPermissions();
        }
    }

    // 检查无障碍服务是否启用
    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + ScrollScreenshotAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(serviceName);
    }

    // 请求无障碍服务权限
    private void requestAccessibilityPermission() {
        Toast.makeText(this, "请在下一页启用长截图无障碍服务", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY);
    }

    // 请求前台服务媒体投影权限
    private void requestForegroundServiceMediaProjectionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(this, "请授予前台服务媒体投影权限", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION},
                    REQUEST_CODE_PERMISSIONS);
        } else {
            // 继续检查下一个权限
            checkAndRequestAllPermissions();
        }
    }

    // 请求媒体投影权限
    private void requestMediaProjectionPermission() {
        if (isMediaProjectionPermissionRequested) {
            Log.d(TAG, "已经请求过媒体投影权限，避免重复请求");
            isRequestingPermissions = false;
            return;
        }

        Toast.makeText(this, "请授予屏幕录制权限", Toast.LENGTH_LONG).show();
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        isMediaProjectionPermissionRequested = true;
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(),
                    REQUEST_CODE_MEDIA_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "请求媒体投影权限时出错: " + e.getMessage());
            isMediaProjectionPermissionRequested = false;
            isRequestingPermissions = false;
            Toast.makeText(this, "无法请求屏幕录制权限: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
//    // 启动悬浮窗服务
//    private void startFloatingWindowService(Intent mediaProjectionData, int resultCode) {
//        Intent intent = new Intent(this, ImprovedLongScreenshotService.class);
//
//        // 添加媒体投影结果数据
//        intent.putExtra("resultCode", resultCode);
//        intent.putExtra("resultData", mediaProjectionData);
//
//        // 计算ScrollView内容高度
//        View contentView = scrollView.getChildAt(0);
//        int contentHeight = contentView != null ? contentView.getHeight() : 0;
//        intent.putExtra("scrollViewHeight", contentHeight);
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            startForegroundService(intent);
//        } else {
//            startService(intent);
//        }
//
//        Toast.makeText(this, "长截屏悬浮窗正在启动", Toast.LENGTH_SHORT).show();
//
//        // 重置权限请求标志
//        isRequestingPermissions = false;
//    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "媒体投影权限已获取，准备启动服务");

                // 添加延迟，等待授权界面消失
                new Handler().postDelayed(() -> {
                    // 确保媒体投影数据被正确保存
                    Intent serviceIntent = new Intent(this, ImprovedLongScreenshotService.class);
                    serviceIntent.putExtra("resultCode", resultCode);
                    serviceIntent.putExtra("resultData", data);

                    // 启动截图服务
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }

                    Toast.makeText(this, "媒体投影权限已获取，长截屏服务启动中", Toast.LENGTH_SHORT).show();
                }, 5000); // 添加0.5秒延迟等待授权界面消失

                isMediaProjectionPermissionRequested = true;
            } else {
                Log.d(TAG, "用户拒绝了媒体投影权限");
                Toast.makeText(this, "需要屏幕录制权限才能进行截图", Toast.LENGTH_LONG).show();
                isMediaProjectionPermissionRequested = false;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    // 在 MainActivity 中添加以下方法
    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder("权限状态：\n");

        // 悬浮窗权限
        status.append("悬浮窗权限: ").append(Settings.canDrawOverlays(this) ? "已授予" : "未授予").append("\n");

        // 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.append("通知权限: ").append(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予").append("\n");
        }

        // 存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            status.append("存储权限: ").append(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予").append("\n");
        }

        // 文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            status.append("文件访问权限: ").append(Environment.isExternalStorageManager() ? "已授予" : "未授予").append("\n");
        }

        // 无障碍服务
        status.append("无障碍服务: ").append(isAccessibilityServiceEnabled() ? "已启用" : "未启用").append("\n");

        // 媒体投影权限状态
        status.append("媒体投影权限: ").append(isMediaProjectionPermissionRequested ? "已请求" : "未请求").append("\n");

        // 显示状态
        TextView statusView = findViewById(R.id.statusTextView);
        if (statusView != null) {
            statusView.setText(status.toString());
        } else {
            Log.d(TAG, "权限状态: " + status.toString());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            Log.d(TAG, "请求的权限已全部授予");
            // 继续检查下一个权限
            checkAndRequestAllPermissions();
        } else {
            Toast.makeText(this, "某些必要权限未授予，应用功能可能受限", Toast.LENGTH_LONG).show();
            isRequestingPermissions = false;
        }
    }

    // 提供一个静态方法让服务访问ScrollView
    public static ScrollView getScrollView() {
        return scrollView;
    }
}