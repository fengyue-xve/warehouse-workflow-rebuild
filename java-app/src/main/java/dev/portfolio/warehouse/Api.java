package dev.portfolio.warehouse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class Api {
    private final Auth auth;
    private final Workflow workflow;
    private final ExportGateway gateway;
    public Api(Auth auth,Workflow workflow,ExportGateway gateway) { this.auth=auth; this.workflow=workflow; this.gateway=gateway; }
    public record Login(@NotBlank @Size(max=32) String username,@NotBlank @Size(max=64) String password) {}
    public record Preview(@NotBlank String scenario) {}
    public record Execute(@NotBlank String previewToken,@NotBlank String mode,String parentRunId,@NotBlank @Size(max=240) String reason,boolean confirmed) {}
    public record ToolCall(Map<String,Object> args) {}
    public record Chat(@NotBlank @Size(max=2000) String message) {}
    public record Reset(boolean confirmed) {}

    @GetMapping("/health") public Object health() { return Map.of("status","ok","synthetic",true,"businessDate",workflow.date()); }
    @PostMapping("/api/auth/login") public Object login(@Valid @RequestBody Login body) {
        return Map.of("token",auth.session(body.username(),body.password()),"username",body.username(),"role",body.username());
    }
    @GetMapping("/api/inventory") public Object stock(HttpServletRequest r) { auth.actor(r); return workflow.inventory(); }
    @GetMapping("/api/readiness") public Object readiness(HttpServletRequest r) { auth.actor(r); return workflow.readiness(); }
    @GetMapping("/api/runs") public Object runs(HttpServletRequest r) { auth.actor(r); return workflow.runs(); }
    @GetMapping("/api/runs/{id}") public Object run(HttpServletRequest r,@PathVariable String id) { auth.actor(r); return workflow.run(id); }
    @GetMapping("/api/audit") public Object audit(HttpServletRequest r) { auth.actor(r); return workflow.audit(); }
    @PostMapping("/api/sync/preview") public Object preview(HttpServletRequest r,@Valid @RequestBody Preview b) { return workflow.preview(auth.actor(r),b.scenario()); }
    @PostMapping("/api/sync/preview-read") public Object previewCard(HttpServletRequest r,@RequestBody Map<String,Object> b) {
        var actor=auth.actor(r);
        if (!b.isEmpty()) throw new ApiError(422,"BAD_REQUEST","问答预检仅允许固定正常场景");
        return workflow.previewCard(actor);
    }
    @PostMapping("/api/demo/reset") public Object reset(HttpServletRequest r,@RequestBody Reset body) {
        return workflow.resetDemo(auth.actor(r),body.confirmed());
    }
    @PostMapping("/api/sync/execute") public Object execute(HttpServletRequest r,@Valid @RequestBody Execute b) {
        return workflow.execute(auth.actor(r),b.previewToken(),b.mode(),b.parentRunId(),b.reason(),b.confirmed());
    }
    @PostMapping("/api/tools/{name}") public Object tool(HttpServletRequest r,@PathVariable String name,@RequestBody ToolCall b) {
        auth.actor(r); return workflow.tool(name,b.args()==null?Map.of():b.args());
    }
    @PostMapping("/api/chat") public Object chat(HttpServletRequest r,@Valid @RequestBody Chat b) {
        auth.actor(r); return gateway.chat(b.message(),r.getHeader("Authorization"));
    }
    @GetMapping("/api/reconciliation.csv") public ResponseEntity<byte[]> csv(HttpServletRequest r) {
        auth.actor(r);
        StringBuilder csv=new StringBuilder("\ufeffsku,label,quantity,data_date,source\r\n");
        for (Map<String,Object> i:workflow.inventory()) csv.append(i.get("sku")).append(',').append(i.get("label")).append(',')
                .append(i.get("quantity")).append(',').append(i.get("dataDate")).append(',').append(i.get("source")).append("\r\n");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=synthetic-stock-reconciliation.csv")
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    @ExceptionHandler(ApiError.class) public ResponseEntity<?> error(ApiError e) {
        return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","BAD_REQUEST","message","请求字段缺失或格式不正确")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code","INTERNAL_ERROR","message","内部处理失败，请检查本地服务与任务记录"));
    }
}
