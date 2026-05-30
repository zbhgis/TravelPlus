package com.travelplus.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.travelplus.dto.Result;
import com.travelplus.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 【图片上传的实现】图片上传/删除统一入口：游记图、用户资料图、图片删除
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("upload")，挂 /upload/** 前缀；匿名可访问（在登录拦截器 exclude 列表）。
 * - 上传方式：multipart/form-data，字段名固定 file；返回 data=相对路径（如 /blogs/{d1}/{d2}/{uuid}.jpg，
 *   不带 /imgs 前缀），前端展示时拼 IMG_BASE + /imgs + 相对路径。
 * - 存储目录：SystemConstants.IMAGE_UPLOAD_DIR（MvcConfig 已把 /imgs/** 静态映射到该目录），
 *   文件名用 UUID + hash 两级散列目录，避免文件堆积在单目录且文件名不可预测。
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /**
     * 【图片上传的实现】上传游记图片，存储到 imgs/blogs/ 目录
     * <p>
     * 实现细节：
     * - 匿名可访问：/upload/note 在登录拦截器 exclude 列表放行，无需 token。
     * - multipart 字段 file 接收图片；createNewFileName 用 UUID.hashCode 拆两级 16 进制散列目录
     *   （/blogs/{d1}/{d2}/{uuid}.{后缀}），目录不存在自动 mkdirs，避免单目录文件过多。
     * - transferTo 落盘到 IMAGE_UPLOAD_DIR；返回 data=相对路径（不带 /imgs 前缀），
     *   前端展示时拼 IMG_BASE + /imgs + 路径。保存失败（IO 异常）抛 RuntimeException"文件上传失败"。
     * @param image multipart 文件字段 file
     * @return data=相对路径（blogs/...）
     */
    @PostMapping("note")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 【图片上传的实现】上传用户资料图（头像/背景图共用），存储到 imgs/user/ 目录
     * <p>
     * 实现细节：
     * - 与 /upload/note 完全同逻辑，仅目录不同：note 存 /blogs/{d1}/{d2}/，本接口存 /user/{d1}/{d2}/。
     * - 头像与背景图共用本接口，前端按业务场景决定把返回路径填到 tb_user.icon 还是 tb_user.bg_image。
     * - 返回 data=相对路径（如 /user/7/a/uuid.jpg，不带 /imgs 前缀），前端展示时拼 IMG_BASE + /imgs + 路径，
     *   与 /upload/note 及既有图片展示规则保持一致。
     * @param image multipart 文件字段 file
     */
    @PostMapping("user")
    public Result uploadUserImage(@RequestParam("file") MultipartFile image) {
        try {
            // 1.获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 2.生成新文件名（目录参数 user → /user/{d1}/{d2}/{uuid}.ext）
            String fileName = createNewFileName(originalFilename, "user");
            // 3.保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 4.返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 【图片删除的实现】删除已上传的图片文件
     * <p>
     * 实现细节：
     * - 匿名可访问：/upload/note/delete 在登录拦截器 exclude 列表放行。
     * - 入参 name 为上传时返回的相对路径（如 /blogs/d1/d2/uuid.jpg），拼 IMAGE_UPLOAD_DIR 后删除；
     *   若目标是目录（误传目录名）则 fail "错误的文件名称"，防止递归删除目录。
     * - FileUtil.del 删除文件（幂等：文件不存在不报错），返回 ok；用于发布游记前的图片回收。
     * @param filename 图片相对路径
     * @return 删除成功返回 ok
     */
    @GetMapping("/note/delete")
    public Result deleteTravelNoteImg(@RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 游记图片默认存 /blogs/ 目录
        return createNewFileName(originalFilename, "blogs");
    }

    private String createNewFileName(String originalFilename, String dir) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在（dir 可为 blogs/user，分别承载游记图与头像/背景图）
        File dirFile = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/{}/{}/{}", dir, d1, d2));
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/{}/{}/{}/{}.{}", dir, d1, d2, name, suffix);
    }
}
