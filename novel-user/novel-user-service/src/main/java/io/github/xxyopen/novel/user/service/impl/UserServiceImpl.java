package io.github.xxyopen.novel.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.xxyopen.novel.common.auth.JwtUtils;
import io.github.xxyopen.novel.common.constant.CommonConsts;
import io.github.xxyopen.novel.common.constant.DatabaseConsts;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.config.exception.BusinessException;
import io.github.xxyopen.novel.user.dao.entity.UserBookshelf;
import io.github.xxyopen.novel.user.dao.entity.UserFeedback;
import io.github.xxyopen.novel.user.dao.entity.UserInfo;
import io.github.xxyopen.novel.user.dao.mapper.UserBookshelfMapper;
import io.github.xxyopen.novel.user.dao.mapper.UserFeedbackMapper;
import io.github.xxyopen.novel.user.dao.mapper.UserInfoMapper;
import io.github.xxyopen.novel.user.dto.req.UserInfoUptReqDto;
import io.github.xxyopen.novel.user.dto.req.UserLoginReqDto;
import io.github.xxyopen.novel.user.dto.req.UserRegisterReqDto;
import io.github.xxyopen.novel.user.dto.resp.UserInfoRespDto;
import io.github.xxyopen.novel.user.dto.resp.UserLoginRespDto;
import io.github.xxyopen.novel.user.dto.resp.UserRegisterRespDto;
import io.github.xxyopen.novel.user.manager.redis.VerifyCodeManager;
import io.github.xxyopen.novel.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 会员模块服务实现：注册、登录、个人资料、意见反馈、书架状态，
 * 以及供 book 服务批量补全评论者昵称/头像的内部接口。
 *
 * <p><b>认证方案</b>：登录/注册成功后签发 JWT（HS256，7 天有效），之后每次请求由
 * 各微服务自己的 AuthInterceptor 解析 token 并校验用户状态，把 userId 放进 UserHolder。
 * 网关只做路由和跨域，<b>不做认证</b>——这是本项目与"网关统一鉴权"方案的区别，
 * 面试要能讲清取舍（见 doc/面试讲解.md）。
 *
 * <p><b>验证码跨服务约定</b>：图片验证码由 novel-resource 生成，写 Redis；
 * 校验却在 novel-user 完成。两个服务靠约定同一个 Key 前缀
 * {@code Cache::Novel::imgVerifyCodeCache::<sessionId>} 共享数据，
 * 这是微服务拆分后"不共享内存、只共享中间件"的典型例子。
 *
 * <p><b>安全边界（主动说明，别等面试官戳）</b>：密码用 MD5 + 固定盐，
 * 只是学习项目的简化；生产应使用 BCrypt/Argon2 并给每个用户随机盐。
 * 当前没有登录失败次数限制、没有验证码防刷、没有 token 刷新与主动吊销。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserInfoMapper userInfoMapper;

    private final VerifyCodeManager verifyCodeManager;

    private final UserFeedbackMapper userFeedbackMapper;

    private final UserBookshelfMapper userBookshelfMapper;

    /**
     * 注册：验验证码 → 查重 → 落库 → 作废验证码 → 签发 JWT。
     *
     * <p>「先查重再插入」中间有竞态窗口，所以不能只靠应用层判断：
     * user_info 上有 uk_username 唯一键，两个并发请求都通过查重时，
     * 后插入的那个会抛 DuplicateKeyException，这里转成明确的「用户名已存在」业务错误。
     * 单表 insert 依赖数据库自动提交即可，不需要额外包一层本地事务。
     */
    @Override
    public RestResp<UserRegisterRespDto> register(UserRegisterReqDto dto) {
        // 1) 图形验证码：从 Redis 按 sessionId 取出正确答案比对。
        //    校验与生成不在同一个服务，靠共享 Redis Key 约定协作。
        if (!verifyCodeManager.imgVerifyCodeOk(dto.getSessionId(), dto.getVelCode())) {
            throw new BusinessException(ErrorCodeEnum.USER_VERIFY_CODE_ERROR);
        }

        // 2) 查重（应用层快速失败，DB 唯一键才是最终保证）
        QueryWrapper<UserInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.UserInfoTable.COLUMN_USERNAME, dto.getUsername())
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        if (userInfoMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ErrorCodeEnum.USER_NAME_EXIST);
        }

        // 3) 落库。昵称默认取用户名；salt 固定为 "0" 是学习项目简化，
        //    真实项目应为每个用户生成随机盐并使用慢哈希。
        UserInfo userInfo = new UserInfo();
        userInfo.setPassword(
            DigestUtils.md5DigestAsHex(dto.getPassword().getBytes(StandardCharsets.UTF_8)));
        userInfo.setUsername(dto.getUsername());
        userInfo.setNickName(dto.getUsername());
        userInfo.setCreateTime(LocalDateTime.now());
        userInfo.setUpdateTime(LocalDateTime.now());
        userInfo.setSalt("0");
        try {
            userInfoMapper.insert(userInfo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCodeEnum.USER_NAME_EXIST);
        }

        // 4) 验证码一次性使用，注册成功后立刻删除，防止同一个验证码被重放
        verifyCodeManager.removeImgVerifyCode(dto.getSessionId());

        // 5) 注册即登录：直接签发 JWT，前端无需再调一次 login
        return RestResp.ok(
            UserRegisterRespDto.builder()
                .token(JwtUtils.generateToken(userInfo.getId(), SystemConfigConsts.NOVEL_FRONT_KEY))
                .uid(userInfo.getId())
                .build()
        );
    }

    /**
     * 登录：按用户名查 → 比对 MD5 摘要 → 签发 JWT。
     *
     * <p>注意这里不查 Redis 用户缓存，直接读 DB：登录是低频操作，
     * 且必须拿到最新的 password / status，读缓存反而有一致性风险。
     * AuthInterceptor 里读的 userInfoCache 只用于「token 有效且用户仍存在」的快速判定。
     */
    @Override
    public RestResp<UserLoginRespDto> login(UserLoginReqDto dto) {
        QueryWrapper<UserInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.UserInfoTable.COLUMN_USERNAME, dto.getUsername())
            .last(DatabaseConsts.SqlEnum.LIMIT_1.getSql());
        UserInfo userInfo = userInfoMapper.selectOne(queryWrapper);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.USER_ACCOUNT_NOT_EXIST);
        }
        if (!Objects.equals(userInfo.getPassword(),
            DigestUtils.md5DigestAsHex(dto.getPassword().getBytes(StandardCharsets.UTF_8)))) {
            throw new BusinessException(ErrorCodeEnum.USER_PASSWORD_ERROR);
        }
        return RestResp.ok(UserLoginRespDto.builder()
            .token(JwtUtils.generateToken(userInfo.getId(), SystemConfigConsts.NOVEL_FRONT_KEY))
            .uid(userInfo.getId())
            .nickName(userInfo.getNickName()).build());
    }

    /** 提交意见反馈：userId 由拦截器从 JWT 解析后传入，不接受前端伪造 */
    @Override
    public RestResp<Void> saveFeedback(Long userId, String content) {
        UserFeedback userFeedback = new UserFeedback();
        userFeedback.setUserId(userId);
        userFeedback.setContent(content);
        userFeedback.setCreateTime(LocalDateTime.now());
        userFeedback.setUpdateTime(LocalDateTime.now());
        userFeedbackMapper.insert(userFeedback);
        return RestResp.ok();
    }

    /** 修改个人资料：只更新传入的非空字段（MyBatis-Plus updateById 默认忽略 null） */
    @Override
    public RestResp<Void> updateUserInfo(UserInfoUptReqDto dto) {
        UserInfo userInfo = new UserInfo();
        userInfo.setId(dto.getUserId());
        userInfo.setNickName(dto.getNickName());
        userInfo.setUserPhoto(dto.getUserPhoto());
        userInfo.setUserSex(dto.getUserSex());
        userInfoMapper.updateById(userInfo);
        return RestResp.ok();
    }

    /** 删除反馈：WHERE 同时带 id 和 userId，防止删掉别人的反馈（水平越权） */
    @Override
    public RestResp<Void> deleteFeedback(Long userId, Long id) {
        QueryWrapper<UserFeedback> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.CommonColumnEnum.ID.getName(), id)
            .eq(DatabaseConsts.UserFeedBackTable.COLUMN_USER_ID, userId);
        userFeedbackMapper.delete(queryWrapper);
        return RestResp.ok();
    }

    /**
     * 查询「某本书是否已在书架」。
     *
     * <p>业务边界：本微服务版<b>只实现了查询</b>，没有加入/移出书架的写接口，
     * user_bookshelf 表的数据需由运营或单体版写入。面试要如实说明，不要说成完整书架功能。
     */
    @Override
    public RestResp<Integer> getBookshelfStatus(Long userId, String bookId) {
        QueryWrapper<UserBookshelf> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(DatabaseConsts.UserBookshelfTable.COLUMN_USER_ID, userId)
            .eq(DatabaseConsts.UserBookshelfTable.COLUMN_BOOK_ID, bookId);
        return RestResp.ok(
            userBookshelfMapper.selectCount(queryWrapper) > 0
                ? CommonConsts.YES
                : CommonConsts.NO
        );
    }

    /** 个人中心资料查询 */
    @Override
    public RestResp<UserInfoRespDto> getUserInfo(Long userId) {
        UserInfo userInfo = userInfoMapper.selectById(userId);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.USER_ACCOUNT_NOT_EXIST);
        }
        return RestResp.ok(UserInfoRespDto.builder()
            .nickName(userInfo.getNickName())
            .userSex(userInfo.getUserSex())
            .userPhoto(userInfo.getUserPhoto())
            .build());
    }

    /**
     * 内部接口（/api/inner/user，不经过网关）：按 ID 列表批量返回用户昵称/头像。
     *
     * <p>被 book 服务展示评论列表时通过 Feign 调用。一次 IN 查询拿回全部用户，
     * 上游再做内存 join，避免每条评论都远程调一次（N+1）。
     * 空列表要提前返回，否则生成的 {@code in ()} 会是非法 SQL。
     */
    @Override
    public RestResp<List<UserInfoRespDto>> listUserInfoByIds(List<Long> userIds) {
        if (Objects.isNull(userIds) || userIds.isEmpty()) {
            return RestResp.ok(Collections.emptyList());
        }
        QueryWrapper<UserInfo> queryWrapper = new QueryWrapper<>();
        queryWrapper.in(DatabaseConsts.CommonColumnEnum.ID.getName(), userIds);
        return RestResp.ok(
            userInfoMapper.selectList(queryWrapper).stream().map(v -> UserInfoRespDto.builder()
                .id(v.getId())
                .username(v.getUsername())
                .userPhoto(v.getUserPhoto())
                .build()).collect(Collectors.toList()));
    }
}
