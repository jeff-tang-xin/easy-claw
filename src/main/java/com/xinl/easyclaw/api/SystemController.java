package com.xinl.easyclaw.api;

import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 系统级 API：目录选择、环境探测等。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /**
     * 弹出系统原生目录选择器（JFileChooser）。
     * <p>
     * 必须在 AWT EDT (Event Dispatch Thread) 上调用。
     * 返回选中目录的绝对路径；用户取消或 headless 环境返回 null。
     */
    @PostMapping("/choose-dir")
    public String chooseDir(@RequestBody(required = false) ChooseDirRequest req) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }

        String startDir = (req != null && req.startDir() != null) ? req.startDir() : System.getProperty("user.home");
        if (startDir == null || startDir.isBlank()) {
            startDir = System.getProperty("user.home");
        }
        final String finalStartDir = startDir;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

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

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    public record ChooseDirRequest(String startDir) {}

    /**
     * 探测当前运行环境是否有 GUI（用于前端选择是否启用"浏览"按钮）。
     */
    @GetMapping("/has-gui")
    public boolean hasGui() {
        return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported();
    }
}
