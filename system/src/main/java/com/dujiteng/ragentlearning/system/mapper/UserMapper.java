package com.dujiteng.ragentlearning.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dujiteng.ragentlearning.system.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserDO> { }
