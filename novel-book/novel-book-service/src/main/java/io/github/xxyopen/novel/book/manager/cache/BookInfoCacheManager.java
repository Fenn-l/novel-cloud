package io.github.xxyopen.novel.book.manager.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.book.dao.entity.BookChapter;
import io.github.xxyopen.novel.book.dao.entity.BookInfo;
import io.github.xxyopen.novel.book.dao.mapper.BookChapterMapper;
import io.github.xxyopen.novel.book.dao.mapper.BookInfoMapper;
import io.github.xxyopen.novel.book.dto.resp.BookInfoRespDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小说信息 缓存管理类：负责书籍详情页基础信息的读取与失效。
 *
 * <p>【为什么用本地缓存 + 18h】书籍基础信息（书名、简介、作者、分类、字数、首/末章 ID 等）
 * 是详情页与列表页的高频读数据，且变更很少；放在 Caffeine 本地缓存里可以零网络开销地扛住读流量。
 * CacheConsts 中 BOOK_INFO_CACHE 配了 ttl=18h、最大 500 条，兼顾命中率与堆内存占用。
 *
 * <p>【一致性怎么保证：MQ 广播清理】Caffeine 是每个服务实例各存一份的本地缓存，
 * 书籍信息或章节一旦变更（作家发布新章节、编辑书籍），单实例自己 evict 只能清掉自己那份，
 * 其它实例仍会继续返回旧数据直到 18h 后自然过期。因此发布章节时由消息中间件广播
 * BookChangeMsg，每个实例收到后都调用 {@code evictBookInfoCache(bookId)} 清掉本地副本，
 * 这是两级缓存方案里「写后失效」的关键补偿，也是面试里最该讲清楚的一致性问题。
 *
 * <p>【为什么 getBookInfo 必须自己查库，不能再调同类方法】原实现里 getBookInfo(@Cacheable)
 * 内部调用 {@code this.cachePutBookInfo(id)}(@CachePut)，属于同类方法自调用（self-invocation）：
 * Spring 缓存基于 AOP 代理，{@code this.xxx()} 不经过代理，@CachePut 永远不生效，
 * 而且语义上 @Cacheable 命中时本就不该再查库。现在把查询逻辑直接内联进 getBookInfo，
 * 让「缓存未命中 → 查库 → 由切面把返回值写入缓存」一条链走通，去掉多余的 @CachePut 方法。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookInfoCacheManager {

    private final BookInfoMapper bookInfoMapper;

    private final BookChapterMapper bookChapterMapper;

    /**
     * 查询书籍详情并缓存。@Cacheable 以参数 id 为 key：命中直接返回缓存值，
     * 未命中才执行方法体查库，返回值由切面自动写入 bookInfoCache。
     *
     * <p>首章 ID 需要额外查 chapter 表按章节号升序取第一条。新书刚创建、还没来得及发布章节时
     * 查出来是 null，此时 firstChapterId 置空即可，不能对 null 调 getId()（会 NPE）；
     * 前端据该字段是否为空来决定「开始阅读」按钮是否可点。
     *
     * @param id 书籍ID，同时也是缓存 key
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_INFO_CACHE_NAME)
    public BookInfoRespDto getBookInfo(Long id) {
        // 书籍基础信息
        BookInfo bookInfo = bookInfoMapper.selectById(id);
        // 首章：按章节号升序取第一章，用于详情页「开始阅读」入口
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper
            .eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, id)
            .orderByAsc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        BookChapter firstBookChapter = bookChapterMapper.selectOne(queryWrapper);
        return BookInfoRespDto.builder()
            .id(bookInfo.getId())
            .bookName(bookInfo.getBookName())
            .bookDesc(bookInfo.getBookDesc())
            .bookStatus(bookInfo.getBookStatus())
            .authorId(bookInfo.getAuthorId())
            .authorName(bookInfo.getAuthorName())
            .categoryId(bookInfo.getCategoryId())
            .categoryName(bookInfo.getCategoryName())
            .commentCount(bookInfo.getCommentCount())
            .firstChapterId(firstBookChapter == null ? null : firstBookChapter.getId())
            .lastChapterId(bookInfo.getLastChapterId())
            .picUrl(bookInfo.getPicUrl())
            .visitCount(bookInfo.getVisitCount())
            .wordCount(bookInfo.getWordCount())
            .build();
    }

    /**
     * 清除单本书的本地缓存。方法体故意留空——真正干活的是 {@code @CacheEvict} 切面。
     *
     * <p>【参数为什么叫 ignoredId 却不能删】{@code @CacheEvict} 默认以方法参数作为缓存 key，
     * 这里必须与 getBookInfo(id) 的 key 保持一致才能精确命中那条缓存；
     * 参数虽不在方法体里使用，但它是缓存键，删掉或改名会导致清不掉（退化成 SimpleKey.EMPTY）。
     * 由 MQ 消费者在书籍 / 章节变更时调用，各实例各自清理自己的 Caffeine 副本。
     */
    @CacheEvict(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_INFO_CACHE_NAME)
    public void evictBookInfoCache(Long ignoredId) {
        // 由 @CacheEvict 完成清理，方法体无需逻辑
    }

    /**
     * 查询某分类下最近更新的 500 个书籍ID列表并缓存 1 小时。
     * 这是「按分类批量捞更新」列表页的数据源：只取已有字数（发布过章节）的书，
     * 按最后章节更新时间倒序，限制 500 条防止一次加载过多拖慢响应。
     * 只缓存 ID 列表而非整本书对象，详情再回源到 getBookInfo，避免同一份数据缓存两份、产生不一致。
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.LAST_UPDATE_BOOK_ID_LIST_CACHE_NAME)
    public List<Long> getLastUpdateIdList(Long categoryId) {
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookTable.COLUMN_CATEGORY_ID, categoryId)
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .orderByDesc(DatabaseConsts.BookTable.COLUMN_LAST_CHAPTER_UPDATE_TIME)
            .last(DatabaseConsts.SqlEnum.LIMIT_500.getSql());
        return bookInfoMapper.selectList(queryWrapper).stream().map(BookInfo::getId).toList();
    }

}
