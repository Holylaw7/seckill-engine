package com.seckill.integration.load;

/**
 * 压测动作：单次请求，返回是否成功。
 */
@FunctionalInterface
public interface LoadAction {

    /**
     * 执行一次请求。
     *
     * @param requestIndex 请求序号（0 起）
     * @return true 表示成功，false 表示业务失败
     * @throws Exception 抛出的异常按失败计数
     */
    boolean execute(int requestIndex) throws Exception;
}
