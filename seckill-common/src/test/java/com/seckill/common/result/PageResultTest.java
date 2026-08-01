package com.seckill.common.result;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageResultTest {

    @Test
    void ofShouldBuildPageResult() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 100L, 1, 10);
        assertEquals(2, page.getList().size());
        assertEquals(100L, page.getTotal());
        assertEquals(1, page.getPageNum());
        assertEquals(10, page.getPageSize());
    }
}
