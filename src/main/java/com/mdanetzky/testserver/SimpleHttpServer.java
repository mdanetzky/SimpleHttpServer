package com.mdanetzky.testserver;

import com.sun.net.httpserver.*;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SimpleHttpServer {

    public static final SSLContext sslContext = HttpsInitializer.tryToGetSslContext();
    private static SimpleHttpServer testServer;
    private static SimpleHttpServer testServerSsl;
    private static int contextSuffix = 0;
    private final HttpServer httpServer;

    private SimpleHttpServer(boolean ssl) throws IOException {
        httpServer = createServer(ssl);
        bindPort(httpServer);
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
    }

    private static void bindPort(HttpServer httpServer) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        httpServer.bind(address, 0);
    }

    public static Builder getBuilder() {
        return new Builder();
    }

    private static synchronized String init() throws IOException {
        if (testServer == null)
            testServer = new SimpleHttpServer(false);
        return "/" + contextSuffix++;
    }

    public static synchronized void stop() {
        stopHttpServer();
        stopHttpsServer();
    }

    private static void stopHttpsServer() {
        stopServer(testServerSsl);
        testServerSsl = null;
    }

    private static void stopHttpServer() {
        stopServer(testServer);
        testServer = null;
    }

    private static void stopServer(SimpleHttpServer server) {
        if (server != null)
            server.stopInstance();
    }

    private static synchronized String initSsl() throws IOException {
        if (testServerSsl == null)
            testServerSsl = new SimpleHttpServer(true);
        return "/" + contextSuffix++;
    }

    private HttpServer createServer(boolean ssl) throws IOException {
        if (ssl)
            return createHttpsServer();
        return HttpServer.create();
    }

    private HttpServer createHttpsServer() throws IOException {
        HttpsServer httpsServer = HttpsServer.create();
        httpsServer.setHttpsConfigurator(new MyHttpsConfigurator());
        return httpsServer;
    }

    private void addContext(String contextPath, HttpHandler httpHandler) {
        httpServer.createContext(contextPath, httpHandler);
    }

    private String getOrigin() {
        return getProtocol() + "://" +
                httpServer.getAddress().getHostName() + ":" +
                httpServer.getAddress().getPort();
    }

    private String getProtocol() {
        return httpServer instanceof HttpsServer ? "https" : "http";
    }

    private void stopInstance() {
        httpServer.stop(0);
    }

    public static class Builder {

        private boolean ssl = false;
        private int responseCode = 200;
        private Map<String, List<String>> headers = null;
        private byte[] content = "".getBytes();
        private HttpHandler handler = BasicHandler.getHandler(this);

        public Builder setContent(String content) {
            this.content = content.getBytes();
            return this;
        }

        public Builder setSsl() {
            this.ssl = true;
            return this;
        }

        public Builder setResponseCode(int responseCode) {
            this.responseCode = responseCode;
            return this;
        }

        public Builder setHeaders(String headers) {
            setHeaders(HttpHeaderParser.parseHeaders(headers));
            return this;
        }

        public Builder setHeaders(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        public String startEcho() throws IOException {
            setHandler(EchoHandler.getHandler());
            return start();
        }

        public Builder setHandler(HttpHandler handler) {
            this.handler = handler;
            return this;
        }

        public String start() throws IOException {
            String contextPath = initServer();
            getTestServer().addContext(contextPath, handler);
            return getTestServer().getOrigin() + contextPath;
        }

        private String initServer() throws IOException {
            if (ssl)
                return initSsl();
            return init();
        }

        private SimpleHttpServer getTestServer() {
            if (ssl)
                return testServerSsl;
            return testServer;
        }
    }

    private static class BasicHandler {

        private static HttpHandler getHandler(Builder builder) {
            return httpExchange -> {
                fillHeaders(builder.headers, httpExchange);
                httpExchange.sendResponseHeaders(builder.responseCode, builder.content.length);
                sendResponse(builder.content, httpExchange);
            };
        }

        private static void fillHeaders(Map<String, List<String>> headers, HttpExchange httpExchange) {
            if (headers != null) {
                Headers responseHeaders = httpExchange.getResponseHeaders();
                responseHeaders.putAll(headers);
            }
        }

        private static void sendResponse(byte[] contentBytes, HttpExchange httpExchange) throws IOException {
            OutputStream os = httpExchange.getResponseBody();
            os.write(contentBytes);
            os.close();
        }
    }

    private static class EchoHandler {

        private static HttpHandler getHandler() {
            return httpExchange -> {
                String echo = getEchoString(httpExchange);
                byte[] contentBytes = echo.getBytes();
                httpExchange.sendResponseHeaders(200, contentBytes.length);
                BasicHandler.sendResponse(contentBytes, httpExchange);
            };
        }

        private static String getEchoString(HttpExchange httpExchange) throws IOException {
            return getFirstLineOfRequest(httpExchange) +
                    getRequestHeaders(httpExchange) +
                    "\nREQUEST BODY:\n" +
                    getRequestBody(httpExchange);
        }

        private static String getFirstLineOfRequest(HttpExchange httpExchange) {
            String head = httpExchange.getRequestMethod() + " ";
            head += httpExchange.getRequestURI() + " ";
            return head + httpExchange.getProtocol() + "\n";
        }

        private static String getRequestHeaders(HttpExchange httpExchange) {
            return httpExchange.getRequestHeaders().entrySet().stream()
                    .map(EchoHandler::formatHeader)
                    .collect(Collectors.joining("\n"));
        }

        private static String getRequestBody(HttpExchange httpExchange) throws IOException {
            InputStream is = httpExchange.getRequestBody();
            String body = readStream(is);
            is.close();
            return body;
        }

        private static String readStream(InputStream is) throws IOException {
            StringBuilder buffer = new StringBuilder();
            int b;
            while ((b = is.read()) != -1)
                buffer.append((char) b);
            return buffer.toString();
        }

        private static String formatHeader(Map.Entry<String, List<String>> header) {
            return header.getKey() + ": " +
                    header.getValue().stream()
                            .map(String::trim)
                            .collect(Collectors.joining("; "));
        }
    }

    private static class HttpsInitializer {

        private static final String KEYSTORE_BASE_64 =
                "/u3+7QAAAAIAAAABAAAAAQAFYWxpYXMAAAFU3TznKQAAAY8wggGLMA4GCisGAQQBKgIRAQEFAASCAXc0adGoYu2VcSzQzoJZFa9n8mpbULjvfZ+I/n" +
                        "EWPMyU8AxWNQuiFcdp+1zRdxUw7mUT8zH6g6K+amyvFzD5d0JrGM2S0ozBCohYbob5LROcZ23PeWq/ikqnMgZviYHUKgihJE8h0CC3FtWYUm1lP+" +
                        "8+Jn4nNoZaugzVlzmJzgjNc39Ha0W88/4OeH/TaxlcULQ8enZViIyWjxvIyNM9v50jhYQdhcPLJ4R/1RXEYL2PL+jBL0eUvOg3J9fHFOzRCZZE1/" +
                        "gUdoeDFISn+MkBVClUXmar+jxciZp8a+AffqNgBdkkCc2MV4Uq22zftZYzELjo6F/umTd5MzT7UjH/M2a5/I4dwX33YimQgiKgGRP9s2GZPnqv9Y" +
                        "endXHrvLtwR5XFApnkAUfqS8/2bhPXppDwfQZDGdtUswskgvBVIGaVS6QQQS22NsxHBoJmIPZqpimTFVzabhMlbFUOvsLb+m/sQC5jglYWMVPXcj" +
                        "7SrRD692mTgEYVLUIAAAABAAVYLjUwOQAAAzkwggM1MIIC86ADAgECAgRtH94+MAsGByqGSM44BAMFADBsMRAwDgYDVQQGEwdVbmtub3duMRAwDg" +
                        "YDVQQIEwdVbmtub3duMRAwDgYDVQQHEwdVbmtub3duMRAwDgYDVQQKEwdVbmtub3duMRAwDgYDVQQLEwdVbmtub3duMRAwDgYDVQQDEwdVbmtub3" +
                        "duMB4XDTE2MDUyMzEwNTAzNloXDTE2MDgyMTEwNTAzNlowbDEQMA4GA1UEBhMHVW5rbm93bjEQMA4GA1UECBMHVW5rbm93bjEQMA4GA1UEBxMHVW" +
                        "5rbm93bjEQMA4GA1UEChMHVW5rbm93bjEQMA4GA1UECxMHVW5rbm93bjEQMA4GA1UEAxMHVW5rbm93bjCCAbgwggEsBgcqhkjOOAQBMIIBHwKBgQ" +
                        "D9f1OBHXUSKVLfSpwu7OTn9hG3UjzvRADDHj+AtlEmaUVdQCJR+1k9jVj6v8X1ujD2y5tVbNeBO4AdNG/yZmC3a5lQpaSfn+gEexAiwk+7qdf+t8" +
                        "Yb+DtX58aophUPBPuD9tPFHsMCNVQTWhaRMvZ1864rYdcq7/IiAxmd0UgBxwIVAJdgUI8VIwvMspK5gqLrhAvwWBz1AoGBAPfhoIXWmz3ey7yrXD" +
                        "a4V7l5lK+7+jrqgvlXTAs9B4JnUVlXjrrUWU/mcQcQgYC0SRZxI+hMKBYTt88JMozIpuE8FnqLVHyNKOCjrh4rs6Z1kW6jfwv6ITVi8ftiegEkO8" +
                        "yk8b6oUZCJqIPf4VrlnwaSi2ZegHtVJWQBTDv+z0kqA4GFAAKBgQDy1P+v4aagD2bf6276UdHpk0AGxEg4kovLsaztkK2KYI1GoJ4zsOXrjxTFKE" +
                        "3wq3VJxPQP+MBGAvgre0JKL6ccr//XNJtux87wQ/1vZDp4dBdZCumzvrJ4wrm4LP70hxOHBg8jIwxF7gcxE4ZQW1VScQhQpMRv670vJX9jLJ5meq" +
                        "MhMB8wHQYDVR0OBBYEFBe/PBGeFmst0xCIHZyOeyRgMXcbMAsGByqGSM44BAMFAAMvADAsAhQ1VadgYg/2lmBrn/joFXFpV1uUYgIUNYn+v8ZONW" +
                        "Nl2BbSNFyVY7y4nU2zwSwWo1yjewoT/h6v8U1iNIVyrw==";
        private static final char[] KEYSTORE_PASSWORD = "simulator".toCharArray();

        static SSLContext tryToGetSslContext() {
            try {
                return getSslContext();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
            return null;
        }

        private static SSLContext getSslContext() throws NoSuchAlgorithmException, KeyStoreException, IOException,
                CertificateException, UnrecoverableKeyException, KeyManagementException {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyStore keyStore = getKeyStore();
            KeyManagerFactory keyManagerFactory = getKeyManagerFactory(keyStore);
            TrustManagerFactory trustManagerFactory = getTrustManagerFactory(keyStore);
            sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            return sslContext;
        }

        private static KeyStore getKeyStore() throws
                KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(getKeystoreStream(), KEYSTORE_PASSWORD);
            return keyStore;
        }

        private static KeyManagerFactory getKeyManagerFactory(KeyStore keyStore) throws
                NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD);
            return keyManagerFactory;
        }

        private static TrustManagerFactory getTrustManagerFactory(KeyStore keyStore) throws
                NoSuchAlgorithmException, KeyStoreException {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
            trustManagerFactory.init(keyStore);
            return trustManagerFactory;
        }

        private static InputStream getKeystoreStream() {
            byte[] data = Base64.getDecoder().decode(KEYSTORE_BASE_64);
            return new ByteArrayInputStream(data);
        }
    }

    private static class MyHttpsConfigurator extends HttpsConfigurator {

        MyHttpsConfigurator() {
            super(sslContext);
        }

        @Override
        public void configure(HttpsParameters params) {
            SSLEngine engine = sslContext.createSSLEngine();
            params.setNeedClientAuth(false);
            params.setCipherSuites(engine.getEnabledCipherSuites());
            params.setProtocols(engine.getEnabledProtocols());
            params.setSSLParameters(sslContext.getDefaultSSLParameters());
        }
    }

    private static class HttpHeaderParser {

        static Map<String, List<String>> parseHeaders(String headers) {
            String[] headerLines = headers.split("[\\r\\n]+");
            return parseHeaderLines(headerLines);
        }

        private static Map<String, List<String>> parseHeaderLines(String[] headerLines) {
            Map<String, List<String>> parsedHeaders = new LinkedHashMap<>();
            for (String header : headerLines)
                if (header.contains(":"))
                    parseHeader(parsedHeaders, header);
            return parsedHeaders;
        }

        private static void parseHeader(Map<String, List<String>> parsedHeaders, String header) {
            String headerName = getStringBeforeFirstColon(header).trim();
            String headerValue = getStringAfterFirstColon(header).trim();
            setParsedHeader(parsedHeaders, headerName, headerValue);
        }

        private static String getStringBeforeFirstColon(String string) {
            return string.substring(0, getIndexOfFirstColon(string));
        }

        private static String getStringAfterFirstColon(String string) {
            return string.substring(getIndexOfFirstColon(string) + 1);
        }

        private static void setParsedHeader(Map<String, List<String>> parsedHeaders, String headerName, String headerValue) {
            if (!parsedHeaders.containsKey(headerName))
                parsedHeaders.put(headerName, new LinkedList<>());
            parsedHeaders.get(headerName).add(headerValue);
        }

        private static int getIndexOfFirstColon(String string) {
            return string.indexOf(":");
        }
    }
}
