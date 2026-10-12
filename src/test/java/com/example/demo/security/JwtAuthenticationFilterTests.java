package com.example.demo.security;

import com.example.demo.services.InventoryStore;
import com.example.demo.models.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTests {
    private final JwtService jwt = mock(JwtService.class);
    private final InventoryStore store = mock(InventoryStore.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, store);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsTokenForAnAccountLostAfterRestart() throws Exception {
        when(jwt.username("token")).thenReturn("missing@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsTokenForAnExistingAccount() throws Exception {
        when(jwt.username("token")).thenReturn("demo@example.com");
        when(store.getUser("demo@example.com")).thenReturn(new User("user", "Demo", "demo@example.com", "hash"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals("demo@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(chain).doFilter(request, response);
    }
}
