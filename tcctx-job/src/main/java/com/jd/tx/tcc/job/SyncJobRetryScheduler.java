package com.jd.tx.tcc.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dangdang.ddframe.job.api.JobExecutionMultipleShardingContext;
import com.dangdang.ddframe.job.plugin.job.type.dataflow.AbstractBatchThroughputDataFlowElasticJob;
import com.jd.tx.tcc.core.TransactionManager;
import com.jd.tx.tcc.core.TransactionResource;
import com.jd.tx.tcc.core.TransactionRunner;
import com.jd.tx.tcc.core.impl.CommonTransactionContext;
import com.jd.tx.tcc.core.impl.JDBCHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.quartz.JobExecutionException;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * @author Leon Guo
 *         Creation Date: 2016/2/26
 */
public class SyncJobRetryScheduler extends AbstractBatchThroughputDataFlowElasticJob<Map<String, String>> {

//    @Setter
//    private int sleepTime;

    private TransactionRunner transactionRunner;

    Map<String, DataSource> dataSourceMap;

    private String dbPrefix;

    public SyncJobRetryScheduler() {
    }

    private DataSource getDataSource(JSONObject jsonObject) {
        int dataSourceId = NumberUtils.toInt(jsonObject.getString("dataSource"));

//        ApplicationContext appContext = SpingContextManager.get();
        String dataSourceKey = StringUtils.isBlank(dbPrefix) ? String.valueOf(dataSourceId) : dbPrefix + dataSourceId;
        return dataSourceMap.get(dataSourceKey);
    }

    private String getKey(JSONObject jsonObject) {
        String key = jsonObject.getString("key");
        Assert.notNull(key);
        return key;
    }

    @Override
    public List<Map<String, String>> fetchData(JobExecutionMultipleShardingContext shardingContext) {
        Assert.notNull(shardingContext);
        Assert.notNull(shardingContext.getJobParameter());

        String jobParameter = shardingContext.getJobParameter();
        JSONObject jsonObject = (JSONObject) JSON.parse(jobParameter);
        String key = getKey(jsonObject);
        DataSource dataSource = getDataSource(jsonObject);

        TransactionManager transactionManager = transactionRunner.getTransactionManager();
        TransactionResource resource = transactionManager.getResource(key);

        CommonTransactionContext context = new CommonTransactionContext();
        context.setKey(key);
        context.setDataSource(dataSource);

        List<Map<String, String>> timeoutItems = JDBCHelper.findTimeoutItems(context, resource,
                shardingContext.getShardingTotalCount(), shardingContext.getShardingItems());

        // Never stop the beat :)
        // Check timeout records every #sleepTime# seconds.
        // If current thread interrupted, need to restart it manually.
//        while (CollectionUtils.isEmpty(timeoutItems)) {
//            try {
//                Thread.sleep(sleepTime * 1000);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                throw new RuntimeException(e.getMessage(), e);
//            }
//            timeoutItems = JDBCHelper.findTimeoutItems(context, resource,
//                    shardingContext.getShardingTotalCount(), shardingContext.getShardingItems());
//        }
        return timeoutItems;
    }

    @Override
    public int processData(JobExecutionMultipleShardingContext shardingContext, List<Map<String, String>> data) {
        if (CollectionUtils.isEmpty(data)) {
            return 0;
        }

        String jobParameter = shardingContext.getJobParameter();
        JSONObject jsonObject = (JSONObject) JSON.parse(jobParameter);
        DataSource dataSource = getDataSource(jsonObject);

        int successNum = 0;
        for (Map<String, String> map : data) {
            if (map.isEmpty()) {
                return 0;
            }

            Map.Entry<String, String> entry = map.entrySet().iterator().next();
            CommonTransactionContext txContext = new CommonTransactionContext();

            txContext.setKey(shardingContext.getJobParameter());
            txContext.setDataSource(dataSource);
            txContext.setId(entry.getKey());
            txContext.setState(entry.getValue());

            try {
                transactionRunner.run(txContext);
                successNum++;
            } catch (Throwable e) {
                //todo: log exception and continue execute others
            }
        }
        return successNum;
    }

    @Override
    public boolean isStreamingProcess() {
        return true;
    }

    @Override
    public void handleJobExecutionException(JobExecutionException jobExecutionException) throws JobExecutionException {
//        jobExecutionException.setUnscheduleAllTriggers(true);
        throw jobExecutionException;
    }

    public void setTransactionRunner(TransactionRunner transactionRunner) {
        this.transactionRunner = transactionRunner;
    }

    public void setDataSourceMap(Map<String, DataSource> dataSourceMap) {
        this.dataSourceMap = dataSourceMap;
    }

    public void setDbPrefix(String dbPrefix) {
        this.dbPrefix = dbPrefix;
    }
}
