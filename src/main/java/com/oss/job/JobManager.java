/**
* Project Name:KettleUtil
* Date:2016年6月28日
* Copyright (c) 2016, jingma All Rights Reserved.
*/

package com.oss.job;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import net.oschina.kettleutil.common.KuConst;
import net.oschina.mytuils.KettleUtils;

import org.beetl.sql.core.db.Db;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.Trans;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.PersistJobDataAfterExecution;

import cn.benma666.constants.UtilConst;
import cn.benma666.myutils.DateUtil;
import cn.benma666.myutils.StringUtil;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jfinal.log.Log;

/**
 * 作业具体操作管理 <br/>
 * date: 2016年6月28日 <br/>
 * @author jingma
 * @version 
 */
@PersistJobDataAfterExecution
@DisallowConcurrentExecution
public class JobManager extends AbsJob {
    /**
    * 日志
    */
    private static Log log = Log.getLog(JobManager.class);
    
    ///////////配置参数//////////////////////
    /**
    * 日志文件最大M
    */
    private static double logFileSize = 20;
    private static final String LOG_FILE_SIZE = "日志文件大小(M)";
    /**
    * 是否将job运行日志写入文件
    */
    private static Boolean writeLogFile = true;
    private static final String WRITE_LOG_FILE = "是否写日志文件";
    /**
    * 日志文件的根路径
    */
    private static String logFileRoot = "/tmp/kettle-manager/logs/kettle";
    private static final String LOG_FILE_ROOT = "日志文件路径";
    
    /**
    * 用于静态化配置信息
    */
    private static JSONObject jmConf = new JSONObject();
    
    /**
    * 启动失败
    */
    public static final String START_FAILED = "StartFailed";
    /**
    * 停止失败
    */
    public static final String STOP_FAILED = "StopFailed";
    /**
    * 插入作业日志SQL
    */
    public static final String SQL_INSERT_JOB_LOG = "insert into job_log(oid,id_job,job_name,start_date,log_file) values(?,?,?,?,?)";
    /**
     * 更新作业日志SQL
     */
     public static final String SQL_UPDATE_JOB_LOG = "update job_log l set l.end_date=?,l.result=?,l.update_date=? where l.oid=?";
     /**
    * 更新作业状态SQL
    */
    public static final String SQL_UPDATE_JOB_STATUS = "update r_job j set j.run_status=?,j.last_update=? where j.id_job=?";
    /**
    * 本应用操作的作业视图
    */
    public static String jobViewName = "v_job";
    /**
     * 本项目代码
     */
    public static String projectCode = "KM_LOCALHOST_82";
    /**
     * 资源库所在数据库操作对象
     */
    private static Db kettledb;
    /**
    * <作业id,作业>
    */
    private static Map<String,Job> jobMap = new Hashtable<String,Job>(); 
    /**
    * <作业,JSON作业>
    */
    private static Map<Job,JSONObject> jsonjobMap = new Hashtable<Job,JSONObject>();
    /**
    * <作业,开始时间>
    */
    private static Map<Job,String> jobStartDateMap = new Hashtable<Job,String>();
    /**
    * <作业,日志主键>
    */
    private static Map<Job,String> jobLogOidMap = new Hashtable<Job,String>();
    /**
    * <作业，已处理行数>
    */
    private static Map<Job,Integer> jobLogLine = new HashMap<Job, Integer>();
    /**
    * <作业，作业对应的日志文件>
    */
    private static Map<Job,File> jobLogFile = new HashMap<Job, File>();
    /**
    * <作业，作业对应的日志输出流>
    */
    private static Map<Job,FileOutputStream> jobLogStream = new HashMap<Job, FileOutputStream>();

    static{
        System.out.println(JobManager.class.getClassLoader());
        kettledb = Db.use(KuConst.DATASOURCE_KETTLE);
    }
    /**
     * Creates a new instance of GenerateDataBill.
     */
    public JobManager() {
    }

