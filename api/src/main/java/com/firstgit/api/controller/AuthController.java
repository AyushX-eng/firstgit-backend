package com.firstgit.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAuthStatus(@AuthenticationPrincipal OAuth2User principal) {
        Map<String, Object> response = new HashMap<>();
        
        if (principal == null) {
            response.put("isAuthenticated", false);
            return ResponseEntity.ok(response);
        }

        response.put("isAuthenticated", true);
        response.put("username", principal.getAttribute("login"));
        response.put("avatarUrl", principal.getAttribute("avatar_url"));
        response.put("name", principal.getAttribute("name"));
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/token-check")
    public ResponseEntity<String> debugToken(
            @RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient authorizedClient) {
        
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return ResponseEntity.status(401).body("No valid token found in session.");
        }

        String tokenValue = authorizedClient.getAccessToken().getTokenValue();
        return ResponseEntity.ok("Token successfully resolved: " + tokenValue.substring(0, 5) + "...");
    }
}