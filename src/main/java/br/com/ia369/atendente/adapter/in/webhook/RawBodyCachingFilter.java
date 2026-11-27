package br.com.ia369.atendente.adapter.in.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RawBodyCachingFilter extends OncePerRequestFilter {

    public static final String RAW_BODY_ATTRIBUTE = "telnyx.rawBody";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/webhooks/telnyx/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] rawBytes = request.getInputStream().readAllBytes();
        request.setAttribute(RAW_BODY_ATTRIBUTE, rawBytes);
        filterChain.doFilter(new CachedBodyRequestWrapper(request, rawBytes), response);
    }

    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }

                @Override
                public int read() {
                    return bais.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name();
            try {
                return new BufferedReader(new InputStreamReader(getInputStream(), enc));
            } catch (Exception e) {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }
    }
}

