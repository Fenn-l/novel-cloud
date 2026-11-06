package io.github.xxyopen.novel.author.service.impl;

import io.github.xxyopen.novel.author.dao.entity.AuthorInfo;
import io.github.xxyopen.novel.author.dao.mapper.AuthorInfoMapper;
import io.github.xxyopen.novel.author.dto.AuthorInfoDto;
import io.github.xxyopen.novel.author.dto.req.AuthorRegisterReqDto;
import io.github.xxyopen.novel.author.manager.cache.AuthorInfoCacheManager;
import io.github.xxyopen.novel.author.service.AuthorService;
import io.github.xxyopen.novel.common.resp.RestResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 作家模块服务实现：作家注册与身份状态查询。
 *
 * <p><b>作家与用户的关系</b>：作家不是一个独立的账号体系，而是「普通用户申请成为作家」。
 * 作家专区的请求同样携带前台 JWT，由本服务的 AuthInterceptor 解析出 userId，
 * 再查 author_info 确认该 userId 是否已注册作家、拿到 authorId。
 * 所以系统里只有一套 front token，没有独立的作家/管理员令牌（相关常量已删除）。
 *
 * <p><b>业务边界</b>：邀请码、作家收入、收入明细这三张表是单体版遗留结构，
 * 本微服务版没有任何业务逻辑，注册时 inviteCode 固定写 "0" 占位。
 * 发书/发章节由本服务通过 Feign 转发给 novel-book 完成，真正的落库在 book 服务。
 *
 * @author xiongxiaoyang
 * @date 2022/5/23
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorServiceImpl implements AuthorService {

    private final AuthorInfoCacheManager authorInfoCacheManager;

    private final AuthorInfoMapper authorInfoMapper;

    /**
     * 申请成为作家。
     *
     * <p>先查缓存确认没注册过（缓存放 Redis、跨实例共享），再插入 author_info。
     * 插入成功后清作家缓存：AuthorInfoCacheManager 的 @CacheEvict 用了 allEntries=true，
     * 早期版本既不指定 key 也不清全部，默认 key 是 SimpleKey.EMPTY，
     * 而查询用的是 userId，导致「注册完仍查不到作家身份」，是个典型缓存注解误用。
     */
    @Override
    public RestResp<Void> register(AuthorRegisterReqDto dto) {
        AuthorInfoDto author = authorInfoCacheManager.getAuthor(dto.getUserId());
        if (Objects.nonNull(author)) {
            // 幂等：已经是作家就直接成功，不重复插入
            return RestResp.ok();
        }
        AuthorInfo authorInfo = new AuthorInfo();
        authorInfo.setUserId(dto.getUserId());
        authorInfo.setChatAccount(dto.getChatAccount());
        authorInfo.setEmail(dto.getEmail());
        // 邀请码机制未实现（author_code 表无任何业务代码），"0" 仅作非空占位
        authorInfo.setInviteCode("0");
        authorInfo.setTelPhone(dto.getTelPhone());
        authorInfo.setPenName(dto.getPenName());
        authorInfo.setWorkDirection(dto.getWorkDirection());
        authorInfo.setCreateTime(LocalDateTime.now());
        authorInfo.setUpdateTime(LocalDateTime.now());
        authorInfoMapper.insert(authorInfo);
        authorInfoCacheManager.evictAuthorCache();
        return RestResp.ok();
    }

    /**
     * 查询作家审核状态。返回 null 表示该用户还不是作家，
     * 前端据此决定展示「申请入驻」还是「作家后台」。
     */
    @Override
    public RestResp<Integer> getStatus(Long userId) {
        AuthorInfoDto author = authorInfoCacheManager.getAuthor(userId);
        return Objects.isNull(author) ? RestResp.ok(null) : RestResp.ok(author.getStatus());
    }

}
