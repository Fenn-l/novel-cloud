package io.github.xxyopen.novel.home.service.impl;

import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.home.dto.resp.HomeBookRespDto;
import io.github.xxyopen.novel.home.dto.resp.HomeFriendLinkRespDto;
import io.github.xxyopen.novel.home.manager.cache.FriendLinkCacheManager;
import io.github.xxyopen.novel.home.manager.cache.HomeBookCacheManager;
import io.github.xxyopen.novel.home.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 首页模块 服务实现类。
 *
 * <p>职责：作为前台首页与缓存层之间的编排层，把首页需要的「小说推荐位」和「友情链接」
 * 两块数据取出来，统一包装成 {@link RestResp} 返回。本类刻意不碰任何缓存细节：缓存的
 * 读写、空值是否入缓存、何时失效，全部下沉到对应的 CacheManager，Service 只认
 * 「调一个方法、拿一份数据、包一层响应」。
 *
 * <p>设计取舍：首页是读多写少、且允许短暂延迟的典型场景，因此推荐位走 Caffeine 本地
 * 缓存（24 小时）、友情链接走 Redis 缓存（不失效 + 手动清除），把绝大部分读流量挡在
 * 数据库之外。早期版本在这里做过「查到空列表就 evictCache()」的补救，那其实是个反直觉
 * 的错误：缓存是方法返回之后才写入的，清缓存只能让「下一次」请求重新回源，本次响应
 * 依旧是空；而且清缓存是全局副作用，会顺带抹掉其他并发请求刚加载好的数据。正确做法
 * 是在 {@link HomeBookCacheManager#listHomeBooks()} 上用 @Cacheable 的 unless 直接拦住
 * 空结果入缓存，让本次及后续请求都能重新回源。因此这里保持最薄的一层，不做补偿逻辑。
 *
 * @author xiongxiaoyang
 * @date 2022/5/13
 */
@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    private final HomeBookCacheManager homeBookCacheManager;

    private final FriendLinkCacheManager friendLinkCacheManager;

    /**
     * 查询首页小说推荐列表。
     *
     * <p>业务目的：首页按类型分组展示的轮播图、顶部栏、本周强推、热门推荐、精品推荐
     * 都来自这一份列表，由前端按 type 自行分组渲染。这里直接透传缓存管理器的结果，
     * 不再做「空则清缓存」这类补偿——空列表是否属于正常业务结果（例如后台还没配推荐）
     * 由缓存层的 unless 判断，Service 不替它下结论。
     *
     * <p>边界：允许返回空列表（首页只是少一块内容，不算系统错误），由前端兜底展示。
     *
     * @return 首页小说推荐列表的 rest 响应结果
     */
    @Override
    public RestResp<List<HomeBookRespDto>> listHomeBooks() {
        return RestResp.ok(homeBookCacheManager.listHomeBooks());
    }

    /**
     * 查询首页友情链接列表。
     *
     * <p>业务目的：页脚友链是运营在后台维护的准静态数据，变更频率极低，整个读取路径
     * 完全由 Redis 缓存承担，Service 不做任何加工，也不做任何「新鲜度」补偿。
     *
     * @return 友情链接列表
     */
    @Override
    public RestResp<List<HomeFriendLinkRespDto>> listHomeFriendLinks() {
        return RestResp.ok(friendLinkCacheManager.listFriendLinks());
    }
}
