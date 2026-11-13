package com.example.books;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BooksApplication {

	public static void main(String[] args) {
		SpringApplication.run(BooksApplication.class, args);
	}

	// XSS defense in depth: even if a browser opens an API response directly,
	// nosniff = never guess "this JSON looks like HTML", CSP 'none' = no script, image, frame... may run or load.
	@Bean
	Filter securityHeaders() {
		return (request, response, chain) -> {
			HttpServletResponse http = (HttpServletResponse) response;
			http.setHeader("X-Content-Type-Options", "nosniff");
			http.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
			chain.doFilter(request, response);
		};
	}
}
