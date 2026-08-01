package com.seckill.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String username;

    private String passwordHash;

    private String phone;

    private String nickname;

    /** 1 正常，0 禁用 */
    private Integer status;

    /** 角色（逗号分隔）：USER / USER,ADMIN */
    private String roles;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
