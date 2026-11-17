package com.askfirst.worker;

import com.askfirst.common.job.Job;

/** Hàng đợi jobs: worker chỉ cần tạo job followup trễ 15 phút. */
public interface JobQueue {

    void enqueue(Job job, int delaySeconds);
}