    /**
    * 
    * @throws Exception
    */
    @Override
    protected void process() throws Exception {
        log.info("log");
        //更新配置
        jmConf = configInfo;
        //要重启的作业
        List<JSONObject> restartList = new ArrayList<JSONObject>();
        //遍历运行的作业
        synchronized (jobMap) {
            Iterator<Entry<String, Job>> jobIter = jobMap.entrySet().iterator();
            while(jobIter.hasNext()){
                Job job = jobIter.next().getValue();
                String status = getJobStatus(job);
                int addLogLine = writeJobLog(job);
                if (job.getResult()!=null&&!job.isActive()) {
                    // 运行结束
                    updateJoblog(job);
                    try {
                        if(isWriteLogFile()){
                            jobLogStream.get(job).close();
                        }
                    } catch (Exception e) {
                        info("关闭日志输出流失败", e);
                    }
                    jobLogLine.remove(job);
                    jobLogStream.remove(job);
                    jobIter.remove();
                    JSONObject jsonjob = jsonjobMap.get(job);
                    jsonjobMap.remove(job);
                    //异常停止且如果设定的异常自动重启次数大于已经自动重启的次数则执行自动重启操作
                    if(STOP_FAILED.equals(status)&&
                            jsonjob.getIntValue("auto_restart_num")>
                            jsonjob.getIntValue("auto_restart_num_yj")){
                        restartList.add(jsonjob);
                    }
                }
                //有新日志产生才更新作业运行状态
                if(addLogLine>0){
                    //更新作业状态
                    kettledb.update(SQL_UPDATE_JOB_STATUS,status,
                            kettledb.getCurrentDateStr14(),
                            Integer.parseInt(job.getObjectId().getId()));
                }
            }
        }
        for(JSONObject jsonjob:restartList){
            startJob(jsonjob);
            //已重启次数+1
            jsonjob.put("auto_restart_num_yj",jsonjob.getIntValue("auto_restart_num_yj")+1);
            info("异常停止，自动重启："+jsonjob);
        }
    }

    /**
    * 启动时初始化，运行之前在运行的作业 <br/>
    * @author jingma
    * @param view 本应用操作的作业视图
    * @param string 
    */
    public static void init(String view, String projectCode){
        setJobViewName(view);
        setProjectCode(projectCode);
        String sql = "select * from "+getJobViewName()+" j where run_status=? and project_code = ?";
        List<JSONObject> list = kettledb.find(sql, Trans.STRING_RUNNING,getProjectCode());
        for(JSONObject job:list){
            String status = START_FAILED;
            try {
                status = startJob(job);
            } catch (Exception e) {
                log.error("启动job失败:"+job, e);
            }
            kettledb.update(SQL_UPDATE_JOB_STATUS, status,
                    kettledb.getCurrentDateStr14(),
                    job.getString("id_job"));
        }
    }

    /**
    * 启动作业 <br/>
    * @author jingma
    * @param jobJson 作业id
    * @return
    * @throws Exception
    */
    public static String startJob(JSONObject jobJson) throws Exception {
        String jobId = jobJson.getString("id_job");
        Date start = new Date();
//        JobMeta jm = KettleUtils.loadJob(jobJson.getString("name"),jobJson.getLong("id_directory"));
        JobMeta jm = KettleUtils.loadJob(jobJson.getLong("id_job"));
        log.info("加载作业总耗时："+(new Date().getTime()-start.getTime())+","+jobJson);
        Map<String, JSONObject> paramMap = kettledb.findMap("ocode","select * from job_params jp where jp.id_job=?", jobId);
        for(JSONObject param:paramMap.values()){
            //设置参数
            jm.setParameterValue(param.getString(KuConst.FIELD_OCODE), param.getString("value"));
        }
        Job job = new Job(KettleUtils.getInstanceRep(), jm);
        jsonjobMap.put(job, jobJson);
        return startJob(job);
    }
    /**
    * 启动作业 <br/>
    * @author jingma
    * @param job 作业
    * @return
    * @throws Exception
    */
    public static String startJob(Job job){
        System.out.println(JobManager.class.getClassLoader());
        String rs = getJobRunStatus(job.getObjectId().getId());
        if(rs!=null){
            return rs;
        }
        jobStartDateMap.put(job, kettledb.getCurrentDateStr14());
        job.start();
        jobMap.put(job.getObjectId().getId(), job);
        jobLogLine.put(job, 0);
        jobLogStream.put(job, null);
        log.info("作业启动完成："+job.getJobname());
        String status = getJobStatus(job);
        return status;
    }
    /**
    * 获取作业运行状态 <br/>
    * @author jingma
    * @param jobId 作业id
    * @return 运行中则返回作业实时状态，否则返回空
    */
    public static String getJobRunStatus(String jobId) {
        if(jobMap.containsKey(jobId)){
            //获取已经存在的job
            Job job = jobMap.get(jobId);
            //正在运行的
            if(job.isActive()){
                return job.getStatus();
            }else{
                // 运行结束
                writeJobLog(job);
                updateJoblog(job);
                try {
                    if(isWriteLogFile()){
                        jobLogStream.get(job).close();
                    }
                } catch (Exception e) {
                    log.debug("关闭日志输出流失败", e);
                }
                jobLogLine.remove(job);
                jobLogStream.remove(job);
                synchronized (jobMap) {
                    jobMap.remove(jobId);
                }
            }
        }
        return null;
    }

