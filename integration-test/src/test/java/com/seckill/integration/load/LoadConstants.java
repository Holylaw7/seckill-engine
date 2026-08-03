package com.seckill.integration.load;

/**
 * 压测框架常量（冻结）。
 */
public final class LoadConstants {

    /** 压测总开关：-Dload.enabled=true 才执行压测用例 */
    public static final String LOAD_ENABLED = "load.enabled";

    /** 报告输出目录：-Dload.report.dir=... 覆盖，默认 target/load-reports */
    public static final String LOAD_REPORT_DIR = "load.report.dir";

    /** 长时间压测时长（分钟）：-Dload.duration-minutes=30 */
    public static final String LOAD_DURATION_MINUTES = "load.duration-minutes";

    private LoadConstants() {
    }
}
