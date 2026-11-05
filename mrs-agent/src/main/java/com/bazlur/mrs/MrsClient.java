package com.bazlur.mrs;

import module java.base;
import module java.net.http;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.cert.CertificateFactory;
import tools.jackson.databind.JsonNode;

/** Fixed-origin HTTPS client. Credentials and tokens never enter tool arguments/results. */
public final class MrsClient {
    public enum Operation {
        WHOAMI("whoami"), GET_ORDER("getOrder"), REQUEST("requestCancellation"), CONFIRM("confirmCancellation");
        final String path;
        Operation(String path) { this.path = path; }
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        Failure(int status) { super("MRS returned HTTP " + status); this.status = status; }
        public int status() { return status; }
    }
    private final URI base;
    private final HttpClient http;
    private String token;

    public MrsClient(URI base, Path certificate) throws Exception {
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || base.getPath().endsWith("/")) {
            throw new IllegalArgumentException("MRS URL must be an HTTPS service URL without a trailing slash");
        }
        this.base = base;
        var trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        try (var input = Files.newInputStream(certificate)) {
            trust.setCertificateEntry("mrs", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, managers.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** MRS's documented SCRAM-SHA-256 exchange, verified against the shipped Python SDK. */
    public MrsClient login(String username, char[] password) throws Exception {
        token = null;
        if (!username.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Invalid login name");
        var nonceBytes = new byte[20];
        new SecureRandom().nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        var challenge = request("POST", "/authentication/login", Map.of(
                "authApp", "OrderMRS", "username", username, "nonce", nonce, "sessionType", "bearer"));
        String serverNonce = challenge.path("nonce").asString();
        int iterations = challenge.path("iterations").asInt();
        if (!serverNonce.startsWith(nonce) || serverNonce.length() <= nonce.length()
                || iterations < 1000 || iterations > 1_000_000 || challenge.path("salt").size() > 1024) {
            throw new IOException("Invalid MRS authentication challenge");
        }
        byte[] salt = new byte[challenge.path("salt").size()];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) challenge.path("salt").get(i).asInt();
        String message = "n=" + username + ",r=" + nonce + ",r=" + serverNonce + ",s="
                + Base64.getEncoder().encodeToString(salt) + ",i=" + iterations + ",r=" + serverNonce;
        var spec = new PBEKeySpec(password, salt, iterations, 256);
        byte[] salted;
        try { salted = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        byte[] key = sign(salted, "Client Key");
        byte[] signature = sign(MessageDigest.getInstance("SHA-256").digest(key), message);
        var proof = new ArrayList<Integer>();
        for (int i = 0; i < key.length; i++) proof.add((key[i] ^ signature[i]) & 255);
        Arrays.fill(salted, (byte) 0);
        Arrays.fill(key, (byte) 0);
        var result = request("POST", "/authentication/login", Map.of(
                "clientProof", proof, "nonce", serverNonce, "state", "response"));
        token = result.path("accessToken").asString();
        if (token.isBlank()) throw new IOException("MRS did not issue a token");
        return this;
    }

    private static byte[] sign(byte[] key, String message) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    public JsonNode orders() throws Exception {
        // Fixed bounded query; the model cannot supply filters, URLs, or customer IDs.
        String query = URLEncoder.encode("{\"$orderby\":{\"createdAt\":\"DESC\",\"orderId\":\"DESC\"}}", StandardCharsets.UTF_8);
        return request("GET", "/shop/orders?limit=10&q=" + query, null);
    }

    public JsonNode cancellation(UUID id) throws Exception {
        return request("GET", "/shop/requests/" + id, null);
    }

    public JsonNode cancellations() throws Exception {
        return request("GET", "/shop/requests?limit=10", null);
    }

    public JsonNode call(Operation operation, Map<String, ?> arguments) throws Exception {
        var envelope = request("POST", "/shop/" + operation.path, arguments);
        var result = envelope.at("/resultSets/0/items/0/result");
        if (!result.isObject() || !result.has("ok")) throw new IOException("Unexpected MRS procedure response");
        return result;
    }

    public String principalId() throws Exception {
        String id = call(Operation.WHOAMI, Map.of()).path("principalId").asString();
        if (!id.matches("[0-9A-F]{32}")) throw new IOException("MRS did not return an authenticated identity");
        return id;
    }

    private JsonNode request(String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.encode(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new Failure(response.statusCode());
            var bytes = input.readNBytes(131_073);
            if (bytes.length > 131_072) throw new IOException("MRS response exceeds the client limit");
            return Json.parse(new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
