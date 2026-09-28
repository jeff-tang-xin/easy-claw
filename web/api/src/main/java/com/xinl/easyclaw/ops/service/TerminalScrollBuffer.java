package com.xinl.easyclaw.ops.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 单连接终端输出的环形缓冲（AI 的「眼睛」）。
 * <p>
 * openShell 的输出流在转发给浏览器的同时，字节副本喂进本类（按连接一个实例）。
 * 设计取舍：
 * <ul>
 *   <li><b>按字节容量截断，不按行</b>：保留最近 {@code capacityBytes} 字节，截断点可能
 *       落在多字节字符中间——读取时以 {@code UTF-8} 解码并替换坏字节，可接受
 *       （这是给 AI 的上下文，不是精确文件）；</li>
 *   <li><b>不去除 ANSI</b>：终端含颜色/光标控制序列，原样保留会干扰阅读，故读取时
 *       统一剥除 ANSI 转义序列；</li>
 *   <li><b>内存有界</b>：超出容量丢弃最旧字节，长连接也不会无限增长。</li>
 * </ul>
 * 线程模型：输出由 sshd 的 channel 线程写入，AI 工具线程读取——方法 synchronized。
 */
public class TerminalScrollBuffer {

    /** 默认容量：256KB（约可容纳上千行终端输出，给 AI 足够上下文又不撑爆） */
    public static final int DEFAULT_CAPACITY = 256 * 1024;

    private final int capacityBytes;
    /** 原始字节环形队列 */
    private final byte[] ring;
    private int start = 0;
    private int size = 0;

    public TerminalScrollBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public TerminalScrollBuffer(int capacityBytes) {
        this.capacityBytes = capacityBytes;
        this.ring = new byte[capacityBytes];
    }

    /** 追加一段输出字节；超长时覆盖最旧内容 */
    public synchronized void append(byte[] data, int off, int len) {
        for (int i = 0; i < len; i++) {
            int pos = (start + size) % capacityBytes;
            ring[pos] = data[off + i];
            if (size < capacityBytes) {
                size++;
            } else {
                // 已满：起点前移覆盖最旧
                start = (start + 1) % capacityBytes;
            }
        }
    }

    /** 缓冲全部内容（剥 ANSI 后的文本） */
    public synchronized String snapshot() {
        return stripAnsi(decode());
    }

    /**
     * 最近 N 行（按 \\n 切分，不足 N 行返回全部）。
     * 先取全部文本再切尾——N 通常很小（几十行），开销可接受。
     */
    public synchronized String tail(int maxLines) {
        String text = stripAnsi(decode());
        if (maxLines <= 0) {
            return text;
        }
        // 按行保留尾部；用 Deque 控制行数
        Deque<String> deque = new ArrayDeque<>();
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                deque.addLast(text.substring(lineStart, i));
                if (deque.size() > maxLines) {
                    deque.pollFirst();
                }
                lineStart = i + 1;
            }
        }
        if (lineStart < text.length()) {
            deque.addLast(text.substring(lineStart));
            if (deque.size() > maxLines) {
                deque.pollFirst();
            }
        }
        return String.join("\n", deque);
    }

    /** 环形字节 → 字符串（坏字节替换，不抛异常） */
    private String decode() {
        byte[] all = new byte[size];
        for (int i = 0; i < size; i++) {
            all[i] = ring[(start + i) % capacityBytes];
        }
        return new String(all, StandardCharsets.UTF_8);
    }

    /**
     * 剥除 ANSI 转义序列（CSI 控制序列、OSC、简单转义）。
     * 正则覆盖常见的颜色/光标/清屏序列；罕见序列残留无害。
     */
    private static String stripAnsi(String s) {
        if (s == null || s.indexOf('\u001b') < 0) {
            return s;
        }
        // CSI: ESC [ ... 终止于 0x40–0x7E；OSC: ESC ] ... BEL/ESC\；其他 ESC + 单字符
        return s.replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("\u001b][^\u0007\u001b]*(?:\u0007|\u001b\\\\)", "")
                .replaceAll("\u001b[@-Z\\\\-_^]", "")
                .replaceAll("[\u0000-\u0008\u000b\u000c\u000e-\u001f]", "");
    }
}
