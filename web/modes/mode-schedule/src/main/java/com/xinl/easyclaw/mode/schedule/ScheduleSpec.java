package com.xinl.easyclaw.mode.schedule;

/**
 * 定时触发声明——描述 {@code schedule} 模式的触发规则。
 * <p>
 * 这是一个<b>配置声明</b>，不含调度实现。本 record 只承载「什么时候触发、
 * 在哪个时区、是否启用、执行什么任务」这四要素，真正的 {@code @Scheduled} /
 * {@code TaskScheduler} 注册在 api 层完成，本模块保持零 Spring 依赖。
 * <p>
 * 使用时请通过 {@link #of(String, String)} 快速构造，或使用全参数构造器。
 * <p>
 * 使用示例：
 * <pre>{@code
 * ScheduleSpec spec = ScheduleSpec.of("0 0 9 * * ?", "每日九点汇总日报");
 * // cron = "0 0 9 * * ?", timezone = "Asia/Shanghai", enabled = true, task = "每日九点汇总日报"
 * }</pre>
 *
 * @param cron     定时表达式（cron 格式，不可为空/空白）
 * @param timezone 时区，默认 "Asia/Shanghai"
 * @param enabled  是否启用，默认 true
 * @param task     定时执行的任务指令
 */
public record ScheduleSpec(String cron, String timezone, boolean enabled, String task) {

    /**
     * 紧凑构造器。
     *
     * @throws IllegalArgumentException cron 为 null 或空白时抛出
     */
    public ScheduleSpec {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("ScheduleSpec.cron 不能为空");
        }
        if (timezone == null) {
            timezone = "Asia/Shanghai";
        }
    }

    /**
     * 快速构造一个启用的定时任务。
     *
     * @param cron cron 表达式
     * @param task 任务指令
     * @return enabled=true、timezone="Asia/Shanghai" 的 ScheduleSpec
     * @throws IllegalArgumentException cron 为 null 或空白时抛出
     */
    public static ScheduleSpec of(String cron, String task) {
        return new ScheduleSpec(cron, "Asia/Shanghai", true, task);
    }
}