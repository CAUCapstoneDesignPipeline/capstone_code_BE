package com.example.capstone.conversion.config;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** multipart 임시 파일 파싱 전 업로드 동시 요청 수를 제한한다. */
@Component
public class UploadConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore uploads=new Semaphore(2);
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getMethod().equals("POST") || !request.getRequestURI().equals("/api/source-files"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!uploads.tryAcquire()) { response.setStatus(503);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write("{\"error\":{\"code\":\"CONVERSION_UNAVAILABLE\",\"message\":\"문서 변환은 현재 사용할 수 없습니다.\"}}");return; }
        try { chain.doFilter(request,response); }finally { uploads.release(); }
    }
}
