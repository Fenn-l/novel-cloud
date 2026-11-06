package io.github.xxyopen.novel.resource.util;

import lombok.experimental.UtilityClass;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Random;

/**
 * 图片验证码工具类（纯绘制，无状态、无存储）。
 *
 * <p>职责：生成随机数字码，把码画成带干扰线、随机字色与逐字位移的 JPEG 图片并转
 * Base64。它只负责「长得像一张验证码」，不负责答案存哪、多久失效——那属于
 * VerifyCodeManager 与 Redis 的职责，刻意分离后便于单独替换绘制算法，或将来接入滑块/
 * 点选式验证码而不动存储与校验链路。
 *
 * <p>设计取舍：用 @UtilityClass（静态方法 + 私有构造），因为绘制过程无状态、不需要
 * Spring 管理；字符集只取数字，降低用户识别与输入成本；4 位长度配合 5 分钟 TTL 与
 * 一次性使用（user 服务校验后删除），足以挡住脚本的批量试探。
 *
 * <p>边界：输出是 JPEG 的 Base64（不含 data:image 前缀，由前端自行拼接）；干扰线数量、
 * 字号与位移幅度固定，属于「抗 OCR」与「人眼可读」之间的折中，不做运行时动态难度调整。
 *
 * @author xiongxiaoyang
 * @date 2022/5/17
 */
@UtilityClass
public class ImgVerifyCodeUtils {

    /**
     * 验证码字符集：只用数字，避开易混淆的字母，降低识别与输入成本
     */
    private final String randNumber = "0123456789";

    /**
     * 图片宽：配合前端验证码输入框比例定死，避免图片被拉伸变形
     */
    private final int width = 100;

    /**
     * 图片高：同上，与宽一起决定画布尺寸
     */
    private final int height = 38;

    /**
     * 随机源：字色、字符位移、干扰线坐标共用；工具类单例持有即可，无需线程安全容器
     */
    private final Random random = new Random();

    /**
     * 获取绘制字体。
     *
     * <p>用固定字族与字号，保证不同服务器上生成的图片字形一致、字符不会超出画布；
     * 只用一套内置字体，避免依赖运行环境是否安装了中文字体。
     */
    private Font getFont() {
        return new Font("Fixed", Font.PLAIN, 23);
    }


    /**
     * 把验证码画成图片并返回 Base64。
     *
     * <p>业务目的：给前端一张可直接渲染的图，同时用干扰线、随机字色和逐字位移抬高
     * OCR 识别成本。步骤：建画布 → 铺背景 → 画干扰线 → 逐字符绘制 → 转 JPEG → Base64。
     *
     * <p>边界：只接收已生成好的验证码字符串，不生成、不校验答案；图片里不含答案明文，
     * 答案由 VerifyCodeManager 单独写 Redis。
     *
     * @param verifyCode 已生成的验证码字符串
     * @return Base64 编码的 JPEG 图片（不含 data:image 前缀）
     * @throws IOException 图片编码失败
     */
    public String genVerifyCodeImg(String verifyCode) throws IOException {
        // BufferedImage类是具有缓冲区的Image类,Image类是用于描述图像信息的类
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
        // 产生Image对象的Graphics对象,改对象可以在图像上进行各种绘制操作
        Graphics g = image.getGraphics();
        //图片大小
        g.fillRect(0, 0, width, height);
        //字体大小
        //字体颜色
        g.setColor(new Color(204, 204, 204));
        // 绘制干扰线：叠加多条随机线形成背景噪声
        int lineSize = 40;
        for (int i = 0; i <= lineSize; i++) {
            drawLine(g);
        }
        // 绘制随机字符
        drawString(g, verifyCode);
        g.dispose();
        //将图片转换成Base64字符串
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", stream);
        return Base64.getEncoder().encodeToString(stream.toByteArray());
    }

    /**
     * 逐字符绘制验证码。
     *
     * <p>每个字符单独设随机颜色并做小幅平移，让字符不与基线整齐对齐，提高机器分割与
     * 识别的难度；字符横向位置按 13 * i 固定，保证人眼仍能读清、不会互相重叠。
     */
    private void drawString(Graphics g, String verifyCode) {
        for (int i = 1; i <= verifyCode.length(); i++) {
            g.setFont(getFont());
            g.setColor(new Color(random.nextInt(101), random.nextInt(111), random
                .nextInt(121)));
            g.translate(random.nextInt(3), random.nextInt(3));
            g.drawString(String.valueOf(verifyCode.charAt(i - 1)), 13 * i, 23);
        }
    }

    /**
     * 绘制一条随机干扰线。
     *
     * <p>起点与长度都在画布范围内随机，多条叠加后形成背景噪声，用来对抗「二值化 + OCR」
     * 这类简单识别；数量由 genVerifyCodeImg 固定，是抗机器与人眼可读之间的折中。
     */
    private void drawLine(Graphics g) {
        int x = random.nextInt(width);
        int y = random.nextInt(height);
        int xl = random.nextInt(13);
        int yl = random.nextInt(15);
        g.drawLine(x, y, x + xl, y + yl);
    }

    /**
     * 生成指定位数的纯数字验证码。
     *
     * <p>业务目的：只取 0-9，避开易混淆字符，减少用户输入错误；位数由调用方决定
     * （当前生产用 4 位），本方法不感知 TTL 与校验规则。
     *
     * <p>边界：num 应为正数；num 为 0 时返回空串，由内部调用方保证不传非法值。
     */
    public String getRandomVerifyCode(int num) {
        int randNumberSize = randNumber.length();
        StringBuilder verifyCode = new StringBuilder();
        for (int i = 0; i < num; i++) {
            String rand = String.valueOf(randNumber.charAt(random.nextInt(randNumberSize)));
            verifyCode.append(rand);
        }
        return verifyCode.toString();
    }

}
