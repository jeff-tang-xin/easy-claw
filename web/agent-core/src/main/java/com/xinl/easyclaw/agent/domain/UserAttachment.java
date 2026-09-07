package com.xinl.easyclaw.agent.domain;

/**
 * 用户消息附件（截图 / 文件上传）。
 *
 * @param name       文件名（如 paste.png / report.txt）
 * @param mimeType   MIME 类型（如 image/png、text/plain）
 * @param base64Data Base64 编码的文件内容（图片/文本均可）
 */
public record UserAttachment(String name, String mimeType, String base64Data) {
}
