package com.rainexis.backend.service.impl;

import com.rainexis.backend.entity.TFile;
import com.rainexis.backend.mapper.TFileMapper;
import com.rainexis.backend.service.ITFileService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TFileServiceImpl extends ServiceImpl<TFileMapper, TFile> implements ITFileService {

}
