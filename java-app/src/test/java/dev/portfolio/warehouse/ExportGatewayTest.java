package dev.portfolio.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ExportGatewayTest {
    @Test void httpTransportCarriesContractAndDoesNotAttemptProtocolUpgrade() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> protocol=new AtomicReference<>(),key=new AtomicReference<>(),body=new AtomicReference<>();
        server.createContext("/exports",exchange->{
            protocol.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            key.set(exchange.getRequestHeaders().getFirst("X-Service-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] result="{\"synthetic\":true,\"rows\":[]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,result.length); exchange.getResponseBody().write(result); exchange.close();
        });
        server.start();
        try {
            var gateway=new ExportGateway("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-service",new ObjectMapper());
            assertThat(gateway.export("normal",LocalDate.of(2026,10,20)).path("synthetic").asBoolean()).isTrue();
            assertThat(protocol.get()).isNull(); assertThat(key.get()).isEqualTo("synthetic-service");
            assertThat(new ObjectMapper().readTree(body.get()).path("businessDate").asText()).isEqualTo("2026-10-20");
        } finally { server.stop(0); }
    }
}
