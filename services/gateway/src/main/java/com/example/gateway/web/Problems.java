package com.example.gateway.web;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Contract problem documents written by gateway filters (application/problem+json). */
public final class Problems {

    private Problems() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        String body = "{\"type\":\"https://tasks.demo.invalid/problems/" + code.toLowerCase().replace('_', '-') + "\""
                + ",\"title\":\"" + status.getReasonPhrase() + "\""
                + ",\"status\":" + status.value()
                + ",\"code\":\"" + code + "\""
                + ",\"detail\":\"" + detail + "\""
                + ",\"requestId\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
