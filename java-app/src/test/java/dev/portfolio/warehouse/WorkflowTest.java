package dev.portfolio.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workflow-tests;DB_CLOSE_DELAY=-1","demo.business-date=2026-10-20"})
@AutoConfigureMockMvc
class WorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired Auth auth;
    @Autowired Workflow workflow;
    @MockBean ExportGateway gateway;
    String operator,viewer;

    @BeforeEach void setup() throws Exception {
        db.update("DELETE FROM audit_event"); db.update("DELETE FROM sync_run");
        db.update("DELETE FROM baseline_sample WHERE origin='RUN'");
        db.update("UPDATE demo_meta SET revision=0 WHERE id=1");
        db.update("UPDATE inventory SET quantity=CASE sku WHEN 'DEMO-CUP-01' THEN 80 WHEN 'DEMO-LAMP-01' THEN 40 ELSE 120 END,data_date=DATE '2026-10-19',source_name='synthetic-seed',last_run_id=NULL");
        reset(gateway);
        when(gateway.export(anyString(),any())).thenAnswer(inv->{
            String scenario=inv.getArgument(0);
            List<Map<String,Object>> rows=new java.util.ArrayList<>(List.of(Map.of("sku","DEMO-CUP-01","quantity",100),
                    Map.of("sku","DEMO-LAMP-01","quantity",40),Map.of("sku","DEMO-BAG-01","quantity",90)));
            if (scenario.equals("empty")) rows.clear();
            if (scenario.equals("duplicate")) rows.add(rows.getFirst());
            if (scenario.equals("spike")) rows.replaceAll(r->Map.of("sku",r.get("sku"),"quantity",5000));
            return mapper.valueToTree(Map.of("source","synthetic-warehouse","dataDate",scenario.equals("stale")?"2026-10-18":"2026-10-20","rows",rows));
        });
        operator="Bearer "+auth.session("operator","demo-only"); viewer="Bearer "+auth.session("viewer","demo-only");
    }
    JsonNode result(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    JsonNode postJson(String path,Object body,String bearer,int status) throws Exception {
        return result(mvc.perform(post(path).header("Authorization",bearer).contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andExpect(status().is(status)).andReturn());
    }
    JsonNode preview(String scenario) throws Exception { return postJson("/api/sync/preview",Map.of("scenario",scenario),operator,200); }
    Map<String,Object> command(JsonNode p,String mode,String parent) {
        var body=new java.util.HashMap<String,Object>(); body.put("previewToken",p.path("previewToken").asText());
        body.put("mode",mode); body.put("parentRunId",parent); body.put("reason","核对合成数据后执行"); body.put("confirmed",true);
        return body;
    }
    JsonNode execute(JsonNode p,String mode,String parent) throws Exception { return postJson("/api/sync/execute",command(p,mode,parent),operator,200); }
    int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    int quantity(String sku) { return db.queryForObject("SELECT quantity FROM inventory WHERE sku=?",Integer.class,sku); }

    @Test void authIsRequiredAndLoginTokensHaveSeparatePurposes() throws Exception {
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
        postJson("/api/auth/login",Map.of("username","operator","password","incorrect"),operator,401);
        var p=preview("normal");
        mvc.perform(get("/api/inventory").header("Authorization","Bearer "+p.path("previewToken").asText())).andExpect(status().isUnauthorized());
    }
    @Test void viewerCanReadButCannotPreviewOrExecute() throws Exception {
        mvc.perform(get("/api/inventory").header("Authorization",viewer)).andExpect(status().isOk());
        postJson("/api/sync/preview",Map.of("scenario","normal"),viewer,403);
        postJson("/api/sync/execute",command(preview("normal"),"NORMAL",null),viewer,403);
        assertThat(count("sync_run")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"sync_tms","generate_files","unknown"})
    void toolBoundaryRejectsUnregisteredActions(String name) throws Exception {
        postJson("/api/tools/"+name,Map.of("args",Map.of("confirmed",true)),operator,403);
        verifyNoInteractions(gateway); assertThat(quantity("DEMO-CUP-01")).isEqualTo(80);
    }
    @Test void toolArgumentsAndUnknownSkuAreNotFabricated() throws Exception {
        postJson("/api/tools/query_inventory",Map.of("args",Map.of("force",true)),operator,422);
        JsonNode r=postJson("/api/tools/query_inventory",Map.of("args",Map.of("sku","NOT-FOUND")),viewer,200);
        assertThat(r.path("missing").asBoolean()).isTrue(); assertThat(r.path("items").isEmpty()).isTrue();
    }
    @Test void previewHasNoBusinessSideEffectsAndExecuteCommitsResultAndAudit() throws Exception {
        JsonNode p=preview("normal");
        assertThat(p.path("changes").size()).isEqualTo(2); assertThat(quantity("DEMO-CUP-01")).isEqualTo(80);
        assertThat(count("sync_run")).isZero();
        JsonNode run=execute(p,"NORMAL",null);
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(quantity("DEMO-CUP-01")).isEqualTo(100);
        assertThat(workflow.readiness().get("ready")).isEqualTo(true);
        assertThat(count("audit_event")).isEqualTo(2);
        assertThat(run.path("result").path("baselineIncludesSyntheticReferences").asBoolean()).isTrue();
    }
    @Test void confirmationCannotBeReplacedByChatText() throws Exception {
        var body=command(preview("normal"),"NORMAL",null); body.put("confirmed",false); body.put("reason","确认同步");
        postJson("/api/sync/execute",body,operator,422);
        assertThat(count("sync_run")).isZero();
    }
    @Test void repeatedPreviewAndRepeatedDailyTaskDoNotCreateExecutions() throws Exception {
        JsonNode p=preview("normal"); execute(p,"NORMAL",null);
        postJson("/api/sync/execute",command(p,"NORMAL",null),operator,409);
        postJson("/api/sync/execute",command(preview("normal"),"NORMAL",null),operator,409);
        assertThat(count("sync_run")).isEqualTo(1); assertThat(count("audit_event")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"empty","stale","duplicate","spike","unavailable"})
    void badExportsFailWithoutChangingBusinessData(String scenario) throws Exception {
        JsonNode p=preview(scenario); assertThat(p.path("eligible").asBoolean()).isFalse();
        JsonNode run=execute(p,"NORMAL",null);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(quantity("DEMO-CUP-01")).isEqualTo(80); assertThat(quantity("DEMO-BAG-01")).isEqualTo(120);
        assertThat(workflow.readiness().get("ready")).isEqualTo(false);
        assertThat(count("baseline_sample")).isEqualTo(3);
    }
    @Test void retryKeepsFailureAndItsAuditAndRequiresParentFailure() throws Exception {
        JsonNode failed=execute(preview("empty"),"NORMAL",null);
        int originalAudit=count("audit_event");
        JsonNode recovered=execute(preview("normal"),"RETRY",failed.path("runId").asText());
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("parentRunId").asText()).isEqualTo(failed.path("runId").asText());
        assertThat(workflow.run(failed.path("runId").asText()).get("status")).isEqualTo("FAILED");
        assertThat(count("audit_event")).isGreaterThan(originalAudit);
        postJson("/api/sync/execute",command(preview("normal"),"RETRY",recovered.path("runId").asText()),operator,409);
        assertThat(count("sync_run")).isEqualTo(2);
    }
    @Test void refreshFailureKeepsLastSuccessfulDataAndSeparatesLatestAttempt() throws Exception {
        JsonNode success=execute(preview("normal"),"NORMAL",null);
        execute(preview("stale"),"REFRESH",success.path("runId").asText());
        Map<String,Object> readiness=workflow.readiness();
        assertThat(readiness.get("ready")).isEqualTo(true);
        assertThat(((Map<?,?>)readiness.get("lastAttempt")).get("status")).isEqualTo("FAILED");
        assertThat(((Map<?,?>)readiness.get("lastSuccessfulRun")).get("runId")).isEqualTo(success.path("runId").asText());
    }
    @Test void changedBusinessVersionInvalidatesOldPreview() throws Exception {
        JsonNode old=preview("normal");
        JsonNode done=execute(preview("normal"),"NORMAL",null);
        JsonNode result=execute(old,"REFRESH",done.path("runId").asText());
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("errorCode").asText()).isEqualTo("STALE_PREVIEW");
        assertThat(count("baseline_sample")).isEqualTo(4);
    }
    @Test void changedSourceBetweenPreviewAndConfirmationIsBlocked() throws Exception {
        JsonNode p=preview("normal");
        when(gateway.export(eq("normal"),any())).thenReturn(mapper.valueToTree(Map.of("source","synthetic-warehouse","dataDate","2026-10-20","rows",List.of())));
        JsonNode result=execute(p,"NORMAL",null);
        assertThat(result.path("errorCode").asText()).isEqualTo("SOURCE_CHANGED");
        assertThat(quantity("DEMO-CUP-01")).isEqualTo(80);
    }
    @Test void realGatewayFailureIsRecordedAndCanBeRetried() throws Exception {
        JsonNode p=preview("normal");
        when(gateway.export(anyString(),any())).thenThrow(new ApiError(502,"CAPABILITY_UNAVAILABLE","能力服务连接失败"));
        JsonNode result=execute(p,"NORMAL",null);
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("errorCode").asText()).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(quantity("DEMO-CUP-01")).isEqualTo(80);
    }
    @Test void databaseFailureRollsBackBusinessRowsAndSuccessAuditTogether() throws Exception {
        JsonNode p=preview("normal");
        db.execute("ALTER TABLE baseline_sample ADD CONSTRAINT injected_commit_failure CHECK(total_units=240)");
        try {
            JsonNode result=execute(p,"NORMAL",null);
            assertThat(result.path("status").asText()).isEqualTo("FAILED");
            assertThat(result.path("errorCode").asText()).isEqualTo("EXECUTION_FAILED");
            assertThat(quantity("DEMO-CUP-01")).isEqualTo(80);
            assertThat(count("baseline_sample")).isEqualTo(3);
            assertThat(workflow.audit()).noneMatch(e->e.get("action").equals("SNAPSHOT_COMMITTED"));
        } finally { db.execute("ALTER TABLE baseline_sample DROP CONSTRAINT injected_commit_failure"); }
    }
    @Test void expiredPreviewCannotStartTask() throws Exception {
        String expired=auth.sign("operator","sync-preview",Map.of(),-10);
        postJson("/api/sync/execute",Map.of("previewToken",expired,"mode","NORMAL","reason","过期测试","confirmed",true),operator,409);
        assertThat(count("sync_run")).isZero();
    }
    @Test void concurrentConfirmationHasOnlyOneWinner() throws Exception {
        var body=command(preview("normal"),"NORMAL",null);
        Callable<Integer> request=()->mvc.perform(post("/api/sync/execute").header("Authorization",operator).contentType("application/json")
                .content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getStatus();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(request); var second=pool.submit(request);
            assertThat(List.of(first.get(),second.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(count("sync_run")).isEqualTo(1);
    }
    @Test void interruptedRunIsMarkedFailedWithoutAutomaticRetry() {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO sync_run(run_id,task_key,status,mode,actor,scenario,reason,business_date,requested_at,result_json,preview_id) VALUES(?,?,'RUNNING','NORMAL','operator','normal','模拟进程中断',DATE '2026-10-20','2026-10-20T10:00:00+08:00','{}',?)",id,"interrupted-key",UUID.randomUUID().toString());
        workflow.recoverInterrupted();
        assertThat(workflow.run(id).get("errorCode")).isEqualTo("INTERRUPTED");
        assertThat(quantity("DEMO-CUP-01")).isEqualTo(80); assertThat(count("sync_run")).isEqualTo(1);
    }
    @Test void reconciliationExportUsesPersistedValues() throws Exception {
        mvc.perform(get("/api/reconciliation.csv").header("Authorization",viewer)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("DEMO-CUP-01,演示保温杯,80,2026-10-19,synthetic-seed")));
    }
    @Test void schedulerUsesNormalModeAndNeverRetries() {
        Workflow mock=mock(Workflow.class);
        when(mock.preview(any(),eq("normal"))).thenReturn(Map.of("previewToken","synthetic-preview"));
        new DemoScheduler(mock).run();
        verify(mock).execute(any(),eq("synthetic-preview"),eq("NORMAL"),isNull(),anyString(),eq(true));
    }
}
