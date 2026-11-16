package ru.egor.reviewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** Small in-memory implementation of the explicitly selected internship scope. */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Set<String> teams = new HashSet<>();
    private final Map<String, PullRequest> requests = new LinkedHashMap<>();

    record User(String user_id, String username, String team_name, boolean is_active) {}
    record PullRequest(String pull_request_id, String pull_request_name, String author_id,
                       String status, List<String> assigned_reviewers) {}
    record Result(int status, Object body) {}
    static final class ApiError extends RuntimeException {
        final int status;
        final String code;
        ApiError(int status, String code, String message) {
            super(message); this.status = status; this.code = code;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        var app = new Main();
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
        System.out.println("Reviewer service listening on " + port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Result result;
        try {
            result = route(exchange);
        } catch (ApiError e) {
            result = new Result(e.status, Map.of("error", Map.of("code", e.code, "message", e.getMessage())));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            result = new Result(400, Map.of("error", Map.of("code", "BAD_REQUEST", "message", "Invalid request")));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            result = new Result(500, Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", "Internal error")));
        }
        byte[] body = JSON.writeValueAsBytes(result.body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(result.status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
        exchange.close();
    }

    // One lock keeps validation and updates atomic for concurrent HTTP requests.
    private synchronized Result route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/health") && method.equals("GET")) return new Result(200, Map.of("status", "ok"));
        if (path.equals("/users/getReview") && method.equals("GET")) {
            String id = query(exchange, "user_id");
            requireUser(id);
            var selected = requests.values().stream().filter(pr -> pr.assigned_reviewers.contains(id))
                    .map(pr -> Map.of("pull_request_id", pr.pull_request_id, "pull_request_name", pr.pull_request_name,
                            "author_id", pr.author_id, "status", pr.status)).toList();
            return new Result(200, Map.of("user_id", id, "pull_requests", selected));
        }
        if (!Set.of("/team/add", "/pullRequest/create").contains(path))
            throw new ApiError(404, "NOT_FOUND", "Endpoint outside implemented scope");
        if (!method.equals("POST")) throw new ApiError(405, "METHOD_NOT_ALLOWED", "Use POST");
        byte[] bytes = exchange.getRequestBody().readNBytes(65_537);
        if (bytes.length > 65_536) throw new ApiError(413, "BAD_REQUEST", "Body too large");
        JsonNode body = JSON.readTree(bytes);
        if (body == null || !body.isObject()) throw new IllegalArgumentException();
        if (path.equals("/team/add")) {
            String name = text(body, "team_name");
            if (teams.contains(name)) throw new ApiError(400, "TEAM_EXISTS", "Team already exists");
            JsonNode members = body.get("members");
            if (members == null || !members.isArray()) throw new IllegalArgumentException();
            var pending = new LinkedHashMap<String, User>();
            for (JsonNode member : members) {
                String id = text(member, "user_id");
                if (!member.has("is_active") || !member.get("is_active").isBoolean()) throw new IllegalArgumentException();
                User user = new User(id, text(member, "username"), name, member.get("is_active").booleanValue());
                if (pending.putIfAbsent(id, user) != null) throw new IllegalArgumentException();
            }
            users.putAll(pending);
            teams.add(name);
            return new Result(201, Map.of("team", Map.of("team_name", name, "members", pending.values().stream()
                    .map(u -> Map.of("user_id", u.user_id, "username", u.username, "is_active", u.is_active)).toList())));
        }
        String id = text(body, "pull_request_id");
        String name = text(body, "pull_request_name");
        String authorId = text(body, "author_id");
        if (requests.containsKey(id)) throw new ApiError(409, "PR_EXISTS", "PR already exists");
        User author = requireUser(authorId);
        var candidates = new ArrayList<>(users.values().stream()
                .filter(u -> u.team_name.equals(author.team_name) && u.is_active && !u.user_id.equals(authorId))
                .map(User::user_id).toList());
        Collections.shuffle(candidates);
        var reviewers = List.copyOf(candidates.subList(0, Math.min(2, candidates.size())));
        var pr = new PullRequest(id, name, authorId, "OPEN", reviewers);
        requests.put(id, pr);
        return new Result(201, Map.of("pr", pr));
    }

    private User requireUser(String id) {
        User user = users.get(id);
        if (user == null) throw new ApiError(404, "NOT_FOUND", "User not found");
        return user;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw new IllegalArgumentException();
        return value.textValue();
    }
    private static String query(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key) && pair.length == 2) {
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (!value.isBlank()) return value;
            }
        }
        throw new IllegalArgumentException();
    }
}
