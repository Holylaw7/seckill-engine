package com.seckill.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlacklistRequest {

    /** USER / IP / DEVICE */
    @NotBlank(message = "黑名单类型不能为空")
    private String bizType;

    @NotBlank(message = "黑名单值不能为空")
    private String bizValue;

    private String reason;

    /** 过期时间（毫秒时间戳），空为长期 */
    private Long expireAt;
}
