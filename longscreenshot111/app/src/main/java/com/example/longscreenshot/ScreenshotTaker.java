package com.example.longscreenshot;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import android.graphics.ImageFormat; // 添加这个导入
// 删除或注释掉 import android.graphics.PixelFormat; (如果不需要用于其他目的)

public class ScreenshotTaker {
    private static final String TAG = "ScreenshotTaker";
    private Context context;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private MediaProjection.Callback mediaProjectionCallback;
    private boolean projectionStopped = false;
    private final Object lock = new Object(); // 同步锁
    private AtomicBoolean isCapturing = new AtomicBoolean(false);
    private Handler mainHandler = new Handler(Looper.getMainLooper());



    public interface ScreenshotCallback {
        void onScreenshotTaken(Bitmap screenshot);
        void onError(String errorMessage);
    }

    public ScreenshotTaker(Context context) {
        this.context = context;
        init();
    }

    private void init() {
        // 获取屏幕尺寸和密度
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        // 创建回调
        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection stopped");
                projectionStopped = true;
                releaseVirtualDisplay();
            }
        };
    }


    // 设置媒体投影
    public void setMediaProjection(MediaProjection mediaProjection) {
        synchronized (lock) {
            // 如果已存在一个媒体投影，先释放它
            if (this.mediaProjection != null) {
                try {
                    this.mediaProjection.unregisterCallback(mediaProjectionCallback);
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering callback: " + e.getMessage());
                }
            }

            this.mediaProjection = mediaProjection;
            projectionStopped = false;

            // 注册回调
            if (this.mediaProjection != null) {
                try {
                    this.mediaProjection.registerCallback(mediaProjectionCallback, mainHandler);
                    Log.d(TAG, "MediaProjection set and callback registered");
                } catch (Exception e) {
                    Log.e(TAG, "Error registering callback: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 异步方式截取屏幕
     * @param callback 截图完成回调
     */
    public void takeScreenshotAsync(final ScreenshotCallback callback) {
        final int TIMEOUT_MS = 5000;

        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "Screenshot already in progress");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Screenshot already in progress"));
            }
            return;
        }

        synchronized (lock) {
            if (mediaProjection == null || projectionStopped) {
                Log.e(TAG, "MediaProjection is null or stopped");
                isCapturing.set(false);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("MediaProjection is null or stopped"));
                }
                return;
            }

            try {
                // 确保之前的ImageReader已释放，但不释放VirtualDisplay
                if (imageReader != null) {
                    try {
                        imageReader.setOnImageAvailableListener(null, null);
                        imageReader.close();
                    } catch (Exception e) {
                        Log.e(TAG, "Error closing previous ImageReader", e);
                    }
                }

                // 创建ImageReader
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

                final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
                    private boolean imageProcessed = false;

                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        if (imageProcessed) {
                            return; // 避免重复处理
                        }

                        Log.d(TAG, "onImageAvailable called");
                        Image image = null;
                        Bitmap bitmap = null;

                        try {
                            // 获取最新图像
                            image = reader.acquireLatestImage();

                            if (image == null) {
                                Log.e(TAG, "Acquired image is null");
                                // 尝试再次获取
                                image = reader.acquireNextImage();

                                if (image == null) {
                                    reportError(callback, "无法获取图像");
                                    return;
                                }
                            }

                            Log.d(TAG, "成功获取图像, 宽度: " + image.getWidth() + ", 高度: " + image.getHeight());

                            // 从这里开始处理图像
                            Image.Plane[] planes = image.getPlanes();
                            if (planes.length > 0) {
                                ByteBuffer buffer = planes[0].getBuffer();
                                int pixelStride = planes[0].getPixelStride();
                                int rowStride = planes[0].getRowStride();
                                int rowPadding = rowStride - pixelStride * image.getWidth();

                                // 创建位图
                                bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride,
                                        image.getHeight(), Bitmap.Config.ARGB_8888);
                                bitmap.copyPixelsFromBuffer(buffer);

                                // 裁剪到实际大小
                                if (rowPadding > 0) {
                                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
                                }

                                imageProcessed = true;
                                final Bitmap finalBitmap = bitmap;

                                // 返回位图
                                mainHandler.post(() -> {
                                    isCapturing.set(false);
                                    if (callback != null) {
                                        callback.onScreenshotTaken(finalBitmap);
                                    }
                                });
                            } else {
                                reportError(callback, "图像平面获取失败");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "处理图像时出错: " + e.getMessage(), e);
                            reportError(callback, "处理图像时出错: " + e.getMessage());
                        } finally {
                            // 释放资源
                            if (image != null) {
                                try {
                                    image.close();
                                } catch (Exception e) {
                                    Log.e(TAG, "关闭图像时出错", e);
                                }
                            }
                        }
                    }
                };

                // 设置图像可用监听器
                imageReader.setOnImageAvailableListener(onImageAvailableListener, mainHandler);

                Log.d(TAG, "Creating or reusing virtual display");

                // 检查是否需要创建新的VirtualDisplay
                if (virtualDisplay == null) {
                    // 创建虚拟显示以捕获屏幕内容 - 每次截图都使用新的名称
                    String displayName = "ScreenCapture_" + System.currentTimeMillis();
                    virtualDisplay = mediaProjection.createVirtualDisplay(
                            displayName,
                            screenWidth,
                            screenHeight,
                            screenDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader.getSurface(),
                            null,
                            mainHandler);

                    Log.d(TAG, "Virtual display created: " + displayName);
                } else {
                    // 重用现有VirtualDisplay，只更新Surface
                    virtualDisplay.setSurface(imageReader.getSurface());
                    Log.d(TAG, "Reused existing virtual display");
                }

                // 设置超时处理
                mainHandler.postDelayed(() -> {
                    synchronized (lock) {
                        if (isCapturing.getAndSet(false)) {
                            Log.e(TAG, "Screenshot timeout - no image received");
                            if (callback != null) {
                                mainHandler.post(() -> callback.onError("Screenshot timeout - no image received"));
                            }
                            // 不释放VirtualDisplay，只关闭ImageReader
                            if (imageReader != null) {
                                try {
                                    imageReader.setOnImageAvailableListener(null, null);
                                    imageReader.close();
                                    imageReader = null;
                                } catch (Exception e) {
                                    Log.e(TAG, "Error closing ImageReader", e);
                                }
                            }
                        }
                    }
                }, TIMEOUT_MS);

            } catch (Exception e) {
                Log.e(TAG, "Error setting up screenshot: " + e.getMessage(), e);
                isCapturing.set(false);

                if (callback != null) {
                    final String errorMsg = e.getMessage();
                    mainHandler.post(() -> callback.onError("Error setting up screenshot: " + errorMsg));
                }

                // 出现异常时，释放VirtualDisplay
                releaseVirtualDisplay();
            }
        }
    }

    private void reportError(ScreenshotCallback callback, String message) {
        Log.e(TAG, message);
        isCapturing.set(false);
        if (callback != null) {
            mainHandler.post(() -> callback.onError(message));
        }
        // 不在这里释放虚拟显示，让外部控制
    }

    public void releaseVirtualDisplay() {
        synchronized (lock) {
            // 先移除监听器
            if (imageReader != null) {
                try {
                    imageReader.setOnImageAvailableListener(null, null);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing listener", e);
                }
            }

            // 释放虚拟显示
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing virtual display", e);
                } finally {
                    virtualDisplay = null;
                }
                Log.d(TAG, "Virtual display released");
            }

            // 关闭ImageReader
            if (imageReader != null) {
                try {
                    imageReader.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing ImageReader", e);
                } finally {
                    imageReader = null;
                }
                Log.d(TAG, "ImageReader closed");
            }
        }
    }

    public void release() {
        synchronized (lock) {
            releaseVirtualDisplay();

            // 只取消注册回调，但不停止MediaProjection
            if (mediaProjection != null) {
                try {
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                    Log.d(TAG, "MediaProjection callback unregistered");
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering MediaProjection callback: " + e.getMessage());
                }
                // 不在这里停止mediaProjection - 由外部控制
            }
        }
    }

    // 完全释放，包括停止MediaProjection
    public void releaseCompletely() {
        synchronized (lock) {
            releaseVirtualDisplay();

            if (mediaProjection != null) {
                try {
                    mediaProjection.unregisterCallback(mediaProjectionCallback);
                    mediaProjection.stop();
                    Log.d(TAG, "MediaProjection stopped and unregistered");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping MediaProjection: " + e.getMessage());
                } finally {
                    mediaProjection = null;
                }
            }
            projectionStopped = true;
        }
    }

    public boolean isProjectionStopped() {
        return projectionStopped;
    }
}