package com.xinl.easyclaw.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 系统级 API：目录选择、环境探测等。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /** 目录选择器兜底超时：超过则放弃等待，释放请求线程（用户可能直接把弹窗晾在一边）。 */
    private static final long DIR_PICKER_TIMEOUT_MINUTES = 10;

    /**
     * 弹出系统原生目录选择器（JFileChooser）。
     * <p>
     * 必须在 AWT EDT (Event Dispatch Thread) 上调用。
     * 返回选中目录的绝对路径；用户取消或 headless 环境返回 200 with null body。
     */
    @PostMapping("/choose-dir")
    public ResponseEntity<String> chooseDir(@RequestBody(required = false) ChooseDirRequest req) {
        if (!guiAvailable()) {
            return ResponseEntity.ok().body(null);
        }

        String startDir = (req != null && req.startDir() != null) ? req.startDir() : System.getProperty("user.home");
        if (startDir == null || startDir.isBlank()) {
            startDir = System.getProperty("user.home");
        }
        final String finalStartDir = startDir;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setDialogTitle("选择项目目录");
                    chooser.setAcceptAllFileFilterUsed(false);
                    File start = new File(finalStartDir);
                    if (start.exists()) {
                        chooser.setCurrentDirectory(start);
                    }
                    int rc = chooser.showOpenDialog(null);
                    if (rc == JFileChooser.APPROVE_OPTION) {
                        result.set(chooser.getSelectedFile().getAbsolutePath());
                    }
                } catch (Exception e) {
                    // AWT 异常，忽略
                } finally {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            // 无图形界面的部署（Linux 无 X）下，首次触碰 AWT 会抛 AWTError/内部错误；
            // 此时 EDT 任务根本没排进去，latch 永远不会倒计时——直接降级返回，不能挂住请求线程。
            log.warn("[choose-dir] AWT 不可用，降级返回空: {}", t.toString());
            return ResponseEntity.ok().body(null);
        }

        try {
            // 兜底超时：正常情况下 invokeLater 的 finally 必定倒计时，这里只防 EDT 卡死导致
            // Tomcat 线程被永久占用（用户不点对话框 = 请求一直挂着）。
            if (!latch.await(DIR_PICKER_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("[choose-dir] 目录选择器超时未响应（{} 分钟），返回空。", DIR_PICKER_TIMEOUT_MINUTES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.ok().body(result.get());
    }

    public record ChooseDirRequest(String startDir) {}

    /**
     * 探测当前运行环境是否有 GUI（用于前端选择是否启用"浏览"按钮）。
     */
    @GetMapping("/has-gui")
    public boolean hasGui() {
        return guiAvailable();
    }

    /**
     * GUI 能力探测：headless 之外还要防 AWT toolkit 初始化失败——
     * {@code spring.main.headless=false} 后，无 X 的 Linux 部署里 {@code Desktop.isDesktopSupported()}
     * 会触发 toolkit 初始化并可能抛 {@code AWTError}，不能让它变成接口 500。
     */
    private static boolean guiAvailable() {
        try {
            return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported();
        } catch (Throwable t) {
            log.warn("[gui] AWT/Desktop 不可用: {}", t.toString());
            return false;
        }
    }
}
