package com.mdanetzky.testserver.tests;

import com.mdanetzky.testserver.SimpleHttpServer;
import com.sun.net.httpserver.HttpHandler;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.xml.sax.SAXException;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SimpleHttpServerTest {

    private static final String TEST_CONTENT = "test content";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void http() throws IOException, SAXException {
        String url = SimpleHttpServer.getBuilder()
                .setContent(TEST_CONTENT)
                .start();
        String response = HttpUtil.getHttpText(url);
        assertEquals(response, TEST_CONTENT);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    public void https() throws IOException, SAXException {
        String url = SimpleHttpServer.getBuilder()
                .setSsl()
                .setContent(TEST_CONTENT)
                .start();
        String response = HttpUtil.getHttpText(url);
        assertEquals(response, TEST_CONTENT);
        assertTrue(url.startsWith("https://"));
    }

    @Test
    public void echo() throws IOException, SAXException {
        String url = SimpleHttpServer.getBuilder().startEcho();
        URLConnection connection = HttpUtil.getUrlConnection(url);
        connection.setRequestProperty("Referer", "http://localhost");
        String response = HttpUtil.readUrlConnection(connection);
        assertTrue(response.contains("Referer"));
    }

    @Test
    public void responseCode500() throws IOException {
        String responseCode500Url = SimpleHttpServer.getBuilder()
                .setResponseCode(500)
                .start();
        URLConnection connection = HttpUtil.getUrlConnection(responseCode500Url);
        thrown.expect(IOException.class);
        thrown.expectMessage(Matchers.containsString("Server returned HTTP response code: 500 for URL"));
        HttpUtil.readUrlConnection(connection);
    }

    @Test
    public void responseCode402() throws IOException {
        String responseCode500Url = SimpleHttpServer.getBuilder()
                .setResponseCode(402)
                .start();
        URLConnection connection = HttpUtil.getUrlConnection(responseCode500Url);
        thrown.expect(IOException.class);
        thrown.expectMessage(Matchers.containsString("Server returned HTTP response code: 402 for URL"));
        HttpUtil.readUrlConnection(connection);
    }

    @Test
    public void sendsParsedHeaders() throws IOException {
        String url = SimpleHttpServer.getBuilder()
                .setHeaders("My1stHeader: My1stHeadersValue\nMy2ndHeader: My2ndHeadersValue")
                .start();
        assertTrue(HttpUtil.getHttpResponseHeaders(url).contains("My1stHeader: My1stHeadersValue"));
        assertTrue(HttpUtil.getHttpResponseHeaders(url).contains("My2ndHeader: My2ndHeadersValue"));
    }

    @Test
    public void sendsRedirect() throws IOException {
        String redirectTargetUrl = SimpleHttpServer.getBuilder()
                .setContent(TEST_CONTENT)
                .start();
        String url = SimpleHttpServer.getBuilder()
                .setResponseCode(302)
                .setHeaders("Location: " + redirectTargetUrl)
                .start();
        assertTrue(HttpUtil.getHttpText(url).equals(TEST_CONTENT));
    }

    @Test
    public void sendsHeadersMap() throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("My1stHeader", Collections.singletonList("My1stHeadersValue"));
        headers.put("My2ndHeader", Collections.singletonList("My2ndHeadersValue"));
        String url = SimpleHttpServer.getBuilder()
                .setHeaders(headers)
                .start();
        assertTrue(HttpUtil.getHttpResponseHeaders(url).contains("My1stHeader: My1stHeadersValue"));
        assertTrue(HttpUtil.getHttpResponseHeaders(url).contains("My2ndHeader: My2ndHeadersValue"));
    }

    @Test
    public void lambdaServer() throws IOException {
        byte[] testContent = TEST_CONTENT.getBytes();
        String url = SimpleHttpServer.getBuilder()
                .setHandler(getSimpleHttpHandlerLambda(testContent))
                .start();
        assertEquals(HttpUtil.getHttpText(url), TEST_CONTENT);
    }

    private HttpHandler getSimpleHttpHandlerLambda(byte[] testContent) {
        return httpExchange -> {
            httpExchange.sendResponseHeaders(200, testContent.length);
            OutputStream os = httpExchange.getResponseBody();
            os.write(testContent);
            os.close();
        };
    }

    @Test
    public void multiThreaded() throws IOException, InterruptedException {
        int numberOfThreads = 4;
        int timeoutInMilliseconds = 2000;
        runTestThreads(numberOfThreads, timeoutInMilliseconds);
    }

    private static void runTestThreads(int numberOfThreads, int timeoutInMilliseconds) throws InterruptedException {
        ExecutorService exec = Executors.newCachedThreadPool();
        List<Callable<Void>> tasks = Stream
                .generate(SimpleHttpServerTest::getTestFiftyServerContextsTask)
                .limit(numberOfThreads)
                .collect(Collectors.toList());
        exec.invokeAll(tasks, timeoutInMilliseconds, TimeUnit.MILLISECONDS)
                .forEach(SimpleHttpServerTest::finishFuture);
    }

    private static Callable<Void> getTestFiftyServerContextsTask() {
        return () -> {
            testFiftyServerContexts();
            return null;
        };
    }

    private static void finishFuture(Future<Void> future) {
        try {
            future.get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (CancellationException e) {
            Assert.fail("Timeout reached. Consider extending it on slower machines.");
        } catch (InterruptedException e) {
            Assert.fail("The thread was interrupted.");
        }
    }

    private static void testFiftyServerContexts() throws IOException {
        String testContent = TEST_CONTENT + RandomString.get();
        for (int i = 0; i < 50; i++)
            testServerContext(testContent);
    }

    private static void testServerContext(String testContent) throws IOException {
        String url = startServerContextOrDie(testContent);
        testContentDelivery(testContent, url);
        sleepRandomMilliseconds();
    }

    private static String startServerContextOrDie(String testContent) throws IOException {
        return startServerContext(testContent);
    }

    private static void testContentDelivery(String testContent, String url) throws IOException {
        Assert.assertEquals(HttpUtil.getHttpText(url), testContent);
    }

    private static void sleepRandomMilliseconds() {
        try {
            Random random = new Random();
            Thread.sleep(random.nextInt(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String startServerContext(String testContent) throws IOException {
        return SimpleHttpServer.getBuilder()
                .setContent(testContent)
                .start();
    }

    private static class HttpUtil {

        private static String getHttpText(String url) throws IOException {
            URLConnection connection = getUrlConnection(url);
            return readUrlConnection(connection);
        }

        private static URLConnection getUrlConnection(String url) throws IOException {
            URL website = new URL(url);
            URLConnection connection = website.openConnection();
            if (connection instanceof HttpsURLConnection)
                setTestServerSslCertificates(connection);
            return connection;
        }

        private static String readUrlConnection(URLConnection connection) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(
                            connection.getInputStream()));
            return getStringFromStream(in);
        }

        private static void setTestServerSslCertificates(URLConnection connection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(SimpleHttpServer.sslContext.getSocketFactory());
            ((HttpsURLConnection) connection).setHostnameVerifier((s, sslSession) -> true);
        }

        private static String getStringFromStream(BufferedReader in) throws IOException {
            String response = readLines(in);
            in.close();
            return response;
        }

        private static String readLines(BufferedReader in) throws IOException {
            String inputLine;
            StringBuilder response = new StringBuilder();
            while ((inputLine = in.readLine()) != null)
                response.append(inputLine);
            return response.toString();
        }

        private static String getHttpResponseHeaders(String url) throws IOException {
            URLConnection connection = getUrlConnection(url);
            return connection.getHeaderFields().entrySet().stream()
                    .map(HttpUtil::formatHttpHeader)
                    .collect(Collectors.joining("\n"));
        }

        private static String formatHttpHeader(Map.Entry<String, List<String>> header) {
            return header.getKey() + ": " +
                    header.getValue().stream().collect(Collectors.joining("; "));
        }
    }

    private static class RandomString {

        private static final char[] SYMBOLS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        private static final Random random = new Random();

        static String get() {
            char[] buffer = new char[10];
            for (int i = 0; i < buffer.length; ++i)
                buffer[i] = SYMBOLS[random.nextInt(SYMBOLS.length)];
            return new String(buffer);
        }
    }
}