    /**
    * 停止作业 <br/>
    * @author jingma
    * @param idJob 作业id
    * @return
    * @throws Exception
    */
    public static String stopJob(String idJob) throws Exception {
        Job job = jobMap.get(idJob);
        if(job == null){
            return Trans.STRING_STOPPED;
        }
        job.stopAll();
        log.info("作业停止完成："+job.getJobname());
        String status = getJobStatus(job);
        return status;
    }

    /**
    * 结束作业 <br/>
    * @author jingma
    * @param idJob 作业id
    * @return
    * @throws Exception
    */
    @SuppressWarnings("deprecation")
    public static String killJob(String idJob) throws Exception {
        Job job = jobMap.get(idJob);
        if(job == null){
            return Trans.STRING_STOPPED;
        }
        //采用线程中断结束卡住的线程
        job.interrupt();
        job.join();
        //中断无效时，直接结束
        if(!job.getState().equals(State.TERMINATED)){
            job.stop();
        }
        log.info("作业结束完成："+job.getJobname());
        String status = getJobStatus(job);
        return status;
    }

    /**
    * 获取作业运行状态 <br/>
    * @author jingma
    * @param job
    * @return
    */
    public static String getJobStatus(Job job) {
        String status = job.getStatus();
        if(status.indexOf("errors")>-1){
            status = STOP_FAILED;
        }
        return status;
    }
    /**
    * 获取作业运行日志 <br/>
    * @author jingma
    * @param idJob 作业id
    * @return
    * @throws Exception
    */
    public static String getLog(String idJob) throws Exception {
        Job job = jobMap.get(idJob);
        if(job == null){
            return "该作业当前未运行。若想查看历史运行日志信息，请到【作业日志】页面查询并下载对应日志文件。";
        }
        int lastLineNr = KettleLogStore.getLastBufferLineNr();
        int startLineNr = 0;
        String msg = KettleLogStore.getAppender().getBuffer(
                job.getLogChannel().getLogChannelId(), false, 
                startLineNr , lastLineNr ).toString();
        if(StringUtil.isBlank(msg)){
            msg = "这里只能显示最近较短时间的实时运行日志。若想查看历史运行日志信息，请到【作业日志】页面查询并下载对应日志文件。";
        }
        return msg;
    }

    /**
    * 写job日志 <br/>
    * @author jingma
    * @param job 要写日志的job
    * @return 新增日志字数
    */
    public static int writeJobLog(Job job) {
        int lastLineNr = KettleLogStore.getLastBufferLineNr();
        int startLineNr = jobLogLine.get(job)==null?0:jobLogLine.get(job);
        //新增的日志
        String joblogStr = KettleLogStore.getAppender().getBuffer(
                job.getLogChannel().getLogChannelId(), false, 
                startLineNr, lastLineNr ).toString();
        jobLogLine.put(job, lastLineNr);
        //如果不写日志到文件
        if(isWriteLogFile()){
            FileOutputStream outStream = jobLogStream.get(job);
            File logFile = null;
            //若日志文件大于20M，则关闭输出流，下一次重新生成一个文件
            if(outStream!=null){
                if(jobLogFile.get(job).length()>getLogFileSize()*1024*1024){
                    try {
                        outStream.close();
                    } catch (IOException e) {
                        log.error("关闭日志输出流失败", e);
                    }
                    outStream = null;
                    jobLogStream.put(job, null);
                    jobLogFile.put(job, null);
                    jobStartDateMap.put(job, kettledb.getCurrentDateStr14());
                    //每个日志文件记录一条作业日志，用户可以根据时间区间选择要下载的日志。
                    updateJoblog(job);
                }
            }
            //还有对应的输出流
            if(outStream==null){
                logFile = new File(getLogFileRoot()+UtilConst.FXG
                        +DateUtil.doFormatDate(new Date(), DateUtil.DATE_FORMATTER8));
                if(!logFile.exists()){
                    logFile.mkdirs();
                }
                try {
                    logFile = new File(logFile.getAbsolutePath()+UtilConst.FXG
                            +job.getJobname()+"_"+DateUtil.doFormatDate(new Date(), 
                                    "HHmmss")+".txt");
                    jobLogFile.put(job, logFile);
                    if(!logFile.exists()){
                            logFile.createNewFile();
                    }
                    outStream = new FileOutputStream(logFile);
                    jobLogStream.put(job, outStream);
                    //记录日志记录的主键，用于更新
                    jobLogOidMap.put(job, StringUtil.getUUIDUpperStr());
                    //生成日志文件时就插入日志记录，便于用户在运行中查询下载作业日志，因为作业管理只显示最近时间的实时日志
                    kettledb.update(SQL_INSERT_JOB_LOG, jobLogOidMap.get(job),
                            Integer.parseInt(job.getObjectId().getId()),
                            job.getJobMeta().getName(),jobStartDateMap.get(job),
                            jobLogFile.get(job).getAbsolutePath());
                    jobStartDateMap.remove(job);
                } catch (Exception e) {
                    log.error("创建日志文件失败："+logFile.getAbsolutePath(), e);
                }
            }
            try {
                outStream.write(joblogStr.getBytes());
            } catch (Exception e) {
                log.error("写日志文件失败", e);
            }
        }
        return joblogStr.length();
    }

