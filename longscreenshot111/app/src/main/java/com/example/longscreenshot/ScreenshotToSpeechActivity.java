package com.example.longscreenshot;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ScreenshotToSpeechActivity extends AppCompatActivity {

    private static final String TAG = "ScreenshotToSpeechActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private static final int REQUEST_CODE_OVERLAY = 102;
    private static final int REQUEST_CODE_ALL_FILES = 104;
    private static final int REQUEST_CODE_ACCESSIBILITY = 105;
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 106;
    private static final int REQUEST_CODE_NOTIFICATION = 107;

    // 服务器地址
    private static final String SERVER_URL = "YOUR_SERVER_URL_HERE";

    // UI 组件
    private TextView errorMessageView;
    private TextView serverStatusView;
    private TextView permissionStatusView;
    private TextView screenshotPathView;
    private TextView progressTextView;
    private TextView transcriptionTextView;
    private ProgressBar progressBar;
    private Button startScreenshotButton;
    private Button uploadButton;
    private Button playAudioButton;

    // 媒体投影相关
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private int resultCode;
    private Intent resultData;

    // 长截图服务
    private ImprovedLongScreenshotService screenshotService;
    private boolean isServiceBound = false;
    private String screenshotPath;

    // 网络请求客户端
    private OkHttpClient client;
    private ExecutorService executorService;
    private Handler mainHandler;

    // 音频播放器
    private MediaPlayer mediaPlayer;
    private boolean audioReceived = false;
    private File audioFile;

    private static final int REQUEST_CODE_PICK_IMAGE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_screenshot_to_speech);

        // 初始化UI组件
        initViews();

        // 初始化字体
        initFonts();

        // 初始化网络组件
        client = new OkHttpClient();
        executorService = Executors.newFixedThreadPool(2);
        mainHandler = new Handler(Looper.getMainLooper());

        // 初始化投影服务
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 检查权限
        checkAndRequestPermissions();

        // 测试服务器连接
        testServerConnection();

        // 设置按钮事件监听器
        setupButtonListeners();
    }

    /**
     * 打开文件选择器选择图片
     */
    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");

        // 设置可选的MIME类型
        String[] mimeTypes = {"image/jpeg", "image/png", "image/jpg"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);

        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
        } catch (Exception e) {
            Log.e(TAG, "无法打开文件选择器: " + e.getMessage());
            showError("无法打开文件选择器: " + e.getMessage());

            // 尝试使用备用方法
            try {
                Intent fallbackIntent = new Intent(Intent.ACTION_GET_CONTENT);
                fallbackIntent.setType("image/*");
                startActivityForResult(fallbackIntent, REQUEST_CODE_PICK_IMAGE);
            } catch (Exception ex) {
                showError("无法打开文件选择器，请确保您的设备支持此功能");
            }
        }
    }

    private void initViews() {
        errorMessageView = findViewById(R.id.error_message);
        serverStatusView = findViewById(R.id.server_status);
        permissionStatusView = findViewById(R.id.permission_status);
        screenshotPathView = findViewById(R.id.screenshot_path);
        progressTextView = findViewById(R.id.progress_text);
        transcriptionTextView = findViewById(R.id.transcription_text);
        progressBar = findViewById(R.id.progress_bar);
        startScreenshotButton = findViewById(R.id.btn_start_screenshot);
        uploadButton = findViewById(R.id.btn_upload);
        playAudioButton = findViewById(R.id.btn_play_audio);

        // 默认禁用上传和播放按钮
        uploadButton.setEnabled(true);
        playAudioButton.setEnabled(false);
    }

    private void initFonts() {
        try {
            Typeface customFont = Typeface.createFromAsset(getAssets(), "fonts/msyh.ttc");

            // 应用字体到所有TextView
            errorMessageView.setTypeface(customFont);
            serverStatusView.setTypeface(customFont);
            permissionStatusView.setTypeface(customFont);
            screenshotPathView.setTypeface(customFont);
            progressTextView.setTypeface(customFont);
            transcriptionTextView.setTypeface(customFont);

            // 应用字体到按钮
            startScreenshotButton.setTypeface(customFont);
            uploadButton.setTypeface(customFont);
            playAudioButton.setTypeface(customFont);

            // 设置标题字体
            ((TextView)findViewById(R.id.title_text)).setTypeface(customFont, Typeface.BOLD);

        } catch (Exception e) {
            Log.e(TAG, "Error loading font: " + e.getMessage());
        }
    }

    private void setupButtonListeners() {
        // 开始长截图按钮
        startScreenshotButton.setOnClickListener(v -> {

            if (checkAndRequestAllPermissions()) {
                requestMediaProjectionPermission();
            }
        });

        // 选择并上传到服务器按钮
        uploadButton.setOnClickListener(v -> {
            // 打开文件选择器
            openImagePicker();
        });

        // 播放语音按钮
        playAudioButton.setOnClickListener(v -> {
            playAudio();
        });
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // 检查存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // 请求权限
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        }

        updatePermissionStatus();
    }

    private boolean checkAndRequestAllPermissions() {
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return false;
        }

        // 检查文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccessPermission();
                return false;
            }
        }

        // 检查无障碍服务权限
        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission();
            return false;
        }

        // 检查前台服务媒体投影权限（Android 14+）
        if (Build.VERSION.SDK_INT >= 34) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) != PackageManager.PERMISSION_GRANTED) {
                requestForegroundServiceMediaProjectionPermission();
                return false;
            }
        }

        updatePermissionStatus();
        return true;
    }

    private void testServerConnection() {
        serverStatusView.setText("正在连接服务器...");

        executorService.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(SERVER_URL)
                        .post(RequestBody.create(new byte[0], null))
                        .build();

                Response response = client.newCall(request).execute();
                int statusCode = response.code();

                mainHandler.post(() -> {
                    if (statusCode == 200 || statusCode == 201) {
                        serverStatusView.setText("服务器连接正常 ✓");
                        serverStatusView.setTextColor(ContextCompat.getColor(this, R.color.teal_700));
                    } else {
                        serverStatusView.setText("服务器连接异常: " + statusCode);
                        serverStatusView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                    }
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    serverStatusView.setText("服务器连接失败: " + e.getMessage());
                    serverStatusView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                });
            }
        });
    }

    private void updatePermissionStatus() {
        StringBuilder status = new StringBuilder();

        // 悬浮窗权限
        status.append("悬浮窗权限: ").append(Settings.canDrawOverlays(this) ? "已授予 ✓" : "未授予 ✗").append("\n");

        // 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status.append("通知权限: ").append(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            == PackageManager.PERMISSION_GRANTED ? "已授予 ✓" : "未授予 ✗").append("\n");
        }

        // 存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            status.append("存储权限: ").append(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED ? "已授予 ✓" : "未授予 ✗").append("\n");
        }

        // 文件访问权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            status.append("文件访问权限: ").append(Environment.isExternalStorageManager() ? "已授予 ✓" : "未授予 ✗").append("\n");
        }

        // 无障碍服务
        status.append("无障碍服务: ").append(isAccessibilityServiceEnabled() ? "已启用 ✓" : "未启用 ✗");

        permissionStatusView.setText(status.toString());
    }

    // 请求悬浮窗权限
    private void requestOverlayPermission() {
        Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_CODE_OVERLAY);
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
                Toast.makeText(this, "无法请求文件访问权限，请手动授予", Toast.LENGTH_LONG).show();
            }
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
        if (Build.VERSION.SDK_INT >= 34) {
            Toast.makeText(this, "请授予前台服务媒体投影权限", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION},
                    REQUEST_CODE_PERMISSIONS);
        }
    }

    // 在ScreenshotToSpeechActivity的requestMediaProjectionPermission方法中
    private void requestMediaProjectionPermission() {
        Toast.makeText(this, "请授予屏幕录制权限", Toast.LENGTH_LONG).show();
        try {
            Log.d(TAG, "请求MediaProjection权限");
            startActivityForResult(projectionManager.createScreenCaptureIntent(),
                    REQUEST_CODE_MEDIA_PROJECTION);
        } catch (Exception e) {
            Log.e(TAG, "请求媒体投影权限时出错: " + e.getMessage());
            showError("无法请求屏幕录制权限: " + e.getMessage());
        }
    }
    private void startScreenshotService() {
        Intent serviceIntent = new Intent(this, ImprovedLongScreenshotService.class);
        serviceIntent.putExtra("resultCode", resultCode);
        serviceIntent.putExtra("resultData", resultData);

        // 启动截图服务前添加日志
        Log.d(TAG, "准备启动长截图服务, resultCode: " + resultCode);

        // 启动截图服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(serviceIntent);
                Log.d(TAG, "前台服务启动成功");
            } catch (Exception e) {
                Log.e(TAG, "启动前台服务失败: " + e.getMessage());
                showError("启动服务失败: " + e.getMessage());
                return;
            }
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "长截图服务启动中，请点击悬浮窗按钮开始截图", Toast.LENGTH_LONG).show();

        // 添加短暂延迟后检查服务是否正在运行
        new Handler().postDelayed(() -> {
            // 这里可以添加检查服务是否正在运行的代码
            // 例如通过ActivityManager查询服务状态
        }, 1000);

        // 注册截图完成的广播接收器
        registerScreenshotReceiver();
    }

    private void registerScreenshotReceiver() {
        // 实现方式1：使用BroadcastReceiver
        // 但由于时间限制，我们简化为轮询检查截图目录

        // 每5秒检查一次截图目录
        final Handler handler = new Handler();
        final Runnable checkScreenshot = new Runnable() {
            @Override
            public void run() {
                // 检查最新的截图
                File screenshotsDir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "Screenshots");

                if (screenshotsDir.exists() && screenshotsDir.isDirectory()) {
                    File[] files = screenshotsDir.listFiles(pathname ->
                            pathname.isFile() && pathname.getName().startsWith("智能长截图_"));

                    if (files != null && files.length > 0) {
                        // 按修改时间排序，获取最新的截图
                        File latestFile = files[0];
                        for (File file : files) {
                            if (file.lastModified() > latestFile.lastModified()) {
                                latestFile = file;
                            }
                        }

                        // 如果找到新截图
                        if (latestFile != null && !latestFile.getAbsolutePath().equals(screenshotPath)) {
                            screenshotPath = latestFile.getAbsolutePath();
                            mainHandler.post(() -> {
                                screenshotPathView.setText("截图已保存: " + screenshotPath);
                                uploadButton.setEnabled(true);
                            });

                            // 截图完成，停止检查
                            handler.removeCallbacks(this);
                            return;
                        }
                    }
                }

                // 继续检查
                handler.postDelayed(this, 5000);
            }
        };

        // 开始检查
        handler.postDelayed(checkScreenshot, 5000);
    }


    private void uploadImage(String imagePath) {
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            showError("图片文件不存在: " + imagePath);
            return;
        }

        if (!imageFile.canRead()) {
            showError("无法读取图片文件，请检查权限");
            return;
        }

        // 检查文件大小
        long fileSize = imageFile.length();
        if (fileSize > 20 * 1024 * 1024) { // 超过20MB
            showError("图片文件过大 (" + (fileSize / 1024 / 1024) + "MB)，请选择较小的图片");
            return;
        }

        // 更新UI
        progressBar.setProgress(0);
        progressTextView.setText("0%");
        uploadButton.setEnabled(false);
        updateProgressUI(10, "准备上传...");

        // 创建请求体
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "images",
                        imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.parse("image/png"))
                )
                .build();

        // 创建请求
        Request request = new Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .build();

        // 发送请求
        updateProgressUI(20, "正在上传...");
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "上传失败: " + e.getMessage());
                mainHandler.post(() -> {
                    showError("上传失败: " + e.getMessage());
                    uploadButton.setEnabled(true);
                    updateProgressUI(0, "上传失败");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                updateProgressUI(70, "服务器处理中...");

                if (!response.isSuccessful()) {
                    mainHandler.post(() -> {
                        showError("服务器错误: " + response.code());
                        uploadButton.setEnabled(true);
                        updateProgressUI(0, "服务器错误");
                    });
                    return;
                }

                String contentType = response.header("Content-Type", "");

                if (contentType.contains("application/json")) {
                    // 处理JSON响应
                    try {
                        String responseBody = response.body().string();
                        JSONObject jsonResponse = new JSONObject(responseBody);
                        final String text = jsonResponse.optString("text", "未获取到文本");

                        mainHandler.post(() -> {
                            transcriptionTextView.setText(text);
                            uploadButton.setEnabled(true);
                            updateProgressUI(100, "文本处理完成");
                        });
                    } catch (JSONException e) {
                        mainHandler.post(() -> {
                            showError("JSON解析错误: " + e.getMessage());
                            uploadButton.setEnabled(true);
                            updateProgressUI(0, "解析错误");
                        });
                    }
                } else if (contentType.contains("audio/wav") || contentType.contains("audio/x-wav")) {
                    // 处理音频响应
                    handleAudioResponse(response);
                } else {
                    mainHandler.post(() -> {
                        showError("服务器返回未知格式: " + contentType);
                        uploadButton.setEnabled(true);
                        updateProgressUI(0, "未知格式");
                    });
                }
            }
        });
    }

    private void handleAudioResponse(Response response) {
        try {
            // 保存音频文件
            audioFile = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "output.wav");

            try (InputStream inputStream = response.body().byteStream();
                 OutputStream outputStream = new FileOutputStream(audioFile)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = 0;
                long contentLength = response.body().contentLength();

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    if (contentLength > 0) {
                        final int progress = (int) (70 + (30 * totalBytes / contentLength));
                        mainHandler.post(() -> updateProgressUI(progress, "下载音频数据..."));
                    }
                }
            }

            audioReceived = true;

            mainHandler.post(() -> {
                updateProgressUI(100, "音频接收完成");
                playAudioButton.setEnabled(true);
                uploadButton.setEnabled(true);
                Toast.makeText(ScreenshotToSpeechActivity.this, "音频接收完成！", Toast.LENGTH_SHORT).show();
            });

        } catch (IOException e) {
            Log.e(TAG, "处理音频响应失败: " + e.getMessage());
            mainHandler.post(() -> {
                showError("处理音频响应失败: " + e.getMessage());
                uploadButton.setEnabled(true);
                updateProgressUI(0, "音频处理失败");
            });
        }
    }

    private void playAudio() {
        if (!audioReceived || audioFile == null || !audioFile.exists()) {
            showError("没有接收到音频，无法播放！");
            return;
        }

        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(new FileInputStream(audioFile).getFD());
            mediaPlayer.prepare();
            mediaPlayer.start();

            Toast.makeText(this, "正在播放音频...", Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            showError("播放音频失败: " + e.getMessage());
        }
    }

    private void updateProgressUI(int progress, String message) {
        progressBar.setProgress(progress);
        progressTextView.setText(progress + "% - " + message);
    }

    private void showError(String message) {
        errorMessageView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "媒体投影权限已获取，准备启动服务");

                // 保存结果
                this.resultCode = resultCode;
                this.resultData = data;

                // 降低延迟时间，并在延迟后启动服务
                new Handler().postDelayed(() -> {
                    // 重新检查权限数据
                    if (this.resultData != null) {
                        startScreenshotService();
                    } else {
                        showError("媒体投影数据丢失，请重试");
                    }
                }, 1000); // 只延迟1秒
            } else {
                showError("需要屏幕录制权限才能进行截图");
            }
        } else if (requestCode == REQUEST_CODE_PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri selectedImageUri = data.getData();
                if (selectedImageUri != null) {
                    // 处理选中的图片URI
                    handleSelectedImage(selectedImageUri);
                } else {
                    showError("未能获取所选图片");
                }
            }
        } else if (requestCode == REQUEST_CODE_OVERLAY ||
                requestCode == REQUEST_CODE_ALL_FILES ||
                requestCode == REQUEST_CODE_ACCESSIBILITY) {
            // 更新权限状态
            updatePermissionStatus();
        }
    }

    /**
     * 处理用户选中的图片
     * @param imageUri 选中图片的URI
     */
    private void handleSelectedImage(Uri imageUri) {
        try {
            // 获取文件路径
            String imagePath = getPathFromUri(imageUri);

            if (imagePath != null) {
                // 更新UI显示选中的图片路径
                screenshotPath = imagePath;
                screenshotPathView.setText("已选择图片: " + imagePath);

                // 上传选中的图片
                uploadImage(imagePath);
            } else {
                // 如果无法获取路径，直接使用URI上传
                uploadImageFromUri(imageUri);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理所选图片时出错: " + e.getMessage());
            showError("处理所选图片时出错: " + e.getMessage());
        }
    }

    /**
     * 从Uri获取文件路径
     * @param uri 文件Uri
     * @return 文件路径，如果无法获取则返回null
     */
    private String getPathFromUri(Uri uri) {
        try {
            if ("content".equals(uri.getScheme())) {
                // 处理content类型的URI
                String[] projection = {MediaStore.Images.Media.DATA};
                Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                    String path = cursor.getString(columnIndex);
                    cursor.close();
                    return path;
                }
                return null;
            } else if ("file".equals(uri.getScheme())) {
                // 处理file类型的URI
                return uri.getPath();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "获取文件路径出错: " + e.getMessage());
            return null;
        }
    }

    /**
     * 直接从Uri上传图片（用于无法获取文件路径的情况）
     * @param imageUri 图片URI
     */
    private void uploadImageFromUri(Uri imageUri) {
        try {
            // 更新UI
            progressBar.setProgress(0);
            progressTextView.setText("0%");
            uploadButton.setEnabled(false);
            updateProgressUI(10, "准备上传...");

            // 创建临时文件
            File tempFile = File.createTempFile("upload_image", ".jpg", getCacheDir());

            // 复制URI内容到临时文件
            try (InputStream inputStream = getContentResolver().openInputStream(imageUri);
                 OutputStream outputStream = new FileOutputStream(tempFile)) {

                if (inputStream == null) {
                    showError("无法读取所选图片");
                    uploadButton.setEnabled(true);
                    return;
                }

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // 更新UI显示临时文件路径
            screenshotPath = tempFile.getAbsolutePath();
            screenshotPathView.setText("已选择图片 (临时文件): " + screenshotPath);

            // 上传临时文件
            uploadImage(tempFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "从URI上传图片失败: " + e.getMessage());
            showError("从URI上传图片失败: " + e.getMessage());
            uploadButton.setEnabled(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "部分权限未授予，功能可能受限", Toast.LENGTH_LONG).show();
            }

            updatePermissionStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 释放资源
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        executorService.shutdown();
    }
}