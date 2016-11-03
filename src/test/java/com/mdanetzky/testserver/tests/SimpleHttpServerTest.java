package com.mdanetzky.testserver.tests;

import com.mdanetzky.testserver.SimpleHttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.xml.sax.SAXException;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

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
    public void bindsNextFreePort() throws IOException {
        SimpleHttpServer.stop();
        stealPort1666();
        String url = SimpleHttpServer.getBuilder().startEcho();
        assertNotNull(url);
        assertFalse(url.contains("1666"));
    }

    private static void stealPort1666() throws IOException {
        try {
            HttpServer portBlock = HttpServer.create();
            InetSocketAddress address = new InetSocketAddress(1666);
            portBlock.bind(address, 0);
        } catch (BindException ignored) {
            // the port 1666 is already bound
        }
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
        TestThreadsHolder testThreadsHolder = runTestThreads(numberOfThreads);
        assertTrue(testThreadsHolder.assertionsOk.get());
        assertEquals(testThreadsHolder.threadsFinished.get(), numberOfThreads);
    }

    private TestThreadsHolder runTestThreads(int numberOfThreads) throws InterruptedException {
        TestThreadsHolder testThreadsHolder = new TestThreadsHolder();
        Collection<Thread> threads = testThreadsHolder.startTestThreads(numberOfThreads);
        testThreadsHolder.waitForThreads(threads);
        return testThreadsHolder;
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

    private static class TestThreadsHolder {

        final AtomicBoolean assertionsOk = new AtomicBoolean(true);
        final AtomicInteger threadsFinished = new AtomicInteger(0);

        private Collection<Thread> startTestThreads(int numberOfThreads) {
            Collection<Thread> threads = new ArrayList<>();
            Stream.generate(this::createAndStartTestThread)
                    .limit(numberOfThreads)
                    .forEach(threads::add);
            return threads;
        }

        private Thread createAndStartTestThread() {
            Thread thread = getTestThread();
            thread.start();
            return thread;
        }

        private Thread getTestThread() {
            return new Thread(this::testServerInstance);
        }

        private void waitForThreads(Collection<Thread> threads) throws InterruptedException {
            for (Thread thread : threads)
                thread.join(2000);
        }

        private void testServerInstance() {
            testFiftyServerContexts();
            threadsFinished.incrementAndGet();
        }

        private void testFiftyServerContexts() {
            String testContent = TEST_CONTENT + RandomString.get();
            for (int i = 0; i < 50; i++)
                testServerContext(testContent);
        }

        private void testServerContext(String testContent) {
            String url = startServerContextOrDie(testContent);
            testContentDelivery(testContent, url);
            sleepRandomMilliseconds();
        }

        private String startServerContextOrDie(String testContent) {
            try {
                return startServerContext(testContent);
            } catch (IOException e) {
                assertionsOk.set(false);
            }
            return null;
        }

        private void testContentDelivery(String testContent, String url) {
            try {
                assertionsOk.set(HttpUtil.getHttpText(url).equals(testContent));
            } catch (IOException e) {
                assertionsOk.set(false);
            }
        }

        private void sleepRandomMilliseconds() {
            try {
                Random random = new Random();
                Thread.sleep(random.nextInt(10));
            } catch (InterruptedException e) {
                assertionsOk.set(false);
            }
        }

        private String startServerContext(String testContent) throws IOException {
            return SimpleHttpServer.getBuilder()
                    .setContent(testContent)
                    .start();
        }
    }

}