    /**
    * 每个日志文件记录一条作业日志，用户可以根据时间区间选择要下载的日志。 <br/>
    * @author jingma
    * @param job 作业
    */
    public static void updateJoblog(Job job) {
        kettledb.update(SQL_UPDATE_JOB_LOG, 
                kettledb.getCurrentDateStr14(),
                getJobStatus(job),
                kettledb.getCurrentDateStr14(),
                jobLogOidMap.get(job));
    }
    
    public String getDefaultConfigInfo() throws Exception {
        JSONObject params = new JSONObject();
        params.put(LOG_FILE_SIZE, logFileSize);
        params.put(WRITE_LOG_FILE, writeLogFile);
        params.put(LOG_FILE_ROOT, logFileRoot);
        return JSON.toJSONString(params, true);
    }

    /**
     * @return writeLogFile 
     */
    public static Boolean isWriteLogFile() {
        setWriteLogFile(jmConf.getBoolean(WRITE_LOG_FILE));
        return writeLogFile;
    }
    /**
     * @param writeLogFile the writeLogFile to set
     */
    public static void setWriteLogFile(Boolean writeLogFile) {
        if(writeLogFile!=null){
            JobManager.writeLogFile = UtilConst.WHETHER_TRUE.equals(StringUtil.whether(writeLogFile));
        }
    }

    /**
     * @return logFileRoot 
     */
    public static String getLogFileRoot() {
        setLogFileRoot(jmConf.getString(LOG_FILE_ROOT));
        return logFileRoot;
    }

    /**
     * @param logFileRoot the logFileRoot to set
     */
    public static void setLogFileRoot(String logFileRoot) {
        if(logFileRoot!=null){
            JobManager.logFileRoot = logFileRoot;
        }
    }

    /**
     * @return logFileSize 
     */
    public static double getLogFileSize() {
        setLogFileSize(jmConf.getDouble(LOG_FILE_SIZE));
        return logFileSize;
    }

    /**
     * @param logFileSize the logFileSize to set
     */
    public static void setLogFileSize(double logFileSize) {
        if(logFileSize>0){
            JobManager.logFileSize = logFileSize;
        }
    }

    /**
     * @return jobViewName 
     */
    public static String getJobViewName() {
        return jobViewName;
    }

    /**
     * @param jobViewName the jobViewName to set
     */
    public static void setJobViewName(String jobViewName) {
        if(jobViewName!=null){
            JobManager.jobViewName = jobViewName;
        }
    }
    /**
     * @return projectCode 
     */
    public static String getProjectCode() {
        return projectCode;
    }
    /**
     * @param projectCode the projectCode to set
     */
    public static void setProjectCode(String projectCode) {
        if(projectCode!=null){
            JobManager.projectCode = projectCode;
        }
    }
    /**
     * @return jobMap 
     */
    public static Job getJob(int jobId) {
        return jobMap.get(jobId+"");
    }
    
}
