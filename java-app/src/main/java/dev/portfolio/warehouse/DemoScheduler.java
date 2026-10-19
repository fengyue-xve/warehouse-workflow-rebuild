package dev.portfolio.warehouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 可选演示调度：仅走普通任务键，不自动 retry 或 refresh。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="demo.scheduler-enabled",havingValue="true")
public class DemoScheduler {
    private final Workflow workflow;
    private static final Logger LOG=LoggerFactory.getLogger(DemoScheduler.class);
    public DemoScheduler(Workflow workflow) { this.workflow=workflow; }
    @Scheduled(cron="${demo.sync-cron:0 10 9 * * *}",zone="Asia/Shanghai")
    public void run() {
        Auth.Actor actor=new Auth.Actor("scheduler","operator");
        try {
            var preview=workflow.preview(actor,"normal");
            workflow.execute(actor,preview.get("previewToken").toString(),"NORMAL",null,"定时同步：演示业务日固定，普通任务不自动重试",true);
        } catch (ApiError e) { LOG.info("Scheduled demo task: {}",e.code); }
    }
}
