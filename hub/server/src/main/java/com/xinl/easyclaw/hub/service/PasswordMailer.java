package com.xinl.easyclaw.hub.service;
import com.xinl.easyclaw.hub.config.AdminBootstrap;

/**
 * 初始/临时密码投递通道。A0 未接 SMTP，默认实现打印控制台（{@link LoggingPasswordMailer}）；
 * 接入邮件服务后以新实现替换即可，调用方（AuthService / AdminBootstrap）不感知。
 */
public interface PasswordMailer {

    void sendInitialPassword(String toEmail, String username, String tempPassword);
}
