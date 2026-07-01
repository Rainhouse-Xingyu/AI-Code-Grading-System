package com.rainexis.backend.service.impl;

import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.service.ITUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现类
 * 继承 MyBatis-Plus ServiceImpl，提供用户相关的数据访问能力
 */
@Service
public class TUserServiceImpl extends ServiceImpl<TUserMapper, TUser> implements ITUserService {

}
