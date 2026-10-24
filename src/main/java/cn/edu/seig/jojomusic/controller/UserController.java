package cn.edu.seig.jojomusic.controller;


import cn.edu.seig.jojomusic.constant.MessageConstant;
import cn.edu.seig.jojomusic.model.dto.*;
import cn.edu.seig.jojomusic.model.vo.UserVO;
import cn.edu.seig.jojomusic.result.Result;
import cn.edu.seig.jojomusic.service.IUserService;
import cn.edu.seig.jojomusic.service.MinioService;
import cn.edu.seig.jojomusic.util.BindingResultUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.hibernate.validator.constraints.URL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author timi669
 * @since 2025-01-09
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private IUserService userService;
    @Autowired
    private MinioService minioService;

    /**
     * 发送验证码
     *
     * @param email 邮箱
     * @return 结果
     */
    @GetMapping("/sendVerificationCode")
    public Result sendVerificationCode(@RequestParam @Email String email) {
        return userService.sendVerificationCode(email);
    }

    /**
     * 注册
     *
     * @param userRegisterDTO 用户注册信息
     * @param bindingResult   绑定结果
     * @return 结果
     */
    @PostMapping("/register")
    public Result register(@RequestBody @Valid UserRegisterDTO userRegisterDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        // 验证验证码是否正确
        boolean isCodeValid = userService.verifyVerificationCode(userRegisterDTO.getEmail(), userRegisterDTO.getVerificationCode());
        if (!isCodeValid) {
            return Result.error(MessageConstant.VERIFICATION_CODE + MessageConstant.INVALID);
        }

        return userService.register(userRegisterDTO);
    }

    /**
     * 登录
     *
     * @param userLoginDTO  用户登录信息
     * @param bindingResult 绑定结果
     * @return 结果
     */
    @PostMapping("/login")
    public Result login(@RequestBody @Valid UserLoginDTO userLoginDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.login(userLoginDTO);
    }

    /**
     * 获取用户信息
     *
     * @return 结果
     */
    @GetMapping("/getUserInfo")
    public Result<UserVO> getUserInfo() {
        return userService.userInfo();
    }

    /**
     * 更新用户信息
     *
     * @param userDTO 用户信息
     * @return 结果
     */
    @PutMapping("/updateUserInfo")
    public Result updateUserInfo(@RequestBody @Valid UserDTO userDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.updateUserInfo(userDTO);
    }

    /**
     * 更新用户头像
     *
     * @param avatar 头像
     * @return 结果
     */
    @PatchMapping("/updateUserAvatar")
    public Result updateUserAvatar(@RequestParam("avatar") MultipartFile avatar) {
        String avatarUrl = minioService.uploadFile(avatar, "users");  // 上传到 users 目录
        return userService.updateUserAvatar(avatarUrl);
    }

    /**
     * 上传自定义背景（壁纸）到对象存储。
     *
     * <p>与头像不同，壁纸是"个人偏好"类资源：服务端只负责存储并返回可访问 URL，
     * 是否使用、如何裁剪由客户端决定（壁纸本体也支持只存在本地浏览器）。
     *
     * <p>为避免把对象存储当图床滥用，这里做与音频上传同级别的校验：
     * <ul>
     *   <li>非空校验</li>
     *   <li>大小上限 20MB</li>
     *   <li>允许的图片 MIME 白名单</li>
     *   <li>魔数（文件头）与实际类型交叉校验，防止改扩展名绕过</li>
     * </ul>
     *
     * @param background 背景图片
     * @return 结果，data 为图片可访问 URL
     */
    @PatchMapping("/uploadUserBackground")
    public Result uploadUserBackground(@RequestParam("background") MultipartFile background) {
        if (background == null || background.isEmpty()) {
            return Result.error("请选择要上传的背景图片");
        }
        if (background.getSize() > MAX_BACKGROUND_SIZE) {
            return Result.error("背景图片过大，请压缩到 20MB 以内再上传");
        }

        String contentType = resolveImageContentType(background);
        if (contentType == null) {
            return Result.error("仅支持 JPG / PNG / WebP / GIF 格式的图片");
        }

        String url = minioService.uploadFile(background, "backgrounds", contentType);
        return Result.success("背景上传成功", url);
    }

    /** 背景图片大小上限：20MB */
    private static final long MAX_BACKGROUND_SIZE = 20L * 1024 * 1024;

    /** 允许的图片类型 -> 期望的文件头 */
    private static final java.util.Map<String, String[]> ALLOWED_IMAGE_TYPES = java.util.Map.of(
            "image/jpeg", new String[]{"FFD8FF"},
            "image/png", new String[]{"89504E47"},
            "image/webp", new String[]{"52494646"},
            "image/gif", new String[]{"47494638"}
    );

    /**
     * 校验并解析图片真实类型：MIME 声明必须在白名单内，
     * 且文件头魔数要与声明匹配，否则视为伪造类型返回 null。
     *
     * @param file 上传文件
     * @return 规范化后的 Content-Type；不合法时返回 null
     */
    private String resolveImageContentType(MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || !ALLOWED_IMAGE_TYPES.containsKey(declared)) {
            return null;
        }
        try (java.io.InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4) {
                return null;
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : head) {
                hex.append(String.format("%02X", b));
            }
            String actual = hex.toString();
            for (String magic : ALLOWED_IMAGE_TYPES.get(declared)) {
                if (actual.startsWith(magic)) {
                    return declared;
                }
            }
            // WebP 的魔数是 RIFF....WEBP，前 4 字节为 RIFF 即可判定
            return null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * 更新用户密码
     *
     * @param userPasswordDTO 用户密码信息
     * @param token           认证token
     * @return 结果
     */
    @PatchMapping("/updateUserPassword")
    public Result updateUserPassword(@RequestBody @Valid UserPasswordDTO userPasswordDTO,
                                     @RequestHeader("Authorization") String token, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        return userService.updateUserPassword(userPasswordDTO, token);
    }

    /**
     * 重置用户密码
     *
     * @param userResetPasswordDTO 用户密码信息
     * @return 结果
     */
    @PatchMapping("/resetUserPassword")
    public Result resetUserPassword(@RequestBody @Valid UserResetPasswordDTO userResetPasswordDTO, BindingResult bindingResult) {
        // 校验失败时，返回错误信息
        String errorMessage = BindingResultUtil.handleBindingResultErrors(bindingResult);
        if (errorMessage != null) {
            return Result.error(errorMessage);
        }

        // 验证验证码是否正确
        boolean isCodeValid = userService.verifyVerificationCode(userResetPasswordDTO.getEmail(), userResetPasswordDTO.getVerificationCode());
        if (!isCodeValid) {
            return Result.error(MessageConstant.VERIFICATION_CODE + MessageConstant.INVALID);
        }

        return userService.resetUserPassword(userResetPasswordDTO);
    }

    /**
     * 登出
     *
     * @param token 认证token
     * @return 结果
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("Authorization") String token) {
        return userService.logout(token);
    }

    /**
     * 注销账号
     *
     * @return 结果
     */
    @DeleteMapping("/deleteAccount")
    public Result deleteAccount() {
        return userService.deleteAccount();
    }

}
