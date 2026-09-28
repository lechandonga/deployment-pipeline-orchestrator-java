package com.github.lechandonga.deploymentpipelineorchestratorjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标槽位配额服务：模拟“扣减/回补资源”。
 *
 * <p>关键约定：每次占用/回补都带全局唯一操作令牌（releaseId+stage+目标），
 * 令牌集合保证“同一逻辑操作只生效一次”。因此并发推进或回滚后再推进，
 * 都不会发生重复扣减；回滚回补同样只执行一次。
 */
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final Set<String> occupiedTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> refundedTokens = ConcurrentHashMap.newKeySet();

    /**
     * 占用目标槽位。
     *
     * @return true=本次首次扣减；false=同令牌重放，未重复扣减
     */
    public boolean occupy(String releaseId, String stage, String target) {
        String token = releaseId + "|" + stage + "|" + target;
        boolean firstTime = occupiedTokens.add(token);
        log.info("quota.occupy releaseId={} stage={} target={} firstTime={} occupied={}",
                releaseId, stage, target, firstTime, occupiedTokens.size());
        return firstTime;
    }

    /**
     * 回补目标槽位。
     *
     * @return true=本次首次回补；false=同令牌已回补过，不再重复
     */
    public boolean refund(String releaseId, String stage, String target) {
        String token = releaseId + "|" + stage + "|" + target;
        boolean firstTime = refundedTokens.add(token);
        log.info("quota.refund releaseId={} stage={} target={} firstTime={} refunded={}",
                releaseId, stage, target, firstTime, refundedTokens.size());
        return firstTime;
    }

    public int occupiedCount() {
        return occupiedTokens.size();
    }

    public int refundedCount() {
        return refundedTokens.size();
    }

    public void reset() {
        occupiedTokens.clear();
        refundedTokens.clear();
    }
}
