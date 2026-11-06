package io.github.xxyopen.novel.home.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.home.dao.entity.HomeFriendLink;
import io.github.xxyopen.novel.home.dao.mapper.HomeFriendLinkMapper;
import io.github.xxyopen.novel.home.dto.resp.HomeFriendLinkRespDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 友情链接 缓存管理类。
 *
 * <p>职责：缓存首页页脚的友情链接。友链由运营在后台维护，读多写极少，因此整表按
 * sort 排序后整体缓存，不分页、不按单条 key 拆分。
 *
 * <p>设计取舍：该缓存用 Redis 且 TTL 配成 0（永不失效），好处是不必为几乎不变的数据
 * 反复回源；但「永不失效」必须有配套的失效入口，否则后台新增/修改/删除友链后，前台会
 * 一直读旧数据、且没有任何办法刷新，属于典型的「缓存与数据源永久不一致」。因此本类额外
 * 提供 {@link #evictFriendLinkCache()}，约定后台改动友链时主动调用它清缓存。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class FriendLinkCacheManager {

    private final HomeFriendLinkMapper friendLinkMapper;

    /**
     * 查询友情链接列表并放入缓存。
     *
     * <p>业务目的：按 sort 升序返回全部友链，只对外暴露名称与跳转地址，不把表里的 id、
     * 排序值等内部字段带给前端。缓存未命中时才会真正查库。
     *
     * @return 友情链接列表；表中无数据时为空列表
     */
    @Cacheable(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.HOME_FRIEND_LINK_CACHE_NAME)
    public List<HomeFriendLinkRespDto> listFriendLinks() {
        // 从友情链接表中查询出友情链接列表
        QueryWrapper<HomeFriendLink> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc(DatabaseConsts.CommonColumnEnum.SORT.getName());
        return friendLinkMapper.selectList(queryWrapper).stream().map(v -> {
            HomeFriendLinkRespDto respDto = new HomeFriendLinkRespDto();
            respDto.setLinkName(v.getLinkName());
            respDto.setLinkUrl(v.getLinkUrl());
            return respDto;
        }).toList();
    }

    /**
     * 清空友情链接缓存（后台增删改友链后调用）。
     *
     * <p>为什么用 allEntries = true：列表类缓存是「整表一个 value、没有入参 key」，
     * 无法按单条记录精确淘汰，只能整体清空，让下一次查询重新加载全表。方法体为空即可，
     * 清除动作由 @CacheEvict 的 AOP 完成。这是该「永不失效」缓存唯一的刷新入口。
     */
    @CacheEvict(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.HOME_FRIEND_LINK_CACHE_NAME, allEntries = true)
    public void evictFriendLinkCache() {

    }

}
