package cn.edu.seig.jojomusic.service;

import cn.edu.seig.jojomusic.model.dto.AdminDTO;
import cn.edu.seig.jojomusic.model.entity.Admin;
import cn.edu.seig.jojomusic.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author timi669
 * @since 2025-01-09
 */
public interface IAdminService extends IService<Admin> {

    // 管理员注册
    Result register(AdminDTO adminDTO);

    // 管理员登录
    Result login(AdminDTO adminDTO);

    // 退出登录
    Result logout(String token);
}
