package dev.portfolio.warehouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

@Component
public class ExportGateway {
    private final String base;
    private final String serviceKey;
    private final ObjectMapper mapper;
    // Uvicorn 的普通 HTTP 端口不接受 JDK 默认尝试的 h2c upgrade。
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    public ExportGateway(@Value("${demo.python-url}") String base,@Value("${demo.service-key}") String serviceKey,ObjectMapper mapper) {
        this.base=base; this.serviceKey=serviceKey; this.mapper=mapper;
    }
    public JsonNode export(String scenario, LocalDate date) {
        return post("/exports", Map.of("scenario",scenario,"businessDate",date.toString()),null,Duration.ofSeconds(5));
    }
    public JsonNode chat(String message, String bearer) {
        return post("/agent/chat",Map.of("message",message),bearer,Duration.ofSeconds(165));
    }
    private JsonNode post(String path,Object body,String bearer,Duration timeout) {
        try {
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(timeout)
                    .header("Content-Type","application/json").header("X-Service-Key",serviceKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (bearer!=null) b.header("Authorization",bearer);
            HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new ApiError(502,"CAPABILITY_UNAVAILABLE","Python 能力服务未能完成请求；可查看任务记录后人工重试");
            return mapper.readTree(response.body());
        } catch (ApiError e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ApiError(502,"CAPABILITY_UNAVAILABLE","能力服务请求被中断"); }
        catch (Exception e) { throw new ApiError(502,"CAPABILITY_UNAVAILABLE","能力服务连接失败或超时，业务表保持原状"); }
    }
}
