package io.github.xxyopen.novel.book.manager.cache;

import io.github.xxyopen.novel.book.dao.entity.BookChapter;
import io.github.xxyopen.novel.book.dao.mapper.BookChapterMapper;
import io.github.xxyopen.novel.book.dto.resp.BookChapterRespDto;
import io.github.xxyopen.novel.common.constant.CacheConsts;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 小说章节（目录元信息）缓存管理类。
 *
 * <p>【存什么、为什么不存正文】这里缓存的是章节的「目录信息」：章节号、章节名、字数、
 * 更新时间、所属书籍，供目录页翻页和阅读器上一章 / 下一章导航使用。
 * 正文大文本由 BookContentCacheManager 单独走 Redis 缓存。两者拆开是因为体积与访问模式不同：
 * 目录小、访问极高频，放 Caffeine 本地（ttl 6h、最多 5000 条）零网络开销；
 * 正文可能几十 KB、数量随章节线性增长，塞进 JVM 堆容易 OOM，放 Redis 更合适。
 * 拆开后还能按需加载——进目录页不必把正文也捞出来。
 *
 * <p>【边界】getChapter 假设上游（阅读接口的 chapterId 校验）已确认章节存在，
 * 因此直接对 selectById 结果取字段、没有判空；若传入不存在的 chapterId 会 NPE。
 * 这是「信任内部调用链」的简化，生产上应在入口校验或返回可预期的空结果。
 *
 * @author xiongxiaoyang
 * @date 2022/5/12
 */
@Component
@RequiredArgsConstructor
public class BookChapterCacheManager {

    private final BookChapterMapper bookChapterMapper;

    /**
     * 按 chapterId 查询章节元信息并缓存，缓存 key 即 chapterId。
     * 返回的 DTO 里 id 直接用入参回填，避免再读一次实体主键。
     */
    @Cacheable(cacheManager = CacheConsts.CAFFEINE_CACHE_MANAGER,
        value = CacheConsts.BOOK_CHAPTER_CACHE_NAME)
    public BookChapterRespDto getChapter(Long chapterId) {
        BookChapter bookChapter = bookChapterMapper.selectById(chapterId);
        return BookChapterRespDto.builder()
            .id(chapterId)
            .bookId(bookChapter.getBookId())
            .chapterNum(bookChapter.getChapterNum())
            .chapterName(bookChapter.getChapterName())
            .chapterWordCount(bookChapter.getWordCount())
            .chapterUpdateTime(bookChapter.getUpdateTime())
            .build();
    }

}
