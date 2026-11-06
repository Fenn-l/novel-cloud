package io.github.xxyopen.novel.book.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.xxyopen.novel.book.dao.entity.BookChapter;
import io.github.xxyopen.novel.book.dao.entity.BookComment;
import io.github.xxyopen.novel.book.dao.entity.BookContent;
import io.github.xxyopen.novel.book.dao.entity.BookInfo;
import io.github.xxyopen.novel.book.dao.mapper.BookChapterMapper;
import io.github.xxyopen.novel.book.dao.mapper.BookCommentMapper;
import io.github.xxyopen.novel.book.dao.mapper.BookContentMapper;
import io.github.xxyopen.novel.book.dao.mapper.BookInfoMapper;
import io.github.xxyopen.novel.book.dto.req.*;
import io.github.xxyopen.novel.book.dto.resp.*;
import io.github.xxyopen.novel.book.manager.cache.*;
import io.github.xxyopen.novel.book.manager.feign.UserFeignManager;
import io.github.xxyopen.novel.book.manager.mq.AmqpMsgManager;
import io.github.xxyopen.novel.book.service.BookService;
import io.github.xxyopen.novel.common.auth.UserHolder;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.resp.PageRespDto;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.config.annotation.Key;
import io.github.xxyopen.novel.config.annotation.Lock;
import io.github.xxyopen.novel.config.exception.BusinessException;
import io.github.xxyopen.novel.user.dto.resp.UserInfoRespDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 小说模块服务实现 —— 整个平台的核心，围绕「书 / 章节 / 正文 / 评论」四张表展开。
 *
 * <p><b>读链路</b>（门户流量主体，读多写少，全部走缓存）：
 * <ul>
 *   <li>分类、榜单、书信息、章节元信息、推荐候选池 → Caffeine 进程内缓存；</li>
 *   <li>章节正文 → Redis（正文体量大，不能塞进每个 JVM 的堆里）；</li>
 *   <li>目录列表、章节总数、上一章/下一章 → 直接查 DB，数据量小且变化频繁，不缓存。</li>
 * </ul>
 *
 * <p><b>写链路</b>（作家发布章节，全项目唯一使用本地事务的业务）：
 * 加 Redisson 分布式锁 → 写 book_chapter / book_content → 更新 book_info → 清本实例缓存
 * → 事务提交后发 MQ 广播（ES 增量索引 + 各实例清本地缓存）。
 *
 * <p><b>分布式锁用在两处「先查后写」</b>：发评论（用户×书）、发章节（书）。
 * 二者都存在"查当前状态 → 计算新值 → 插入"的竞态，DB 唯一键是最后一道兜底。
 *
 * <p><b>业务边界（面试必须主动说明，别装作有）</b>：
 * 本项目是学习型的微服务门户，{@code isVip} 目前只是章节/书籍上的一个标记位，
 * <b>没有</b>付费章节鉴权、订阅扣费、加入/移出书架、阅读历史落库、评论回复这些实现。
 * 相关表（user_bookshelf、user_read_history、user_pay_log、user_consume_log 等）
 * 是单体版遗留结构，本微服务版只用到「书架状态查询」。详见 doc/面试讲解.md。
 *
 * @author xiongxiaoyang
 * @date 2022/5/14
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookServiceImpl implements BookService {

    private final BookCategoryCacheManager bookCategoryCacheManager;

    private final BookRankCacheManager bookRankCacheManager;

    private final BookInfoCacheManager bookInfoCacheManager;

    private final BookChapterCacheManager bookChapterCacheManager;

    private final BookContentCacheManager bookContentCacheManager;

    private final BookInfoMapper bookInfoMapper;

    private final BookChapterMapper bookChapterMapper;

    private final BookContentMapper bookContentMapper;

    private final BookCommentMapper bookCommentMapper;

    private final AmqpMsgManager amqpMsgManager;

    private final UserFeignManager userFeignManager;

    /** 详情页「为你推荐」展示本数 */
    private static final Integer REC_BOOK_COUNT = 4;

    /** 最新章节摘要截断长度 */
    private static final int SUMMARY_LENGTH = 30;

    // ---------------------------------------------------------------- 榜单 / 详情

    /**
     * 点击榜 / 新书榜 / 更新榜。
     * 三种榜单是三份独立缓存：点击榜放 Redis（跨实例共享，6h），另两个放 Caffeine（30min / 60s）。
     * 这里故意不自己查 DB，全部交给 BookRankCacheManager，Service 只做响应包装。
     */
    @Override
    public RestResp<List<BookRankRespDto>> listVisitRankBooks() {
        return RestResp.ok(bookRankCacheManager.listVisitRankBooks());
    }

    @Override
    public RestResp<List<BookRankRespDto>> listNewestRankBooks() {
        return RestResp.ok(bookRankCacheManager.listNewestRankBooks());
    }

    @Override
    public RestResp<List<BookRankRespDto>> listUpdateRankBooks() {
        return RestResp.ok(bookRankCacheManager.listUpdateRankBooks());
    }

    /**
     * 小说详情页：直接读 Caffeine 中的书信息快照（18h TTL）。
     * 发布新章节时会通过 MQ 广播清理各实例缓存，所以这里的「旧数据窗口」很短。
     */
    @Override
    public RestResp<BookInfoRespDto> getBookById(Long bookId) {
        return RestResp.ok(bookInfoCacheManager.getBookInfo(bookId));
    }

    /**
     * 详情页顶部「最新章节」卡片：书名/最新章信息 + 正文前 30 字摘要 + 章节总数。
     *
     * <p>三段数据分别来自书信息缓存、章节缓存、正文 Redis 缓存，命中即返回，
     * 未命中由各 CacheManager 回源 DB 并回填；章节总数变化频繁，不做缓存直接 count。
     */
    @Override
    public RestResp<BookChapterAboutRespDto> getLastChapterAbout(Long bookId) {
        BookInfoRespDto bookInfo = bookInfoCacheManager.getBookInfo(bookId);
        // 新书可能还没有任何章节，lastChapterId 为 null；此时返回空摘要而不是 500
        if (Objects.isNull(bookInfo) || Objects.isNull(bookInfo.getLastChapterId())) {
            return RestResp.ok(BookChapterAboutRespDto.builder()
                .chapterTotal(0L)
                .contentSummary("")
                .build());
        }

        BookChapterRespDto bookChapter = bookChapterCacheManager.getChapter(
            bookInfo.getLastChapterId());
        String content = bookContentCacheManager.getBookContent(bookInfo.getLastChapterId());

        QueryWrapper<BookChapter> chapterQueryWrapper = new QueryWrapper<>();
        chapterQueryWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, bookId);
        Long chapterTotal = bookChapterMapper.selectCount(chapterQueryWrapper);

        // 正文可能不足 30 字（或为空），必须按实际长度截断，原来写死 substring(0,30) 会越界
        String summary = Objects.isNull(content) ? ""
            : content.substring(0, Math.min(SUMMARY_LENGTH, content.length()));
        return RestResp.ok(BookChapterAboutRespDto.builder()
            .chapterInfo(bookChapter)
            .chapterTotal(chapterTotal)
            .contentSummary(summary)
            .build());
    }

    /**
     * 「同类推荐」：取同分类下最近更新的 500 本书作为候选池（该 ID 列表缓存 1 小时），
     * 洗牌后取前 4 本。
     *
     * <p>相比早期的「while 循环随机下标去重」写法，这里修掉三个问题：
     * 候选为空时 nextInt(0) 抛异常、候选不足 4 本时死循环、可能把当前这本书推荐给自己。
     * 另外用普通 SecureRandom 代替 getInstanceStrong()，后者在熵不足时会阻塞请求线程。
     */
    @Override
    public RestResp<List<BookInfoRespDto>> listRecBooks(Long bookId)
        throws NoSuchAlgorithmException {
        BookInfoRespDto current = bookInfoCacheManager.getBookInfo(bookId);
        if (Objects.isNull(current) || Objects.isNull(current.getCategoryId())) {
            return RestResp.ok(Collections.emptyList());
        }
        List<Long> candidates = bookInfoCacheManager.getLastUpdateIdList(current.getCategoryId());
        if (Objects.isNull(candidates) || candidates.isEmpty()) {
            return RestResp.ok(Collections.emptyList());
        }

        List<Long> pool = candidates.stream()
            .filter(id -> !Objects.equals(id, bookId))
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(pool, new SecureRandom());

        int size = Math.min(REC_BOOK_COUNT, pool.size());
        List<BookInfoRespDto> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BookInfoRespDto info = bookInfoCacheManager.getBookInfo(pool.get(i));
            if (Objects.nonNull(info)) {
                result.add(info);
            }
        }
        return RestResp.ok(result);
    }

    /**
     * 点击量 +1。
     *
     * <p>用 SQL 原子自增（见 BookInfoMapper.xml），不走「读-改-写」，避免并发丢计数。
     * <b>刻意不失效 bookInfoCache</b>：详情页每次浏览都会调这个方法，若每次清缓存，
     * 等于让读多写少的详情页每次都回源 DB。点击量是展示型指标，允许缓存 TTL 内的滞后；
     * 要求准确的点击榜由独立的 Redis 榜单缓存按周期重算。这是明确的最终一致取舍。
     */
    @Override
    public RestResp<Void> addVisitCount(Long bookId) {
        bookInfoMapper.addVisitCount(bookId);
        return RestResp.ok();
    }

    /**
     * 上一章 / 下一章：按 chapter_num 有序取相邻一条。
     * 章节号连续由发布链路的分布式锁保证；到头/到尾时返回 null，前端据此禁用按钮。
     */
    @Override
    public RestResp<Long> getPreChapterId(Long chapterId) {
        BookChapterRespDto chapter = bookChapterCacheManager.getChapter(chapterId);
        if (Objects.isNull(chapter)) {
            return RestResp.ok(null);
        }
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, chapter.getBookId())
            .lt(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM, chapter.getChapterNum())
            .orderByDesc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        return RestResp.ok(
            Optional.ofNullable(bookChapterMapper.selectOne(queryWrapper))
                .map(BookChapter::getId)
                .orElse(null)
        );
    }

    @Override
    public RestResp<Long> getNextChapterId(Long chapterId) {
        BookChapterRespDto chapter = bookChapterCacheManager.getChapter(chapterId);
        if (Objects.isNull(chapter)) {
            return RestResp.ok(null);
        }
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, chapter.getBookId())
            .gt(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM, chapter.getChapterNum())
            .orderByAsc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        return RestResp.ok(
            Optional.ofNullable(bookChapterMapper.selectOne(queryWrapper))
                .map(BookChapter::getId)
                .orElse(null)
        );
    }

    /**
     * 目录列表：只返回 id / 章节名 / 是否收费，正文不在这里给，避免列表接口传输大文本。
     * 目录变化频繁、单本最多几千条，直接查 DB 比维护缓存更划算。
     */
    @Override
    public RestResp<List<BookChapterRespDto>> listChapters(Long bookId) {
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, bookId)
            .orderByAsc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM);
        return RestResp.ok(bookChapterMapper.selectList(queryWrapper).stream()
            .map(v -> BookChapterRespDto.builder()
                .id(v.getId())
                .chapterName(v.getChapterName())
                .isVip(v.getIsVip())
                .build()).toList());
    }

    /** 分类列表（男频/女频各一套），几乎不变，放本地缓存且 ttl=0 常驻 */
    @Override
    public RestResp<List<BookCategoryRespDto>> listCategory(Integer workDirection) {
        return RestResp.ok(bookCategoryCacheManager.listCategory(workDirection));
    }

    // ---------------------------------------------------------------- 评论

    /**
     * 发表评论。
     *
     * <p>业务规则：同一用户对同一本书只允许评论一次（book_comment 上有 uk_bookId_userId）。
     * 「先 selectCount 判重再 insert」是典型的检查-执行竞态，所以用 Redisson 锁把
     * 粒度收敛到 (userId, bookId)：@Lock 的 prefix + @Key 的 SpEL 拼出锁 key。
     * 抢锁失败返回 USER_REQ_MANY（原实现默认错误码是 OK，会返回"成功码但没数据"）。
     * 即使多实例下锁失效，DB 唯一键仍是最后兜底，DuplicateKeyException 由全局异常处理器转换。
     */
    @Lock(prefix = "userComment", failCode = ErrorCodeEnum.USER_REQ_MANY)
    @Override
    public RestResp<Void> saveComment(
        @Key(expr = "#{userId + '::' + bookId}") BookCommentReqDto dto) {
        QueryWrapper<BookComment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookCommentTable.COLUMN_USER_ID, dto.getUserId())
            .eq(DatabaseConsts.BookCommentTable.COLUMN_BOOK_ID, dto.getBookId());
        if (bookCommentMapper.selectCount(queryWrapper) > 0) {
            return RestResp.fail(ErrorCodeEnum.USER_COMMENTED);
        }
        BookComment bookComment = new BookComment();
        bookComment.setBookId(dto.getBookId());
        bookComment.setUserId(dto.getUserId());
        bookComment.setCommentContent(dto.getCommentContent());
        bookComment.setCreateTime(LocalDateTime.now());
        bookComment.setUpdateTime(LocalDateTime.now());
        bookCommentMapper.insert(bookComment);
        return RestResp.ok();
    }

    /**
     * 最新 5 条评论 + 评论总数。
     *
     * <p>评论表只冗余了 userId，昵称/头像在 user 服务里。这里用一次 Feign <b>批量</b>查询
     * 再转 Map 做内存 join，避免逐条远程调用（N+1）。
     * 注意 Feign 降级或用户被删时查不到对应 UserInfoRespDto，必须用"匿名用户"兜底，
     * 否则一次下游抖动会把整个评论列表打成 500 —— 降级不该把故障转移到上游。
     */
    @Override
    public RestResp<BookCommentRespDto> listNewestComments(Long bookId) {
        QueryWrapper<BookComment> countWrapper = new QueryWrapper<>();
        countWrapper.eq(DatabaseConsts.BookCommentTable.COLUMN_BOOK_ID, bookId);
        Long commentTotal = bookCommentMapper.selectCount(countWrapper);
        BookCommentRespDto respDto = BookCommentRespDto.builder()
            .commentTotal(commentTotal).build();
        if (commentTotal <= 0) {
            respDto.setComments(Collections.emptyList());
            return RestResp.ok(respDto);
        }

        QueryWrapper<BookComment> commentWrapper = new QueryWrapper<>();
        commentWrapper.eq(DatabaseConsts.BookCommentTable.COLUMN_BOOK_ID, bookId)
            .orderByDesc(DatabaseConsts.CommonColumnEnum.CREATE_TIME.getName())
            .last(DatabaseConsts.SqlEnum.LIMIT_5.getSql());
        List<BookComment> bookComments = bookCommentMapper.selectList(commentWrapper);

        List<Long> userIds = bookComments.stream()
            .map(BookComment::getUserId).distinct().toList();
        List<UserInfoRespDto> userInfos = userFeignManager.listUserInfoByIds(userIds);
        Map<Long, UserInfoRespDto> userInfoMap = (Objects.isNull(userInfos)
            ? Collections.<UserInfoRespDto>emptyList() : userInfos).stream()
            .filter(Objects::nonNull)
            // 合并函数防止同一 userId 重复返回时 toMap 抛 IllegalStateException
            .collect(Collectors.toMap(UserInfoRespDto::getId, Function.identity(), (a, b) -> a));

        List<BookCommentRespDto.CommentInfo> commentInfos = bookComments.stream()
            .map(v -> {
                UserInfoRespDto user = userInfoMap.get(v.getUserId());
                return BookCommentRespDto.CommentInfo.builder()
                    .id(v.getId())
                    .commentUserId(v.getUserId())
                    .commentUser(Objects.isNull(user) ? "匿名用户" : user.getUsername())
                    .commentUserPhoto(Objects.isNull(user) ? null : user.getUserPhoto())
                    .commentContent(v.getCommentContent())
                    .commentTime(v.getCreateTime())
                    .build();
            }).toList();
        respDto.setComments(commentInfos);
        return RestResp.ok(respDto);
    }

    /**
     * 删除 / 修改评论：WHERE 条件里必须同时带 commentId <b>和</b> userId，
     * 防止恶意调用改掉别人的评论（水平越权）。这是最基础也最容易被面试官挑的点。
     */
    @Override
    public RestResp<Void> deleteComment(BookCommentReqDto dto) {
        QueryWrapper<BookComment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.CommonColumnEnum.ID.getName(), dto.getCommentId())
            .eq(DatabaseConsts.BookCommentTable.COLUMN_USER_ID, dto.getUserId());
        bookCommentMapper.delete(queryWrapper);
        return RestResp.ok();
    }

    @Override
    public RestResp<Void> updateComment(BookCommentReqDto dto) {
        QueryWrapper<BookComment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.CommonColumnEnum.ID.getName(), dto.getCommentId())
            .eq(DatabaseConsts.BookCommentTable.COLUMN_USER_ID, dto.getUserId());
        BookComment bookComment = new BookComment();
        bookComment.setCommentContent(dto.getCommentContent());
        bookCommentMapper.update(bookComment, queryWrapper);
        return RestResp.ok();
    }

    // ---------------------------------------------------------------- 作家发布

    /**
     * 作家新建作品：先查同名（book_info 上有 uk_bookName_authorName），再落一条基础信息。
     * 此时还没有任何章节，所以 lastChapterId / wordCount 都是空，首页推荐也不会捞到它
     * （推荐候选池过滤 word_count > 0）。
     */
    @Override
    public RestResp<Void> saveBook(BookAddReqDto dto) {
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookTable.COLUMN_BOOK_NAME, dto.getBookName());
        if (bookInfoMapper.selectCount(queryWrapper) > 0) {
            return RestResp.fail(ErrorCodeEnum.AUTHOR_BOOK_NAME_EXIST);
        }
        BookInfo bookInfo = new BookInfo();
        bookInfo.setAuthorId(dto.getAuthorId());
        bookInfo.setAuthorName(dto.getPenName());
        bookInfo.setWorkDirection(dto.getWorkDirection());
        bookInfo.setCategoryId(dto.getCategoryId());
        bookInfo.setCategoryName(dto.getCategoryName());
        bookInfo.setBookName(dto.getBookName());
        bookInfo.setPicUrl(dto.getPicUrl());
        bookInfo.setBookDesc(dto.getBookDesc());
        bookInfo.setIsVip(dto.getIsVip());
        bookInfo.setScore(0);
        bookInfo.setCreateTime(LocalDateTime.now());
        bookInfo.setUpdateTime(LocalDateTime.now());
        bookInfoMapper.insert(bookInfo);
        return RestResp.ok();
    }

    /**
     * 发布章节 —— 全项目最关键的一条写链路，也是唯一使用本地事务的业务。
     *
     * <p>执行顺序：
     * <ol>
     *   <li><b>先加分布式锁</b>（粒度 = bookId）：章节号是「查当前最大 chapter_num + 1」，
     *       两个作家端并发发同一本书会算出同一个号，撞 uk_bookId_chapterNum 后一个请求 500。
     *       锁切面必须在事务外层，保证「锁释放」发生在「事务提交」之后；</li>
     *   <li>写 book_chapter（章节元信息）与 book_content（正文）；</li>
     *   <li>回写 book_info 的 lastChapterId / lastChapterName / 总字数；</li>
     *   <li>清本实例 Caffeine 缓存，避免详情页还显示旧的 lastChapterId；</li>
     *   <li>注册事务同步，<b>afterCommit 之后</b>才投递 MQ 广播。</li>
     * </ol>
     *
     * <p>为什么消息一定要在事务提交后发：如果先发消息再提交，事务一旦回滚，
     * ES 就会索引到一条「数据库里根本不存在」的章节。afterCommit 保证不会出现这种脏消息；
     * 反过来「事务提交成功但消息没发出去」由生产者 confirm + 待重放列表 + XXL-JOB 全量兜底。
     *
     * <p>MQ 广播有两类消费者：search 服务增量更新 ES 索引；book 服务每个实例各清一份本地缓存。
     */
    @Lock(prefix = "publishChapter", failCode = ErrorCodeEnum.USER_REQ_MANY)
    @Transactional(rollbackFor = Exception.class)
    @Override
    public RestResp<Void> saveBookChapter(
        @Key(expr = "#{bookId}") ChapterAddReqDto dto) {
        // 归属校验：只能给自己名下的书发章节，防止越权操作他人作品
        BookInfo bookInfo = bookInfoMapper.selectById(dto.getBookId());
        if (Objects.isNull(bookInfo)) {
            return RestResp.fail(ErrorCodeEnum.USER_UN_AUTH);
        }
        if (!Objects.equals(bookInfo.getAuthorId(), dto.getAuthorId())) {
            return RestResp.fail(ErrorCodeEnum.USER_UN_AUTH);
        }

        // 1) 计算新章节号（锁保证同一本书串行；DB 唯一键再兜一层底）
        int chapterNum = 0;
        QueryWrapper<BookChapter> chapterWrapper = new QueryWrapper<>();
        chapterWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, dto.getBookId())
            .orderByDesc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM)
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        BookChapter latestChapter = bookChapterMapper.selectOne(chapterWrapper);
        if (Objects.nonNull(latestChapter) && Objects.nonNull(latestChapter.getChapterNum())) {
            chapterNum = latestChapter.getChapterNum() + 1;
        }

        BookChapter newBookChapter = new BookChapter();
        newBookChapter.setBookId(dto.getBookId());
        newBookChapter.setChapterName(dto.getChapterName());
        newBookChapter.setChapterNum(chapterNum);
        newBookChapter.setWordCount(dto.getChapterContent().length());
        newBookChapter.setIsVip(dto.getIsVip());
        newBookChapter.setCreateTime(LocalDateTime.now());
        newBookChapter.setUpdateTime(LocalDateTime.now());
        bookChapterMapper.insert(newBookChapter);

        // 2) 正文单独一张表：章节列表/详情页不查它，只有点进阅读才按 chapterId 取，
        //    大文本与元数据分离，避免列表查询被正文字段拖慢
        BookContent bookContent = new BookContent();
        bookContent.setContent(dto.getChapterContent());
        bookContent.setChapterId(newBookChapter.getId());
        bookContent.setCreateTime(LocalDateTime.now());
        bookContent.setUpdateTime(LocalDateTime.now());
        bookContentMapper.insert(bookContent);

        // 3) 回写书籍汇总信息。word_count 在 DDL 上有默认值 0，但代码里仍按 null 防御，
        //    避免历史数据为 null 时整条发布链路 NPE
        int oldWordCount = Objects.isNull(bookInfo.getWordCount()) ? 0 : bookInfo.getWordCount();
        BookInfo newBookInfo = new BookInfo();
        newBookInfo.setId(dto.getBookId());
        newBookInfo.setLastChapterId(newBookChapter.getId());
        newBookInfo.setLastChapterName(newBookChapter.getChapterName());
        newBookInfo.setLastChapterUpdateTime(LocalDateTime.now());
        newBookInfo.setWordCount(oldWordCount + newBookChapter.getWordCount());
        newBookInfo.setUpdateTime(LocalDateTime.now());
        bookInfoMapper.updateById(newBookInfo);

        // 4) 清当前实例缓存；其它实例由 MQ 的独占广播队列各自清理
        bookInfoCacheManager.evictBookInfoCache(dto.getBookId());
        // 5) 事务提交后才广播（AmqpMsgManager 内部注册 afterCommit）
        amqpMsgManager.sendBookChangeMsg(dto.getBookId());
        return RestResp.ok();
    }

    /** 作家后台：按作家 ID 分页列出自己的作品 */
    @Override
    public RestResp<PageRespDto<BookInfoRespDto>> listAuthorBooks(BookPageReqDto dto) {
        IPage<BookInfo> page = new Page<>();
        page.setCurrent(dto.getPageNum());
        page.setSize(dto.getPageSize());
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookTable.AUTHOR_ID, dto.getAuthorId())
            .orderByDesc(DatabaseConsts.CommonColumnEnum.CREATE_TIME.getName());
        IPage<BookInfo> bookInfoPage = bookInfoMapper.selectPage(page, queryWrapper);
        return RestResp.ok(PageRespDto.of(dto.getPageNum(), dto.getPageSize(), page.getTotal(),
            bookInfoPage.getRecords().stream().map(v -> BookInfoRespDto.builder()
                .id(v.getId())
                .bookName(v.getBookName())
                .picUrl(v.getPicUrl())
                .categoryName(v.getCategoryName())
                .wordCount(v.getWordCount())
                .visitCount(v.getVisitCount())
                .updateTime(v.getUpdateTime())
                .build()).toList()));
    }

    /** 作家后台：某本书的章节分页列表（按章节号倒序，最新发的在最前） */
    @Override
    public RestResp<PageRespDto<BookChapterRespDto>> listBookChapters(ChapterPageReqDto dto) {
        IPage<BookChapter> page = new Page<>();
        page.setCurrent(dto.getPageNum());
        page.setSize(dto.getPageSize());
        QueryWrapper<BookChapter> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.BookChapterTable.COLUMN_BOOK_ID, dto.getBookId())
            .orderByDesc(DatabaseConsts.BookChapterTable.COLUMN_CHAPTER_NUM);
        IPage<BookChapter> bookChapterPage = bookChapterMapper.selectPage(page, queryWrapper);
        return RestResp.ok(PageRespDto.of(dto.getPageNum(), dto.getPageSize(), page.getTotal(),
            bookChapterPage.getRecords().stream().map(v -> BookChapterRespDto.builder()
                .id(v.getId())
                .chapterName(v.getChapterName())
                .chapterUpdateTime(v.getUpdateTime())
                .isVip(v.getIsVip())
                .build()).toList()));
    }

    /**
     * 供 search 服务批量拉取「可索引小说」，按自增 ID 游标分批（每批 30 条），
     * 同时被 XXL-JOB 全量同步和 MQ 增量消费者复用：
     * 全量任务从 maxBookId=0 一路往后翻，增量消费者传 bookId-1 取它所在那一批。
     *
     * <p>过滤 word_count > 0：没有正文的书不进索引，避免搜出一本空书。
     * 新书刚创建时 lastChapterUpdateTime 为 null，转成 0 而不是 NPE（否则整批同步失败）。
     */
    @Override
    public RestResp<List<BookEsRespDto>> listNextEsBooks(Long maxBookId) {
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc(DatabaseConsts.CommonColumnEnum.ID.getName())
            .gt(DatabaseConsts.CommonColumnEnum.ID.getName(), maxBookId)
            .gt(DatabaseConsts.BookTable.COLUMN_WORD_COUNT, 0)
            .last(DatabaseConsts.SqlEnum.LIMIT_30.getSql());
        return RestResp.ok(bookInfoMapper.selectList(queryWrapper).stream()
            .map(bookInfo -> BookEsRespDto.builder()
                .id(bookInfo.getId())
                .categoryId(bookInfo.getCategoryId())
                .categoryName(bookInfo.getCategoryName())
                .bookDesc(bookInfo.getBookDesc())
                .bookName(bookInfo.getBookName())
                .authorId(bookInfo.getAuthorId())
                .authorName(bookInfo.getAuthorName())
                .bookStatus(bookInfo.getBookStatus())
                .commentCount(bookInfo.getCommentCount())
                .isVip(bookInfo.getIsVip())
                .score(bookInfo.getScore())
                .visitCount(bookInfo.getVisitCount())
                .wordCount(bookInfo.getWordCount())
                .workDirection(bookInfo.getWorkDirection())
                .lastChapterId(bookInfo.getLastChapterId())
                .lastChapterName(bookInfo.getLastChapterName())
                .lastChapterUpdateTime(Objects.isNull(bookInfo.getLastChapterUpdateTime()) ? 0L
                    : bookInfo.getLastChapterUpdateTime()
                        .toInstant(ZoneOffset.ofHours(8)).toEpochMilli())
                .build()).collect(Collectors.toList()));
    }

    /**
     * 供 home 服务批量补全首页推荐位的书名/封面/作者。
     * home_book 表里只存 bookId 和排序，具体书籍信息必须跨服务回查，这里一次 IN 查询搞定。
     */
    @Override
    public RestResp<List<BookInfoRespDto>> listBookInfoByIds(List<Long> bookIds) {
        QueryWrapper<BookInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.in(DatabaseConsts.CommonColumnEnum.ID.getName(), bookIds);
        return RestResp.ok(
            bookInfoMapper.selectList(queryWrapper).stream().map(v -> BookInfoRespDto.builder()
                .id(v.getId())
                .bookName(v.getBookName())
                .authorName(v.getAuthorName())
                .picUrl(v.getPicUrl())
                .bookDesc(v.getBookDesc())
                .build()).collect(Collectors.toList()));
    }

    /**
     * 阅读页：聚合「章节元信息 + 正文 + 所属书籍信息」一次返回，省掉前端三次请求。
     *
     * <p>登录态由 TokenParseInterceptor 解析 JWT 后放进 UserHolder（ThreadLocal），
     * 但<b>当前版本没有付费鉴权</b>：isVip 只是展示标记，不拦截正文返回。
     * 早期注释声称"付费校验在上层/购买逻辑处理"与实现不符，已更正 —— 面试时不要照着旧注释讲。
     * 预留 UserHolder 是为了后续接「章节购买校验 + 阅读历史落库」时能直接拿到 userId。
     */
    @Override
    public RestResp<BookContentAboutRespDto> getBookContentAbout(Long chapterId) {
        log.debug("[阅读页] 当前登录 userId={}（当前版本仅记录，未做付费鉴权）",
            UserHolder.getUserId());

        BookChapterRespDto bookChapter = bookChapterCacheManager.getChapter(chapterId);
        if (Objects.isNull(bookChapter)) {
            throw new BusinessException(ErrorCodeEnum.USER_REQUEST_PARAM_ERROR);
        }
        String content = bookContentCacheManager.getBookContent(chapterId);
        BookInfoRespDto bookInfo = bookInfoCacheManager.getBookInfo(bookChapter.getBookId());

        return RestResp.ok(BookContentAboutRespDto.builder()
            .bookInfo(bookInfo)
            .chapterInfo(bookChapter)
            .bookContent(Objects.isNull(content) ? "" : content)
            .build());
    }
}
