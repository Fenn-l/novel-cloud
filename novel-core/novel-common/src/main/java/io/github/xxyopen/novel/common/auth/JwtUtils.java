package io.github.xxyopen.novel.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;

/**
 * JWT（登录令牌）生成与解析工具。
 *
 * <p>【负责的业务】登录成功后签发一枚令牌，后续请求由各服务的拦截器解析出 userId，
 * 作为「当前登录人」的唯一凭据，从而不依赖 Session、也不需要每个接口反复查库校验身份。
 *
 * <p>【为什么用 HS256 对称签名】签发和验签使用同一把密钥（SECRET），实现简单、性能高，
 * 适合共享密钥的微服务集群；代价是任何拿到密钥的一方都能伪造令牌，
 * 所以生产环境应换成非对称签名（RS256：私钥签发、公钥验签），或至少把密钥放进
 * 配置中心/环境变量并支持轮换。signWith(Key) 会根据密钥长度自动选择 HS256/HS384/HS512，
 * 这里 32 字节的密钥对应 HS256。
 *
 * <p>【密钥硬编码是学习项目的简化，面试要主动讲】SECRET 直接写在代码里，
 * 根因是本类被 {@code @UtilityClass} 标注：Lombok 会把它编译成 final 类 + 私有构造 +
 * 全静态成员，Spring 无法对静态方法/静态字段做 {@code @Value} 依赖注入，
 * 因此这里根本 inject 不了配置项（原文件里那几个 Spring import 也是无用的）。
 * 想让密钥可配置，就必须去掉 {@code @UtilityClass}，改成 {@code @Component} 单例 Bean + 实例方法。
 *
 * <p>【systemKeyHeader 的作用】在 JWT 头部塞一个自定义字段 systemKey，解析时要求它与
 * 调用方传入的系统标识一致。这样前台、作家后台即便共用同一把签名密钥，也能防止「拿 A 端签发的
 * token 去访问 B 端接口」，属于轻量级的令牌用途隔离；它不是真正的安全边界，
 * 真正防篡改靠的是签名校验。
 *
 * <p>【边界与已知缺陷，面试主动讲】
 * 1. 令牌一旦签发无法主动吊销：改密码、封号、退出登录都不能让其即时失效，只能等自然过期；
 * 2. 没有 refresh token / 滑动续期机制，7 天到期后用户必须重新登录；
 * 3. payload 只放 userId，不含角色与权限，鉴权仍需回查缓存或数据库；
 * 4. 解析失败统一返回 {@code null}，由上层拦截器转成「登录已过期」，调用方必须判空。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@UtilityClass
@Slf4j
public class JwtUtils {

    /** JWT 签名密钥（HS256 要求至少 256 bit / 32 字节，这里 32 个 ASCII 字符刚好满足） */
    private static final String SECRET = "E66559580A1ADF48CDD928516062F12E";

    /** 自定义头部字段名：用于标识令牌所属系统 */
    private static final String HEADER_SYSTEM_KEY = "systemKeyHeader";

    /**
     * 令牌有效期：7 天。
     * 这个窗口既是用户体验（免频繁登录），也是「无法吊销」缺陷的兜底——过期是令牌唯一自动失效的方式。
     */
    private static final long TOKEN_EXPIRE_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /**
     * 签发令牌：把 userId 放进标准 subject，并写入签发时间（iat）与过期时间（exp）。
     * 过期时间由服务端集中计算并参与签名，客户端改不动；校验工作交给 jjwt 在解析时完成。
     *
     * @param uid       用户ID
     * @param systemKey 系统标识，写入头部用于隔离不同端的令牌
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(Long uid, String systemKey) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .setHeaderParam(HEADER_SYSTEM_KEY, systemKey)
            .setSubject(uid.toString())
            .setIssuedAt(new Date(now))
            .setExpiration(new Date(now + TOKEN_EXPIRE_MILLIS))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    /**
     * 解析并校验令牌：验签、过期校验都由 jjwt 的 parseClaimsJws 一并完成，
     * 签名不符或已过期会抛 {@link JwtException}（过期是它的子类 ExpiredJwtException）。
     *
     * @param token     JWT
     * @param systemKey 期望的系统标识，与头部不一致视为跨端冒用，同样判为无效
     * @return 用户ID；令牌无效/过期/系统不匹配时返回 {@code null}
     */
    public Long parseToken(String token, String systemKey) {
        Jws<Claims> claimsJws;
        try {
            claimsJws = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseClaimsJws(token);
            // 验签通过后再核对系统标识，防止同一密钥签发的令牌被跨端使用
            if (Objects.equals(claimsJws.getHeader().get(HEADER_SYSTEM_KEY), systemKey)) {
                return Long.parseLong(claimsJws.getBody().getSubject());
            }
        } catch (JwtException e) {
            // 过期、被篡改、格式错误一律归为「不可信」，不向调用方泄漏具体失败原因
            log.warn("JWT解析失败:{}", token);
        }
        return null;
    }

}
