package com.firstgit.api.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.firstgit.api.service.ZipProcessingService;

@RestController
@RequestMapping("/api/deploy")
public class DeploymentController {

    private final ZipProcessingService zipProcessingService;

    public DeploymentController(ZipProcessingService zipProcessingService) {
        this.zipProcessingService = zipProcessingService;
    }

    // NEW ENDPOINT: Fetch user's existing GitHub repositories
    @GetMapping("/repos")
    public ResponseEntity<List<String>> getExistingRepositories(
            @RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient authorizedClient) {
        
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return ResponseEntity.status(401).build();
        }

        String githubToken = authorizedClient.getAccessToken().getTokenValue();
        
        // GitHub API endpoint for user repositories (sorted by most recently updated)
        String githubApiUrl = "https://api.github.com/user/repos?affiliation=owner&sort=updated&per_page=100";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    githubApiUrl,
                    Objects.requireNonNull(HttpMethod.GET),
                    entity,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            // Guard against null body and non-2xx responses
            List<Map<String, Object>> body = response.getBody();
            if (response.getStatusCode().is2xxSuccessful() && body != null) {
                List<String> repoNames = body.stream()
                        .map(repo -> (String) repo.get("name"))
                        .collect(Collectors.toList());

                return ResponseEntity.ok(repoNames);
            }
            return ResponseEntity.status(response.getStatusCode()).build();
        } catch (RestClientException e) {
            return ResponseEntity.status(502).build();
        }
    }

    // EXISTING ENDPOINT: File Upload
    @PostMapping("/submit")
    public ResponseEntity<String> deployProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam("repoName") String repoName,
            @RegisteredOAuth2AuthorizedClient("github") OAuth2AuthorizedClient authorizedClient) {

        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return ResponseEntity.status(401).body("Authentication session expired. Please log in again.");
        }

        String githubToken = authorizedClient.getAccessToken().getTokenValue();

        try {
            String repoUrl = zipProcessingService.processAndDeploy(file, repoName, githubToken);
            return ResponseEntity.ok(repoUrl);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Deployment failed: " + e.getMessage());
        }
    }
}