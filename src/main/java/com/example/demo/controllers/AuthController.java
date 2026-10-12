package com.example.demo.controllers;

import com.example.demo.security.JwtService;
import com.example.demo.models.User;
import com.example.demo.services.InventoryStore;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:5173")
public class AuthController {
    private final JwtService jwtService;
    private final InventoryStore store;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtService jwtService, InventoryStore store, PasswordEncoder passwordEncoder) {
        this.jwtService = jwtService;
        this.store = store;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        User user = store.getUser(email);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }
        return ResponseEntity.ok(new LoginResponse(
                jwtService.createToken(user.getEmail()), user.getEmail()));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request.name() == null || request.name().isBlank()
                || request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().length() < 8) {
            return ResponseEntity.badRequest().body("Name, email, and a password of at least 8 characters are required");
        }
        String email = request.email().trim().toLowerCase();
        User user = store.createUser(
                request.name().trim(), email, passwordEncoder.encode(request.password()));
        if (user == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An account with that email already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new LoginResponse(jwtService.createToken(user.getEmail()), user.getEmail()));
    }

    public record LoginRequest(String email, String password) {
    }

    public record RegisterRequest(String name, String email, String password) {
    }

    public record LoginResponse(String token, String email) {
    }
}
