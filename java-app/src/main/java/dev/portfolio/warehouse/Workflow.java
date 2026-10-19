package dev.portfolio.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 单实例演示：锁串行化预检/执行；业务行、审计与成功结果在同一个本地事务提交。 */
@Service
@DependsOnDatabaseInitialization
public class Workflow {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ExportGateway gateway;
    private final Auth auth;
    private final ObjectMapper mapper;
    private final LocalDate businessDate;
    private static final Set<String> SCENARIOS=Set.of("normal","empty","stale","duplicate","spike","unavailable");

    public Workflow(JdbcTemplate db,TransactionTemplate tx,ExportGateway gateway,Auth auth,ObjectMapper mapper,
                    @Value("${demo.business-date}") LocalDate date) {
        this.db=db; this.tx=tx; this.gateway=gateway; this.auth=auth; this.mapper=mapper; this.businessDate=date;
    }
    public String date() { return businessDate.toString(); }
    private String now() { return OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(); }
    private String json(Object data) {
        try { return mapper.writeValueAsString(data); }
        catch (Exception e) { throw new IllegalStateException("结果序列化失败",e); }
    }
    private String digest(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(node).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private long revision() { return db.queryForObject("SELECT revision FROM demo_meta WHERE id=1",Long.class); }
    private static ApiError bad(String code,String message) { return new ApiError(422,code,message); }

    public synchronized Map<String,Object> preview(Auth.Actor actor,String scenario) {
        auth.requireOperator(actor);
        if (!SCENARIOS.contains(scenario)) throw bad("BAD_SCENARIO","请选择已定义的合成演示场景");
        long revision=revision();
        // unavailable 以确定的演示故障占位：仍然能确认启动任务，并留下失败记录。
        JsonNode payload=fetch(scenario);
        Map<String,Object> report=inspect(payload);
        String previewId=UUID.randomUUID().toString();
        String token=auth.sign(actor.name(),"sync-preview",Map.of("scenario",scenario,"digest",digest(payload),
                "revision",revision,"businessDate",date(),"previewId",previewId),300);
        Map<String,Object> result=new LinkedHashMap<>(report);
        result.put("previewToken",token); result.put("previewId",previewId);
        result.put("revision",revision); result.put("businessDate",date()); result.put("expiresInSeconds",300);
        return result;
    }

    private JsonNode fetch(String scenario) {
        if (scenario.equals("unavailable")) return mapper.valueToTree(Map.of("demoFailure","EXPORT_UNAVAILABLE"));
        return gateway.export(scenario,businessDate);
    }

    private Map<String,Object> inspect(JsonNode payload) {
        List<String> reasons=new ArrayList<>();
        List<Map<String,Object>> changes=new ArrayList<>();
        if (payload.has("demoFailure")) return Map.of("eligible",false,"reasons",List.of("模拟导出失败，不能更新业务表"),"changes",changes);
        if (!"synthetic-warehouse".equals(payload.path("source").asText())) reasons.add("来源不在演示白名单内");
        if (!date().equals(payload.path("dataDate").asText())) reasons.add("源数据日期与本次业务日不一致，不能当作当前库存");
        JsonNode rows=payload.path("rows");
        Map<String,Map<String,Object>> stock=new HashMap<>();
        for (Map<String,Object> item:inventory()) stock.put(item.get("sku").toString(),item);
        Set<String> seen=new HashSet<>();
        long totalUnits=0;
        if (!rows.isArray() || rows.isEmpty()) reasons.add("导出数据为空；不得据此把库存清零");
        else for (JsonNode row:rows) {
            String sku=row.path("sku").asText("");
            if (!stock.containsKey(sku)) { reasons.add("出现未登记的 SKU"); continue; }
            if (!seen.add(sku)) { reasons.add("源数据包含重复 SKU："+sku); continue; }
            JsonNode q=row.path("quantity");
            if (!q.isIntegralNumber() || !q.canConvertToInt() || q.intValue()<0) { reasons.add("库存数量必须为非负整数："+sku); continue; }
            int quantity=q.intValue();
            totalUnits+=quantity;
            Map<String,Object> old=stock.get(sku);
            if (((Number)old.get("quantity")).intValue()!=quantity)
                changes.add(Map.of("sku",sku,"label",old.get("label"),"before",old.get("quantity"),"after",quantity));
        }
        if (seen.size()!=stock.size()) reasons.add("源数据没有覆盖全部演示商品，缺失不等于库存为零");
        List<Map<String,Object>> samples=db.queryForList("SELECT row_count,total_units,origin FROM baseline_sample ORDER BY sample_order DESC LIMIT 7");
        double meanUnits=samples.stream().mapToLong(s->((Number)s.get("TOTAL_UNITS")).longValue()).average().orElse(0);
        if (samples.size()>=3 && meanUnits>0 && Math.abs(totalUnits-meanUnits)/meanUnits>0.50)
            reasons.add("库存总量相对参考样本偏离超过 50%，需人工核实源数据");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("eligible",reasons.isEmpty()); report.put("reasons",reasons.stream().distinct().toList());
        report.put("changes",changes); report.put("sourceRowCount",rows.isArray()?rows.size():0);
        report.put("sourceTotalUnits",totalUnits); report.put("baselineMeanUnits",meanUnits);
        report.put("baselineSampleCount",samples.size()); report.put("baselineIncludesSyntheticReferences",samples.stream().anyMatch(s->s.get("ORIGIN").equals("FIXTURE")));
        report.put("thresholdNote","演示阈值为总量偏离 50%；前三个参考样本为虚构夹具，不是生产历史");
        return report;
    }

    public synchronized Map<String,Object> execute(Auth.Actor actor,String token,String mode,String parentId,String reason,boolean confirmed) {
        auth.requireOperator(actor);
        if (!confirmed) throw bad("CONFIRMATION_REQUIRED","需要在工作台明确勾选确认；聊天文字不能替代授权");
        if (!Set.of("NORMAL","RETRY","REFRESH").contains(mode)) throw bad("BAD_MODE","无效执行模式");
        if (reason==null || reason.isBlank() || reason.length()>240) throw bad("REASON_REQUIRED","请填写不超过240字符的执行原因");
        Claims p;
        try { p=auth.verify(token,"sync-preview"); }
        catch (ApiError e) { throw new ApiError(409,"INVALID_PREVIEW","预检已过期或无效，请重新预检；登录会话仍可继续使用"); }
        if (!actor.name().equals(p.getSubject()) || !date().equals(p.get("businessDate",String.class)))
            throw new ApiError(403,"PREVIEW_OWNER_MISMATCH","不能使用其他身份或业务日的预检结果");
        String scenario=p.get("scenario",String.class);
        if (!SCENARIOS.contains(scenario)) throw bad("BAD_SCENARIO","无效场景");
        String previewId=p.get("previewId",String.class);
        if (db.queryForObject("SELECT COUNT(*) FROM sync_run WHERE preview_id=?",Integer.class,previewId)>0)
            throw new ApiError(409,"PREVIEW_ALREADY_USED","同一预检已执行；请查看原任务，避免重复操作");
        if (db.queryForObject("SELECT COUNT(*) FROM sync_run WHERE status='RUNNING'",Integer.class)>0)
            throw new ApiError(409,"RUN_IN_PROGRESS","已有任务执行中");
        String baseKey=date()+":warehouse-sync";
        if (mode.equals("NORMAL")) {
            if (parentId!=null && !parentId.isBlank()) throw bad("BAD_PARENT","普通任务不能指定来源任务");
        } else {
            if (parentId==null || parentId.isBlank()) throw bad("PARENT_REQUIRED","人工重跑必须选择来源任务");
            Map<String,Object> parent=run(parentId);
            if (!date().equals(parent.get("businessDate"))) throw bad("PARENT_DATE_MISMATCH","只能重跑当前业务日的任务");
            if (mode.equals("RETRY") && !"FAILED".equals(parent.get("status"))) throw new ApiError(409,"RETRY_REQUIRES_FAILURE","只有失败任务可以重试");
            if ("RUNNING".equals(parent.get("status"))) throw new ApiError(409,"RUN_IN_PROGRESS","来源任务仍在运行");
        }
        String id=UUID.randomUUID().toString();
        String taskKey=mode.equals("NORMAL")?baseKey:baseKey+":"+mode.toLowerCase()+":"+id;
        try {
            db.update("INSERT INTO sync_run(run_id,task_key,status,mode,actor,scenario,parent_run_id,reason,business_date,requested_at,result_json,preview_id) VALUES(?,?,'RUNNING',?,?,?,?,?,?,?,'{}',?)",
                    id,taskKey,mode,actor.name(),scenario,parentId,reason.trim(),businessDate,now(),previewId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiError(409,"DUPLICATE_TASK","当日普通任务已存在；失败请重试，终态刷新请填写来源和原因");
        }
        audit(id,actor.name(),"RUN_STARTED",Map.of("mode",mode,"reason",reason.trim()));
        try {
            if (p.get("revision",Number.class).longValue()!=revision()) throw new ApiError(409,"STALE_PREVIEW","预检后业务表已经变化；请重新预检");
            JsonNode source=fetch(scenario);
            if (!digest(source).equals(p.get("digest",String.class))) throw new ApiError(409,"SOURCE_CHANGED","预检后源数据已经变化；请重新预检");
            Map<String,Object> report=inspect(source);
            if (!(Boolean)report.get("eligible")) {
                String code=scenario.equals("unavailable")?"EXPORT_UNAVAILABLE":"QUALITY_BLOCKED";
                fail(id,actor.name(),code,String.join("；",(List<String>)report.get("reasons")),report);
                return run(id);
            }
            tx.executeWithoutResult(status->{
                if (p.get("revision",Number.class).longValue()!=revision()) throw new ApiError(409,"STALE_PREVIEW","业务版本变化，请重新预检");
                for (JsonNode row:source.path("rows")) {
                    db.update("UPDATE inventory SET quantity=?,data_date=?,source_name=?,last_run_id=? WHERE sku=?",
                            row.path("quantity").intValue(),businessDate,"synthetic-warehouse",id,row.path("sku").asText());
                }
                db.update("UPDATE demo_meta SET revision=revision+1 WHERE id=1");
                db.update("INSERT INTO baseline_sample(sample_key,row_count,total_units,origin) VALUES(?,?,?,'RUN')",
                        "run:"+now()+":"+id,report.get("sourceRowCount"),report.get("sourceTotalUnits"));
                audit(id,actor.name(),"SNAPSHOT_COMMITTED",report);
                db.update("UPDATE sync_run SET status='SUCCEEDED',finished_at=?,source_digest=?,result_json=? WHERE run_id=? AND status='RUNNING'",
                        now(),digest(source),json(report),id);
            });
        } catch (ApiError e) { fail(id,actor.name(),e.code,e.getMessage(),Map.of()); }
        catch (RuntimeException e) { fail(id,actor.name(),"EXECUTION_FAILED","执行未完成，本地事务已回滚；请核对任务后重试",Map.of()); }
        return run(id);
    }

    private void audit(String runId,String actor,String action,Object detail) {
        db.update("INSERT INTO audit_event(run_id,actor,action,occurred_at,detail_json) VALUES(?,?,?,?,?)",runId,actor,action,now(),json(detail));
    }
    private void fail(String id,String actor,String code,String message,Object detail) {
        tx.executeWithoutResult(status->{
            db.update("UPDATE sync_run SET status='FAILED',finished_at=?,error_code=?,error_message=?,result_json=? WHERE run_id=? AND status='RUNNING'",
                    now(),code,message.length()>240?message.substring(0,240):message,json(detail),id);
            audit(id,actor,"RUN_FAILED",Map.of("code",code,"message",message));
        });
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recoverInterrupted() {
        for (String id:db.queryForList("SELECT run_id FROM sync_run WHERE status='RUNNING'",String.class))
            fail(id,"system","INTERRUPTED","进程中断；本地业务事务没有成功提交，请由人工检查并重试",Map.of());
    }
    public List<Map<String,Object>> inventory() {
        return db.query("SELECT i.*,c.label,c.pack_size FROM inventory i JOIN catalog c ON c.sku=i.sku ORDER BY i.sku",(r,n)->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("sku",r.getString("sku")); m.put("label",r.getString("label")); m.put("quantity",r.getInt("quantity"));
            m.put("packSize",r.getInt("pack_size")); m.put("dataDate",r.getDate("data_date").toLocalDate().toString());
            m.put("source",r.getString("source_name")); m.put("lastRunId",r.getString("last_run_id"));
            m.put("fresh",r.getDate("data_date").toLocalDate().equals(businessDate));
            return m;
        });
    }
    private Map<String,Object> mapRun(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        for (String col:List.of("run_id","task_key","status","mode","actor","scenario","parent_run_id","reason","requested_at","finished_at","error_code","error_message")) {
            String[] words=col.split("_"); String key=words[0];
            for(int i=1;i<words.length;i++) key+=Character.toUpperCase(words[i].charAt(0))+words[i].substring(1);
            m.put(key,r.getString(col));
        }
        m.put("businessDate",r.getDate("business_date").toLocalDate().toString());
        try { m.put("result",mapper.readTree(r.getString("result_json"))); }
        catch (Exception e) { throw new IllegalStateException("任务结果损坏",e); }
        return m;
    }
    public List<Map<String,Object>> runs() { return db.query("SELECT * FROM sync_run ORDER BY requested_at DESC,run_id DESC LIMIT 30",this::mapRun); }
    public Map<String,Object> run(String id) {
        List<Map<String,Object>> found=db.query("SELECT * FROM sync_run WHERE run_id=?",this::mapRun,id);
        if (found.isEmpty()) throw new ApiError(404,"RUN_NOT_FOUND","任务不存在");
        return found.getFirst();
    }
    public List<Map<String,Object>> audit() {
        return db.query("SELECT * FROM audit_event ORDER BY id DESC LIMIT 60",(r,n)->Map.of("id",r.getLong("id"),
                "runId",r.getString("run_id"),"actor",r.getString("actor"),"action",r.getString("action"),
                "occurredAt",r.getString("occurred_at"),"detail",r.getString("detail_json")));
    }
    public Map<String,Object> readiness() {
        List<Map<String,Object>> items=inventory();
        List<Map<String,Object>> recent=runs();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("businessDate",date()); out.put("revision",revision()); out.put("synthetic",true);
        out.put("ready",items.stream().allMatch(m->Boolean.TRUE.equals(m.get("fresh"))));
        out.put("lastAttempt",recent.isEmpty()?null:recent.getFirst());
        out.put("lastSuccessfulRun",db.query("SELECT * FROM sync_run WHERE status='SUCCEEDED' ORDER BY requested_at DESC LIMIT 1",this::mapRun).stream().findFirst().orElse(null));
        out.put("scope","库存核对；不表示订单已发货、到货或客户可交付时间");
        return out;
    }
    public Object tool(String name,Map<String,Object> args) {
        switch (name) {
            case "query_inventory":
            case "query_product":
                if (!Set.of("sku").containsAll(args.keySet()) || (args.containsKey("sku") && !(args.get("sku") instanceof String)))
                    throw bad("BAD_TOOL_ARGS","sku 必须为字符串，且不接受其他字段");
                String sku=(String)args.getOrDefault("sku","");
                if (sku.length()>48) throw bad("BAD_TOOL_ARGS","sku 超出长度限制");
                List<Map<String,Object>> rows=inventory().stream().filter(i->sku.isBlank()||i.get("sku").equals(sku)).toList();
                return Map.of("businessDate",date(),"items",rows,"missing",!sku.isBlank()&&rows.isEmpty(),"synthetic",true);
            case "query_run":
                if (!args.isEmpty()) throw bad("BAD_TOOL_ARGS","此工具不接受参数");
                return Map.of("businessDate",date(),"items",runs());
            case "query_readiness":
                if (!args.isEmpty()) throw bad("BAD_TOOL_ARGS","此工具不接受参数");
                return readiness();
            default: throw new ApiError(403,"TOOL_DENIED","此工具未注册为只读能力，不能通过模型入口执行");
        }
    }
}
