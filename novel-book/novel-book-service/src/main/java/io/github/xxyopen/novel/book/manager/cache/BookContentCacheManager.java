package io.github.xxyopen.novel.book.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.book.dao.entity.BookContent;
import io.github.xxyopen.novel.book.dao.mapper.BookContentMapper;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 小说正文 缓存管理类：按 chapterId 缓存章节的大文本内容。
 *
 * <p>【为什么放 Redis 而不是本地 Caffeine】正文是本项目体积最大的缓存 value，
 * 且随章节数线性增长；若放进本地 JVM 堆，几百上千章就能把堆撑满、引发频繁 GC 甚至 OOM，
 * 多实例还会各自存一份造成内存翻倍。放到 Redis 集中存储（ttl 12h）则容量可控、
 * 多实例共享，阅读器翻章时命中即返回，避免每次都去查大文本字段拖慢数据库。
 * 这也是「大对象走远程、小热点走本地」这一分级原则的体现。
 *
 * <p>【边界】与章节目录一样，这里假设 chapterId 对应的正文一定存在，直接取 getContent()；
 * 传入无效章节 ID 会 NPE，依赖上游阅读接口先做参数与存在性校验。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookContentCacheManager {

    private final BookContentMapper bookContentMapper;

    /**
     * 按 chapterId 查询章节正文并缓存，缓存 key 即 chapterId。
     * 查询用 LIMIT 1 兜底：即便数据异常出现同章节多条，也只取一条，避免结果不确定。
     */
    @Cacheable(cacheManager = CacheConsts.REDIS_CACHE_MANAGER,
        value = CacheConsts.BOOK_CONTENT_CACHE_NAME)
    public String getBookContent(Long chapterId) {
        QueryWrapper<BookContent> contentQueryWrapper = new QueryWrapper<>();
        contentQueryWrapper.eq(DatabaseConsts.BookContentTable.COLUMN_CHAPTER_ID, chapterId)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        BookContent bookContent = bookContentMapper.selectOne(contentQueryWrapper);
        return bookContent.getContent();
    }

}
