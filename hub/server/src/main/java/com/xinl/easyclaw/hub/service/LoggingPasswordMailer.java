package com.xinl.easyclaw.hub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DEV 投递实现：未配置 SMTP 前把临时密码打印到控制台（与 admin 初始密码同通道）。
 * TODO(A1/A5)：接入 SMTP 后替换为真实邮件投递。
 */
@Component
public class LoggingPasswordMailer implements PasswordMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordMailer.class);

    @Override
    public void sendInitialPassword(String toEmail, String username, String tempPassword) {
        String banner = "\n========== 【DEV】用户初始密码（未配置 SMTP，控制台投递） =========="
                + "\n  用户: " + username + " <" + toEmail + ">"
                + "\n  临时密码: " + tempPassword
                + "\n  （首次登录后必须修改）"
                + "\n=================================================================";
        System.out.println(banner);
        log.warn(banner);
    }
}
