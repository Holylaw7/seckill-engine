package com.seckill.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码（冻结）。
 *
 * <p>分段：10xxx 系统通用，20xxx 认证，30xxx 秒杀业务，40xxx 订单业务，50xxx 支付业务。
 * 禁止业务模块自行创建重复错误码。</p>
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "success"),

    // ========== 10xxx 系统通用 ==========
    SYSTEM_ERROR(10000, "系统内部错误"),
    PARAM_ERROR(10001, "参数错误"),
    RESOURCE_NOT_FOUND(10002, "资源不存在"),
    RATE_LIMITED(10003, "请求过于频繁，请稍后重试"),
    SERVICE_BUSY(10004, "系统繁忙，请稍后重试"),
    JSON_ERROR(10005, "JSON 处理失败"),

    // ========== 20xxx 认证 ==========
    UNAUTHORIZED(20001, "未登录或登录已过期"),
    FORBIDDEN(20002, "无权限"),
    RISK_REJECTED(20003, "操作被风控拦截"),
    CAPTCHA_REQUIRED(20004, "需要人机验证"),
    BLACKLISTED(20005, "账号已被限制"),

    // ========== 30xxx 秒杀业务 ==========
    SESSION_NOT_STARTED(30001, "秒杀未开始"),
    SESSION_ENDED(30002, "秒杀已结束"),
    SESSION_NOT_READY(30003, "场次未就绪"),
    STOCK_EMPTY(30004, "库存不足"),
    REPEAT_BUY(30005, "请勿重复抢购"),
    SECKILL_BUSY(30006, "系统繁忙，请稍后重试"),
    INVENTORY_ERROR(30007, "库存数据异常，请联系客服"),

    // ========== 40xxx 订单业务 ==========
    ORDER_NOT_FOUND(40001, "订单不存在"),
    ORDER_STATUS_INVALID(40002, "订单状态不允许该操作"),
    ORDER_TIMEOUT(40003, "订单已超时"),

    // ========== 50xxx 支付业务 ==========
    PAYMENT_NOT_FOUND(50001, "支付单不存在"),
    PAYMENT_SIGN_INVALID(50002, "支付回调验签失败"),
    PAYMENT_DUPLICATE_CALLBACK(50003, "支付重复回调"),
    REFUND_FAILED(50004, "退款失败");

    private final int code;
    private final String message;
}
