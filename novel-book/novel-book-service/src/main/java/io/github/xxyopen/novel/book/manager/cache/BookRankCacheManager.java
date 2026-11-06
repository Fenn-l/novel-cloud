package io.github.xxyopen.novel.book.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.book.dao.entity.BookInfo;
import io.github.xxyopen.novel.book.dao.mapper.BookInfoMapper;
import io.github.xxyopen.novel.book.dto.resp.BookRankRespDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小说排行榜 缓存管理类：点击榜 / 新书榜 / 更新榜三个榜单。
 *
 * <p>【三个榜各自的分级取舍，面试常问】
 * - 点击榜（visit_count 倒序）走 Redis 远程缓存（ttl 6h）：点击量是全站累计、随时在变，
 *   多实例必须共享同一份结果，否则各实例按自己的本地缓存算出来的名次会互相打架。
 * - 新书榜（create_time 倒序）与更新榜（update_time 倒序）走 Caffeine 本地缓存
 *   （ttl 分别 30min / 60s）：这两个榜单时效性极强、过期时间本来就短，
 *   用本地缓存省掉 Redis 网络往返，短暂的实例间不一致在几秒到几分钟的尺度上无所谓。
 * 这正是 CacheConsts 里「远程 vs 本地」按数据特性分流的典型例子。
 *
 * <p>【公共查询的边界】三个 @Cacheable 方法都复用私有方法 listRankBooks 做查询与 DTO 转换。
 * 它是 private 的、且只被本类已代理的入口方法调用，属于普通方法调用，不涉及 Spring 缓存代理，
 * 因此不会像 BookInfoCacheManager 那样踩 self-invocation 的坑；公共逻辑放这里纯粹是去重。
 *
 * <p>【过滤与截断】统一过滤 word_count &gt; 0，排除还没发布任何章节的「空壳书」，
 * 避免榜单出现点进去没内容的书；再 LIMIT 30 只取前 30 条，控制响应体大小与查询成本。
 * 榜单是读多写少场景，没有主动失效入口，靠各自的 TTL 自然过期换新鲜度。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookRankCacheManager {

    private final BookInfoMapper bookInfoMapper;

    /**
     * 点击榜：按累计点击量倒序，走 Redis 共享缓存。
     */
    @Cacheable(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.BOOK_VISIT_RANK_CACHE_NAME)
    public List<BookRankRespDto> listVisitRankBooks() {
        QueryWrapper<BookInfo> bookInfoQueryWrapper = new QueryWrapper<>();
        bookInfoQueryWrapper.orderByDesc(DatabaseConsts.BookTable.COLUMN_VISIT_COUNT);
        return listRankBooks(bookInfoQueryWrapper);
    }

    /**
     * 新书榜：按创建时间倒序，走本地缓存、短 TTL 保证新书能较快上榜。
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_NEWEST_RANK_CACHE_NAME)
    public List<BookRankRespDto> listNewestRankBooks() {
        QueryWrapper<BookInfo> bookInfoQueryWrapper = new QueryWrapper<>();
        bookInfoQueryWrapper
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .orderByDesc(DatabaseConsts.CommonColumnEnum.CREATE_TIME.getName());
        return listRankBooks(bookInfoQueryWrapper);
    }

    /**
     * 更新榜：按最后更新时间倒序，TTL 最短（60s），读者对「刚更新」最敏感。
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_UPDATE_RANK_CACHE_NAME)
    public List<BookRankRespDto> listUpdateRankBooks() {
        QueryWrapper<BookInfo> bookInfoQueryWrapper = new QueryWrapper<>();
        bookInfoQueryWrapper
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .orderByDesc(DatabaseConsts.CommonColumnEnum.UPDATE_TIME.getName());
        return listRankBooks(bookInfoQueryWrapper);
    }

    /**
     * 公共查询：补上「过滤空壳书 + 截断 30 条」，再把实体转成精简的榜单 DTO。
     * 只挑列表页要展示的字段，不返回整本书对象，减少序列化与传输开销。
     */
    private List<BookRankRespDto> listRankBooks(QueryWrapper<BookInfo> bookInfoQueryWrapper) {
        bookInfoQueryWrapper
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .last(DatabaseConsts.SqlEnum.LIMIT_30.getSql());
        return bookInfoMapper.selectList(bookInfoQueryWrapper).stream().map(v -> {
            BookRankRespDto respDto = new BookRankRespDto();
            respDto.setId(v.getId());
            respDto.setCategoryId(v.getCategoryId());
            respDto.setCategoryName(v.getCategoryName());
            respDto.setBookName(v.getBookName());
            respDto.setAuthorName(v.getAuthorName());
            respDto.setPicUrl(v.getPicUrl());
            respDto.setBookDesc(v.getBookDesc());
            respDto.setLastChapterName(v.getLastChapterName());
            respDto.setLastChapterUpdateTime(v.getLastChapterUpdateTime());
            respDto.setWordCount(v.getWordCount());
            return respDto;
        }).toList();
    }

}
