package com.example.longscreenshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public class ScrollScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "ScrollScreenshotService";

    // 静态实例，用于从其他类访问服务
    private static ScrollScreenshotAccessibilityService instance;

    // 标记是否正在捕获截图
    private boolean isCapturing = false;

    // 主线程Handler
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 这里可以监听屏幕变化事件，但对于长截图我们主要使用显式调用方法
    }

    @Override
    public void onInterrupt() {
        // 服务中断时调用
        Log.d(TAG, "Accessibility service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility service connected");
        Toast.makeText(this, "长截图辅助服务已启动", Toast.LENGTH_SHORT).show();
    }

    /**
     * 获取服务实例
     */
    public static ScrollScreenshotAccessibilityService getInstance() {
        return instance;
    }

    /**
     * 执行更精确的滚动，避免内容断开
     */
    public boolean performPreciseScroll() {
        // 获取当前可滚动节点
        AccessibilityNodeInfo scrollable = findScrollableNode();
        if (scrollable == null) {
            return performGestureScroll(0.4f); // 回退到手势滚动，滚动70%的屏幕高度
        }

        // 获取节点在屏幕上的位置
        Rect rect = new Rect();
        scrollable.getBoundsInScreen(rect);

        // 先尝试使用标准API滚动
        boolean scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (scrolled) {
            Log.d(TAG, "使用标准API滚动成功");
            return true;
        }

        // 如果标准API失败，尝试更精确的手势滚动
        return performGestureScroll(0.4f);
    }

    /**
     * 根据提供的比例执行手势滚动
     *
     * @param scrollFactor 滚动屏幕的比例，范围0.0-1.0
     */
    public boolean performGestureScroll(float scrollFactor) {
        // 确保滚动因子在有效范围内
        scrollFactor = Math.max(0.1f, Math.min(0.1f, scrollFactor));

        // 获取屏幕尺寸
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenHeight = metrics.heightPixels;

        // 计算滚动距离
        float scrollDistance = 0.1f * (screenHeight * scrollFactor);

        // 创建滚动路径
        Path path = new Path();
        float startX = metrics.widthPixels / 2; // 屏幕中间
        float startY = screenHeight * 0.1f;     // 屏幕70%位置
        float endY = 0.5f * (startY - scrollDistance);   // 滚动到的位置

        path.moveTo(startX, startY);
        path.lineTo(startX, endY);

        // 创建手势
        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 使用更长的持续时间，确保滚动更平滑
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 400));

        // 执行手势
        final float finalScrollFactor = scrollFactor;
        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "精确滚动手势完成，滚动比例: " + finalScrollFactor);
            }
        }, null);
    }


    /**
     * 查找并返回可滚动节点
     */
    public AccessibilityNodeInfo findScrollableNode() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "Root node is null");
            return null;
        }

        // 广度优先搜索查找可滚动节点
        AccessibilityNodeInfo result = findScrollableNodeBFS(rootNode);

        if (result == null) {
            Log.d(TAG, "No scrollable node found");
        } else {
            Log.d(TAG, "Found scrollable node: " + result.getClassName());
        }

        return result;
    }

    private AccessibilityNodeInfo findScrollableNodeBFS(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // 检查当前节点是否可滚动
        if (root.isScrollable()) {
            return root;
        }

        // 广度优先搜索子节点
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                if (child.isScrollable()) {
                    return child;
                }
            }
        }

        // 递归搜索所有子节点
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo scrollable = findScrollableNodeBFS(child);
                if (scrollable != null) {
                    return scrollable;
                }
            }
        }

        return null;
    }

    /**
     * 执行向上滚动
     */
    public boolean scrollUp(AccessibilityNodeInfo node) {
        if (node == null) return false;

        boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        Log.d(TAG, "Scroll up result: " + result);
        return result;
    }

    /**
     * 执行向下滚动
     */
    public boolean scrollDown(AccessibilityNodeInfo node) {
        if (node == null) return false;

        boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        Log.d(TAG, "Scroll down result: " + result);
        return result;
    }

    /**
     * 使用手势滚动（更通用的方法）
     */
    public boolean performScroll() {
        AccessibilityNodeInfo scrollable = findScrollableNode();

        // 如果找到可滚动节点，则尝试使用它的边界进行滚动
        Rect rect = new Rect();
        if (scrollable != null) {
            scrollable.getBoundsInScreen(rect);
        } else {
            // 如果没有找到可滚动节点，使用屏幕中间区域
            rect.left = 0;
            rect.top = 0;
            rect.right = 1080; // 默认屏幕宽度
            rect.bottom = 2000; // 默认屏幕高度
        }

        // 创建滚动路径
        Path path = new Path();
        float startX = rect.centerX();
        float startY = rect.centerY() + 300; // 从中间靠下位置开始
        float endY = rect.centerY() - 300;   // 向上滚动到中间靠上位置

        path.moveTo(startX, startY);
        path.lineTo(startX, endY);

        // 创建手势
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));

        // 执行手势
        boolean result = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                Log.d(TAG, "Gesture completed");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                Log.d(TAG, "Gesture cancelled");
            }
        }, null);

        Log.d(TAG, "Perform scroll gesture result: " + result);
        return result;
    }

    /**
     * 检测当前是否已滚动到底部
     */
    public boolean isScrolledToBottom(AccessibilityNodeInfo node) {
        if (node == null) return true;

        // 检查是否可以继续向下滚动
        boolean canScrollForward = node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);

        Log.d(TAG, "Can scroll forward: " + canScrollForward);
        return !canScrollForward;
    }

    /**
     * 启动长截图过程
     */
    public void startLongScreenshot(LongScreenshotCallback callback) {
        if (isCapturing) {
            Log.w(TAG, "Screenshot already in progress");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("截图已在进行中"));
            }
            return;
        }

        isCapturing = true;

        // 查找可滚动元素
        AccessibilityNodeInfo scrollable = findScrollableNode();
        if (scrollable == null) {
            isCapturing = false;
            Log.e(TAG, "No scrollable content found");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("未找到可滚动内容"));
            }
            return;
        }

        // 通知可以开始截图
        if (callback != null) {
            Log.d(TAG, "Ready for capture with scrollable node");
            AccessibilityNodeInfo finalScrollable = scrollable;
            mainHandler.post(() -> callback.onReadyForCapture(finalScrollable));
        }
    }

    /**
     * 结束长截图过程
     */
    public void stopLongScreenshot() {
        Log.d(TAG, "Stopping long screenshot");
        isCapturing = false;
    }

    public interface LongScreenshotCallback {
        void onReadyForCapture(AccessibilityNodeInfo scrollableNode);

        void onError(String message);
    }
}