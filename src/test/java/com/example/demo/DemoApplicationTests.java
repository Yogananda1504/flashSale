package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.demo.controllers.AuthController;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DemoApplicationTests {
    @Autowired
    private AuthController authController;

    @Test
    void advertisedDemoCredentialsWork() {
        var response = authController.login(new AuthController.LoginRequest("demo@example.com", "password"));
        assertEquals(200, response.getStatusCode().value());
        assertInstanceOf(AuthController.LoginResponse.class, response.getBody());
    }

	@Test
	void contextLoads() {
	}

}
