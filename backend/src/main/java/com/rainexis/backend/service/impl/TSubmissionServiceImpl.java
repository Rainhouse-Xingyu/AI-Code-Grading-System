package com.rainexis.backend.service.impl;

import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.service.ITSubmissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TSubmissionServiceImpl extends ServiceImpl<TSubmissionMapper, TSubmission> implements ITSubmissionService {

}
