package com.seckill.auth.service;

import com.seckill.auth.dto.BlacklistRequest;
import com.seckill.auth.entity.RiskBlacklist;
import com.seckill.common.result.PageResult;

/**
 * 黑名单服务接口（管理事实源 MySQL + 查询热点 Redis）。
 */
public interface BlacklistService {

    void add(BlacklistRequest request, Long operatorId);

    void remove(String bizType, String bizValue);

    PageResult<RiskBlacklist> list(int pageNum, int pageSize);
}
