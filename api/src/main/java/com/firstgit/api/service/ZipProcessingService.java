package com.firstgit.api.service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ZipProcessingService {

    public String processAndDeploy(MultipartFile file, String repoName, String githubToken) throws IOException {
        // Build the GitHub connection using the OAuth token from the session
        GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build();
        
        GHRepository repository;
        
        try {
            // Attempt to find the existing repository
            repository = github.getRepository(github.getMyself().getLogin() + "/" + repoName);
        } catch (GHFileNotFoundException e) {
            // Create a new one if it doesn't exist
            repository = github.createRepository(repoName)
                    .description("Deployed via FirstGit")
                    .private_(false)
                    .create();
        }

        // Save uploaded zip to a temp file and extract
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("firstgit-deploy-");
        java.io.File tempZip = tempDir.resolve(file.getOriginalFilename() == null ? "upload.zip" : file.getOriginalFilename()).toFile();
        file.transferTo(tempZip);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(tempZip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (rawName == null) continue;
                // normalize zip entry name to forward slashes and strip any leading slash
                String entryName = rawName.replace('\\','/');
                while (entryName.startsWith("/")) entryName = entryName.substring(1);
                if (entry.isDirectory() || entryName.endsWith("/")) continue;
                java.nio.file.Path outPath = tempDir.resolve(entryName).normalize();
                if (!outPath.startsWith(tempDir)) continue; // prevent zip-slip
                java.nio.file.Path parent = outPath.getParent();
                if (parent != null) {
                    try {
                        java.nio.file.Files.createDirectories(parent);
                    } catch (Exception dirEx) {
                        // if we can't create parent directories, skip this entry
                        continue;
                    }
                }
                try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(outPath)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) os.write(buffer, 0, len);
                } catch (Exception writeEx) {
                    // skip write failures and continue extracting remaining entries
                }
            }
        }

        // Use system git CLI to initialize a local repo, add files and push to remote
        java.nio.file.Path workDir = tempDir; // use extracted folder as git workdir
        // Read optional `.firstgit-preserve` manifest to whitelist paths/globs to keep
        java.util.List<String> preservePatterns = java.util.Collections.emptyList();
        java.nio.file.Path manifest = tempDir.resolve(".firstgit-preserve");
        if (java.nio.file.Files.exists(manifest)) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8);
                Set<String> set = new HashSet<>();
                for (String L : lines) {
                    String s = L == null ? "" : L.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    set.add(s);
                    if (set.size() >= 200) break;
                }
                preservePatterns = java.util.List.copyOf(set);
            } catch (Exception ignore) { preservePatterns = java.util.Collections.emptyList(); }
        }

        // Cleanup disabled temporarily for testing to avoid errors deleting nested files
        // try { cleanExtracted(tempDir, preservePatterns); } catch (Exception ignore) {}
        try {
            java.io.File wd = workDir.toFile();

            // Helper to run commands
            java.util.function.BiConsumer<String[],Boolean> run = (cmdArr, failOnError) -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmdArr);
                    pb.directory(wd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    StringBuilder out = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) out.append(line).append('\n');
                    }
                    int rc = p.waitFor();
                    if (rc != 0 && failOnError) throw new RuntimeException("Command failed: " + Arrays.toString(cmdArr) + "\n" + out.toString());
                } catch (Exception ex) {
                    if (failOnError) throw new RuntimeException(ex);
                }
            };

            // init repo
            run.accept(new String[] {"git", "init"}, true);

            // set author info
            String authorName = github.getMyself().getLogin();
            String authorEmail = github.getMyself().getEmail() == null ? (authorName + "@users.noreply.github.com") : github.getMyself().getEmail();
            run.accept(new String[] {"git", "config", "user.name", authorName}, false);
            run.accept(new String[] {"git", "config", "user.email", authorEmail}, false);

            // add & commit
            run.accept(new String[] {"git", "add", "."}, true);
            run.accept(new String[] {"git", "commit", "-m", "Deploy via FirstGit", "--author", authorName + " <" + authorEmail + ">"}, true);

            String owner = github.getMyself().getLogin();

            // Ensure remote exists (create repo if needed)
            try {
                repository = github.getRepository(owner + "/" + repoName);
            } catch (GHFileNotFoundException e) {
                repository = github.createRepository(repoName)
                        .description("Deployed via FirstGit")
                        .private_(false)
                        .create();
            }

            // Generate ephemeral SSH keypair using ssh-keygen (if available)
            java.nio.file.Path keyPath = java.nio.file.Files.createTempDirectory("firstgit-key-");
            java.nio.file.Path privateKey = keyPath.resolve("id_ed25519");
            java.nio.file.Path publicKey = keyPath.resolve("id_ed25519.pub");
            boolean keyGenOk = false;
            try {
                run.accept(new String[] {"ssh-keygen", "-t", "ed25519", "-f", privateKey.toString(), "-N", "", "-q"}, true);
                keyGenOk = java.nio.file.Files.exists(privateKey) && java.nio.file.Files.exists(publicKey);
            } catch (Exception ex) {
                keyGenOk = false;
            }

            String sshRemote = "git@github.com:" + owner + "/" + repoName + ".git";

            if (keyGenOk) {
                // read public key
                String pub = java.nio.file.Files.readString(publicKey);
                // create deploy key via GitHub REST API
                HttpClient http = HttpClient.newHttpClient();
                String url = "https://api.github.com/repos/" + owner + "/" + repoName + "/keys";
                String title = "firstgit-deploy-" + System.currentTimeMillis();
                String bodyJson = "{\"title\":\"" + title + "\",\"key\":\"" + pub.replace("\n","\\n").replace("\"","\\\"") + "\",\"read_only\":false}";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "token " + githubToken)
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int keyId = -1;
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(resp.body());
                    if (m.find()) keyId = Integer.parseInt(m.group(1));
                }

                // add ssh remote and push using GIT_SSH_COMMAND to use private key
                run.accept(new String[] {"git", "remote", "add", "origin", sshRemote}, true);
                run.accept(new String[] {"git", "branch", "-M", "main"}, false);

                ProcessBuilder pbPush = new ProcessBuilder(new String[] {"git", "push", "-u", "origin", "main"});
                pbPush.directory(wd);
                String sshCmd = "ssh -i \"" + privateKey.toString() + "\" -o IdentitiesOnly=yes -o StrictHostKeyChecking=no";
                pbPush.environment().put("GIT_SSH_COMMAND", sshCmd);
                pbPush.redirectErrorStream(true);
                Process ppush = pbPush.start();
                StringBuilder pout = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(ppush.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) pout.append(line).append('\n');
                }
                int rc = ppush.waitFor();

                // cleanup deploy key from GitHub and local keys
                if (keyId != -1) {
                    String delUrl = "https://api.github.com/repos/" + owner + "/" + repoName + "/keys/" + keyId;
                    HttpRequest delReq = HttpRequest.newBuilder()
                            .uri(URI.create(delUrl))
                            .header("Authorization", "token " + githubToken)
                            .header("Accept", "application/vnd.github+json")
                            .DELETE()
                            .build();
                    try { http.send(delReq, HttpResponse.BodyHandlers.discarding()); } catch (Exception ignore) {}
                }
                try { java.nio.file.Files.deleteIfExists(privateKey); java.nio.file.Files.deleteIfExists(publicKey); java.nio.file.Files.deleteIfExists(keyPath); } catch (Exception ignore) {}

                if (rc == 0) {
                    return repository.getHtmlUrl().toString();
                }
                // else fallthrough to fallback upload
            } else {
                // ssh-keygen not available or failed - fall back to HTTPS method
                String remoteUrl = "https://" + owner + ":" + githubToken + "@github.com/" + owner + "/" + repoName + ".git";
                run.accept(new String[] {"git", "remote", "add", "origin", remoteUrl}, true);
                run.accept(new String[] {"git", "branch", "-M", "main"}, false);
                run.accept(new String[] {"git", "push", "-u", "origin", "main"}, true);
                return repository.getHtmlUrl().toString();
            }
        } catch (Exception e) {
            // Log the exception stacktrace to help debugging of deployment failures
            try { e.printStackTrace(); } catch (Exception ignore) {}
            try { System.err.println("ZipProcessingService error: " + e.getMessage()); } catch (Exception ignore) {}
            // If JGit push fails, fall back to API upload of files
            // (keep previous behavior: upload text files and release)
            // Extracted files will be uploaded via API as best-effort
            java.util.List<java.nio.file.Path> extractedFiles = new java.util.ArrayList<>();
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(tempZip))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    java.nio.file.Path outPath = tempDir.resolve(entry.getName()).normalize();
                    if (!outPath.startsWith(tempDir)) continue;
                    extractedFiles.add(outPath);
                }
            } catch (Exception ignored) {}

            for (java.nio.file.Path p : extractedFiles) {
                String relPath = tempDir.relativize(p).toString().replace('\\', '/');
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(p);
                    boolean binary = false;
                    int scan = Math.min(bytes.length, 1024);
                    for (int i = 0; i < scan; i++) if (bytes[i] == 0) { binary = true; break; }
                    if (!binary) {
                        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        try {
                            repository.createContent().path(relPath).content(content).message("Add " + relPath).commit();
                        } catch (Exception ex) {
                            try { org.kohsuke.github.GHContent existing = repository.getFileContent(relPath); existing.update(content, "Update " + relPath); } catch (Exception ignore){}
                        }
                    }
                } catch (Exception ignore) {}
            }

            try {
                String tag = "deployed-" + System.currentTimeMillis();
                org.kohsuke.github.GHRelease release = repository.createRelease(tag).name("Deployment " + tag).body("Deployed via FirstGit").create();
                try { release.uploadAsset(tempZip, "application/zip"); } catch (IOException ignore) {}
            } catch (Exception ignore) {}
        }

        return repository.getHtmlUrl().toString();
    }

    // Remove common heavy/build/generated files to reduce payload size while keeping source code
    private void cleanExtracted(java.nio.file.Path dir, java.util.List<String> preservePatterns) throws IOException {
        java.util.Set<String> removeDirs = new java.util.HashSet<>(java.util.Arrays.asList(
                "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode", ".cache", "venv", ".venv", "__pycache__", ".pytest_cache", ".parcel-cache", "out"));
        java.util.Set<String> removeExt = new java.util.HashSet<>(java.util.Arrays.asList(
                ".class", ".jar", ".war", ".exe", ".dll", ".pdb", ".iso", ".mp4", ".mov", ".psd", ".log", ".tmp", ".zip"));

        if (!java.nio.file.Files.exists(dir)) return;
        java.util.List<java.nio.file.Path> paths = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(dir)) { stream.forEach(paths::add); }
        // delete files before directories
        paths.sort((a,b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        for (java.nio.file.Path p : paths) {
            try {
                String rel = dir.relativize(p).toString().replace('\\','/');
                // if preserved by manifest patterns, skip
                if (matchesAny(rel, preservePatterns)) continue;

                if (java.nio.file.Files.isDirectory(p)) {
                    String name = p.getFileName() == null ? "" : p.getFileName().toString();
                    if (removeDirs.contains(name) || name.equalsIgnoreCase(".git")) {
                        deleteRecursively(p);
                    }
                } else if (java.nio.file.Files.isRegularFile(p)) {
                    String name = p.getFileName() == null ? "" : p.getFileName().toString();
                    // remove common unwanted files
                    if (name.equals(".DS_Store") || name.endsWith(".iml") || name.endsWith("~")) {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignore) {}
                        continue;
                    }
                    boolean removed = false;
                    for (String ext : removeExt) {
                        if (name.toLowerCase().endsWith(ext)) { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignore) {} removed = true; break; }
                    }
                    if (removed) continue;
                }
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
    }

    private boolean matchesAny(String rel, java.util.List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return false;
        for (String pat : patterns) {
            if (pat.equals("")) continue;
            // exact match
            if (rel.equals(pat)) return true;
            try {
                if (pat.contains("*") || pat.contains("?")) {
                    PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pat);
                    if (m.matches(Paths.get(rel))) return true;
                } else if (pat.startsWith("*.") && rel.toLowerCase().endsWith(pat.substring(1).toLowerCase())) {
                    return true;
                } else if (pat.endsWith("/**")) {
                    String prefix = pat.substring(0, pat.length()-3);
                    if (rel.startsWith(prefix)) return true;
                }
            } catch (Exception ignore) {}
        }
        return false;
    }

    private void deleteRecursively(java.nio.file.Path path) throws IOException {
        if (!java.nio.file.Files.exists(path)) return;
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(path)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignore) {}
            });
        }
    }

    // Backwards-compatible helper used by controllers that include visibility and token separately
    public String deployZipToGithub(MultipartFile file, String repoName, boolean isPrivate, String accessToken) throws IOException {
        // For now, ignore `isPrivate` and delegate to the existing implementation.
        return processAndDeploy(file, repoName, accessToken);
    }
}