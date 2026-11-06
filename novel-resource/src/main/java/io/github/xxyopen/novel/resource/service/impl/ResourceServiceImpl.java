package io.github.xxyopen.novel.resource.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.xxyopen.novel.common.constant.ErrorCodeEnum;
import io.github.xxyopen.novel.common.constant.SystemConfigConsts;
import io.github.xxyopen.novel.common.resp.RestResp;
import io.github.xxyopen.novel.config.exception.BusinessException;
import io.github.xxyopen.novel.resource.dto.resp.ImgVerifyCodeRespDto;
import io.github.xxyopen.novel.resource.manager.redis.VerifyCodeManager;
import io.github.xxyopen.novel.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 资源（图片/验证码）相关服务实现类。
 *
 * <p>职责：承担两个都属「资源」但互不相干的能力——图形验证码的生成、以及图片上传落盘。
 * 前者只写 Redis 不落盘，后者只写本地文件系统不碰 Redis。
 *
 * <p>设计取舍：图片不入库、不接对象存储，直接写到 ${novel.file.upload.path} 指定的本地
 * 目录，再由 FileInterceptor 把 /image/** 请求以字节流直接回吐，省掉一次 Controller
 * 转发与序列化开销；代价是文件与当前实例绑定，多实例部署需要共享存储或改接 CDN，这是
 * 单体/小规模部署下有意做的简化。
 *
 * <p>跨服务约定：验证码的「答案」由本服务写入 Redis，却由 user 服务在注册时读取校验，
 * 两边靠同一个 CacheConsts 常量和 sessionId 对齐，详见 {@link #getImgVerifyCode()} 与
 * VerifyCodeManager。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceServiceImpl implements ResourceService {

    /**
     * 允许上传的图片扩展名白名单（小写）。
     *
     * <p>第一道粗筛：扩展名明显不对的直接拒绝，不落盘；真正「是不是图片」由写盘后的
     * ImageIO.read 解码校验兜底。两层配合，防止只改后缀的伪装文件。
     */
    private static final Set<String> IMG_EXT_WHITE_LIST =
        Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp");

    private final VerifyCodeManager verifyCodeManager;

    @Value("${novel.file.upload.path}")
    private String fileUploadPath;

    /**
     * 生成图形验证码。
     *
     * <p>业务目的：注册等敏感操作前，先给前端一张验证码图片，同时把「答案」写进 Redis。
     * 这里先造一个全局唯一的 sessionId（32 位 UUID），它有两个作用：① 作为本次验证码的
     * 身份标识返回给前端；② 拼进 Redis Key，把答案与这次会话绑定。
     *
     * <p>跨服务约定（重点）：本服务只负责「生成 + 存」，不负责校验。前端提交注册时，把
     * sessionId 和用户输入的验证码一起交给 user 服务；user 服务的 VerifyCodeManager 用
     * 完全相同的规则 CacheConsts.IMG_VERIFY_CODE_CACHE_KEY + sessionId 去 Redis 读取比对，
     * 用完即删。两个服务之间没有直接调用，全靠「同一 Key 前缀 + 同一 sessionId + 同一套
     * Redis」这个隐式契约耦合，因此 Key 的拼法不能在任一侧单独改动，答案的 5 分钟 TTL
     * 也由写入方（VerifyCodeManager）统一控制。
     *
     * <p>边界：sessionId 用 UUID 而不是登录态，因为此刻用户还没登录；图片以 Base64
     * 内联返回，避免为一张临时图片再开一个文件存储/访问接口。
     *
     * @throws IOException 验证码图片生成失败
     * @return sessionId + Base64 编码的验证码图片
     */
    @Override
    public RestResp<ImgVerifyCodeRespDto> getImgVerifyCode() throws IOException {
        String sessionId = IdWorker.get32UUID();
        return RestResp.ok(ImgVerifyCodeRespDto.builder()
            .sessionId(sessionId)
            .img(verifyCodeManager.genImgVerifyCode(sessionId))
            .build());
    }

    /**
     * 上传图片并落盘，返回可供拦截器直出的访问路径。
     *
     * <p>关键步骤与设计取舍：
     * <ul>
     *   <li>按 年/月/日 分片：单目录下文件过多会拖慢文件系统检索，按日期拆目录也方便
     *       按天归档/清理；</li>
     *   <li>UUID 重命名：彻底丢弃原始文件名，避免重名覆盖、中文/空格导致的 URL 问题，
     *       更关键的是不让用户可控的文件名参与存储路径，杜绝路径穿越；</li>
     *   <li>先写盘、再用 ImageIO.read 解码校验：MultipartFile 的 contentType 和后缀都由
     *       客户端提供、不可信，只有真能被图像解码器读出来才算图片；校验不通过立刻删掉
     *       刚写的文件并抛业务异常，既不留垃圾，也不留可执行的伪装文件；</li>
     *   <li>返回 /image/yyyy/MM/dd/uuid.ext 形式的相对路径，正好落在 FileInterceptor 拦截
     *       的 /image/** 前缀内，由拦截器直接把字节流写回响应。</li>
     * </ul>
     *
     * <p>边界：拿不到原始文件名按上传异常拒绝；没有扩展名、扩展名不在白名单按类型不匹配
     * 拒绝。lastIndexOf(".") 可能为 -1，直接 substring 会越界，必须显式处理。
     *
     * @param file 需要上传的图片
     * @return 图片访问路径
     */
    @SneakyThrows
    @Override
    public RestResp<String> uploadImage(MultipartFile file) {
        LocalDateTime now = LocalDateTime.now();
        // 按 年/月/日 分片，避免单目录文件过多，也便于按天归档
        String savePath =
            SystemConfigConsts.IMAGE_UPLOAD_DIRECTORY
                + now.format(DateTimeFormatter.ofPattern("yyyy")) + File.separator
                + now.format(DateTimeFormatter.ofPattern("MM")) + File.separator
                + now.format(DateTimeFormatter.ofPattern("dd"));
        String oriName = file.getOriginalFilename();
        // 边界1：拿不到原始文件名（部分客户端/伪造请求不带文件名）；assert 在生产环境默认关闭，不可依赖
        if (oriName == null || oriName.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.USER_UPLOAD_FILE_ERROR);
        }
        // 边界2：没有扩展名（lastIndexOf 返回 -1）或以点结尾，substring 会越界
        int dotIndex = oriName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == oriName.length() - 1) {
            throw new BusinessException(ErrorCodeEnum.USER_UPLOAD_FILE_TYPE_NOT_MATCH);
        }
        // 边界3：扩展名白名单粗筛，统一转小写后比较
        String ext = oriName.substring(dotIndex).toLowerCase(Locale.ROOT);
        if (!IMG_EXT_WHITE_LIST.contains(ext)) {
            throw new BusinessException(ErrorCodeEnum.USER_UPLOAD_FILE_TYPE_NOT_MATCH);
        }
        // UUID 重命名，用户可控的原始文件名不参与最终存储路径
        String saveFileName = IdWorker.get32UUID() + ext;
        File saveFile = new File(fileUploadPath + savePath, saveFileName);
        if (!saveFile.getParentFile().exists()) {
            boolean isSuccess = saveFile.getParentFile().mkdirs();
            if (!isSuccess) {
                throw new BusinessException(ErrorCodeEnum.USER_UPLOAD_FILE_ERROR);
            }
        }
        file.transferTo(saveFile);
        // 写完再用 ImageIO 解码校验：能解码才是真图片，防「改后缀」的伪装文件
        if (Objects.isNull(ImageIO.read(saveFile))) {
            // 上传的文件不是图片
            Files.delete(saveFile.toPath());
            throw new BusinessException(ErrorCodeEnum.USER_UPLOAD_FILE_TYPE_NOT_MATCH);
        }
        return RestResp.ok(savePath + File.separator + saveFileName);
    }

}
