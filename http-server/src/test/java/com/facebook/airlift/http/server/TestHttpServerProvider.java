/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.airlift.http.server;

import com.facebook.airlift.event.client.NullEventClient;
import com.facebook.airlift.http.client.HttpClient;
import com.facebook.airlift.http.client.HttpClient.HttpResponseFuture;
import com.facebook.airlift.http.client.HttpClientConfig;
import com.facebook.airlift.http.client.Request;
import com.facebook.airlift.http.client.StatusResponseHandler.StatusResponse;
import com.facebook.airlift.http.client.StringResponseHandler.StringResponse;
import com.facebook.airlift.http.client.jetty.JettyHttpClient;
import com.facebook.airlift.http.server.HttpServer.ClientCertificate;
import com.facebook.airlift.log.Logging;
import com.facebook.airlift.node.NodeConfig;
import com.facebook.airlift.node.NodeInfo;
import com.facebook.airlift.testing.TempFile;
import com.facebook.airlift.tracetoken.TraceTokenManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import io.airlift.units.Duration;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import javax.security.auth.x500.X500Principal;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static com.facebook.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static com.facebook.airlift.http.server.TestHttpServerInfo.closeChannels;
import static com.facebook.airlift.testing.Assertions.assertContains;
import static com.facebook.airlift.testing.Assertions.assertInstanceOf;
import static com.facebook.airlift.testing.Assertions.assertNotEquals;
import static com.google.common.io.Files.asCharSink;
import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.io.Resources.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestHttpServerProvider
{
    private HttpServer server;
    private File tempDir;
    private NodeInfo nodeInfo;
    private HttpServerConfig config;
    private HttpsConfig httpsConfig;
    private ClientCertificate clientCertificate;
    private HttpServerInfo httpServerInfo;

    @BeforeSuite
    public void setupSuite()
    {
        Logging.initialize();
    }

    @BeforeMethod
    public void setup()
            throws IOException
    {
        tempDir = Files.createTempDir().getCanonicalFile(); // getCanonicalFile needed to get around Issue 365 (http://code.google.com/p/guava-libraries/issues/detail?id=365)
        config = new HttpServerConfig()
                .setHttpPort(0)
                .setLogPath(new File(tempDir, "http-request.log").getAbsolutePath());
        httpsConfig = new HttpsConfig()
                .setHttpsPort(0);
        clientCertificate = ClientCertificate.NONE;
        nodeInfo = new NodeInfo(new NodeConfig()
                .setEnvironment("test")
                .setNodeInternalAddress("localhost"));
        httpServerInfo = createHttpServerInfo();
    }

    @AfterMethod(alwaysRun = true)
    public void teardown()
            throws Exception
    {
        closeChannels(httpServerInfo);
        try {
            if (server != null) {
                server.stop();
            }
        }
        finally {
            deleteRecursively(tempDir.toPath(), ALLOW_INSECURE);
        }
    }

    @Test
    public void testConnectorDefaults()
    {
        assertTrue(config.isHttpEnabled());
        assertNotNull(httpServerInfo.getHttpUri());
        assertNotNull(httpServerInfo.getHttpExternalUri());
        assertNotNull(httpServerInfo.getHttpChannel());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), httpServerInfo.getHttpExternalUri().getScheme());
        assertEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getHttpExternalUri().getPort());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), "http");

        assertFalse(config.isHttpsEnabled());
        assertNull(httpServerInfo.getHttpsUri());
        assertNull(httpServerInfo.getHttpsExternalUri());
        assertNull(httpServerInfo.getHttpsChannel());

        assertTrue(config.isAdminEnabled());
        assertNotNull(httpServerInfo.getAdminUri());
        assertNotNull(httpServerInfo.getAdminExternalUri());
        assertNotNull(httpServerInfo.getAdminChannel());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), httpServerInfo.getAdminExternalUri().getScheme());
        assertEquals(httpServerInfo.getAdminUri().getPort(), httpServerInfo.getAdminExternalUri().getPort());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), "http");

        assertNotEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getAdminUri().getPort());
    }

    @Test
    public void testHttpDisabled()
    {
        closeChannels(httpServerInfo);

        config.setHttpEnabled(false);
        httpServerInfo = createHttpServerInfo();

        assertNull(httpServerInfo.getHttpUri());
        assertNull(httpServerInfo.getHttpExternalUri());
        assertNull(httpServerInfo.getHttpChannel());

        assertNull(httpServerInfo.getHttpsUri());
        assertNull(httpServerInfo.getHttpsExternalUri());
        assertNull(httpServerInfo.getHttpsChannel());

        assertNotNull(httpServerInfo.getAdminUri());
        assertNotNull(httpServerInfo.getAdminExternalUri());
        assertNotNull(httpServerInfo.getAdminChannel());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), httpServerInfo.getAdminExternalUri().getScheme());
        assertEquals(httpServerInfo.getAdminUri().getPort(), httpServerInfo.getAdminExternalUri().getPort());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), "http");
    }

    @Test
    public void testAdminDisabled()
    {
        closeChannels(httpServerInfo);

        config.setAdminEnabled(false);
        httpServerInfo = createHttpServerInfo();

        assertNotNull(httpServerInfo.getHttpUri());
        assertNotNull(httpServerInfo.getHttpExternalUri());
        assertNotNull(httpServerInfo.getHttpChannel());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), httpServerInfo.getHttpExternalUri().getScheme());
        assertEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getHttpExternalUri().getPort());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), "http");

        assertNull(httpServerInfo.getHttpsUri());
        assertNull(httpServerInfo.getHttpsExternalUri());
        assertNull(httpServerInfo.getHttpsChannel());

        assertNull(httpServerInfo.getAdminUri());
        assertNull(httpServerInfo.getAdminExternalUri());
        assertNull(httpServerInfo.getAdminChannel());
    }

    @Test
    public void testHttpsEnabled()
    {
        closeChannels(httpServerInfo);

        config.setHttpsEnabled(true);
        httpServerInfo = createHttpServerInfo();

        assertNotNull(httpServerInfo.getHttpUri());
        assertNotNull(httpServerInfo.getHttpExternalUri());
        assertNotNull(httpServerInfo.getHttpChannel());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), httpServerInfo.getHttpExternalUri().getScheme());
        assertEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getHttpExternalUri().getPort());
        assertEquals(httpServerInfo.getHttpUri().getScheme(), "http");

        assertNotNull(httpServerInfo.getHttpsUri());
        assertNotNull(httpServerInfo.getHttpsExternalUri());
        assertNotNull(httpServerInfo.getHttpsChannel());
        assertEquals(httpServerInfo.getHttpsUri().getScheme(), httpServerInfo.getHttpsExternalUri().getScheme());
        assertEquals(httpServerInfo.getHttpsUri().getPort(), httpServerInfo.getHttpsExternalUri().getPort());
        assertEquals(httpServerInfo.getHttpsUri().getScheme(), "https");

        assertNotNull(httpServerInfo.getAdminUri());
        assertNotNull(httpServerInfo.getAdminExternalUri());
        assertNotNull(httpServerInfo.getAdminChannel());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), httpServerInfo.getAdminExternalUri().getScheme());
        assertEquals(httpServerInfo.getAdminUri().getPort(), httpServerInfo.getAdminExternalUri().getPort());
        assertEquals(httpServerInfo.getAdminUri().getScheme(), "https");

        assertNotEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getHttpsUri().getPort());
        assertNotEquals(httpServerInfo.getHttpUri().getPort(), httpServerInfo.getAdminUri().getPort());
    }

    @Test
    public void testHttp()
            throws Exception
    {
        createServer();
        server.start();

        try (JettyHttpClient httpClient = new JettyHttpClient(new HttpClientConfig().setHttp2Enabled(false))) {
            StatusResponse response = httpClient.execute(prepareGet().setUri(httpServerInfo.getHttpUri()).build(), createStatusResponseHandler());

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(response.getHeader("X-Protocol"), "HTTP/1.1");
        }

        try (JettyHttpClient httpClient = new JettyHttpClient(new HttpClientConfig().setHttp2Enabled(true))) {
            StatusResponse response = httpClient.execute(prepareGet().setUri(httpServerInfo.getHttpUri()).build(), createStatusResponseHandler());

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(response.getHeader("X-Protocol"), "HTTP/2.0");
        }
    }

    @Test
    public void testHttps()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setHttpsEnabled(true);
        httpsConfig
                .setKeystorePath(getResource("test.keystore.with.two.passwords").getPath())
                .setKeystorePassword("airlift")
                .setKeyManagerPassword("airliftkey")
                .setAutomaticHttpsSharedSecret("shared-secret");

        createAndStartServer();

        HttpClientConfig http1ClientConfig = new HttpClientConfig()
                .setHttp2Enabled(false)
                .setTrustStorePath(getResource("test.truststore").getPath())
                .setTrustStorePassword("airlift")
                .setAutomaticHttpsSharedSecret("shared-secret")
                .setNodeEnvironment("test");

        try (JettyHttpClient httpClient = new JettyHttpClient(http1ClientConfig)) {
            verifyUri(httpClient, URI.create("https://localhost:" + httpServerInfo.getHttpsUri().getPort()));
            verifyUri(httpClient, URI.create("https://127.0.0.1:" + httpServerInfo.getHttpsUri().getPort()));
        }
    }

    private void verifyUri(JettyHttpClient httpClient, URI uri)
    {
        StatusResponse response = httpClient.execute(prepareGet().setUri(uri).build(), createStatusResponseHandler());

        assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(response.getHeader("X-Protocol"), "HTTP/1.1");
    }

    @Test
    public void testFilter()
            throws Exception
    {
        createServer();
        server.start();

        try (JettyHttpClient client = new JettyHttpClient()) {
            StatusResponse response = client.execute(prepareGet().setUri(httpServerInfo.getHttpUri().resolve("/filter")).build(), createStatusResponseHandler());

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_PAYMENT_REQUIRED);
        }
    }

    @Test
    public void testAuth()
            throws Exception
    {
        File file = File.createTempFile("auth", ".properties", tempDir);
        asCharSink(file, UTF_8).write("user: password");

        config.setUserAuthFile(file.getAbsolutePath());

        createServer();
        server.start();

        try (HttpClient client = new JettyHttpClient()) {
            StringResponse response = client.execute(
                    prepareGet()
                            .setUri(httpServerInfo.getHttpUri())
                            .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("user:password".getBytes()).trim())
                            .build(),
                    createStringResponseHandler());

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(response.getBody(), "user");
        }
    }

    @Test
    public void testClientCertificateJava()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setAdminEnabled(false)
                .setHttpsEnabled(true);
        httpsConfig.setKeystorePath(getResource("clientcert-java/server.keystore").getPath())
                .setKeystorePassword("airlift")
                .setAutomaticHttpsSharedSecret("shared-secret");
        clientCertificate = ClientCertificate.REQUIRED;

        createAndStartServer(createCertTestServlet());

        HttpClientConfig clientConfig = new HttpClientConfig()
                .setKeyStorePath(getResource("clientcert-java/client.keystore").getPath())
                .setKeyStorePassword("airlift")
                .setTrustStorePath(getResource("clientcert-java/client.truststore").getPath())
                .setTrustStorePassword("airlift")
                .setAutomaticHttpsSharedSecret("shared-secret")
                .setNodeEnvironment("test");

        try (JettyHttpClient httpClient = new JettyHttpClient(clientConfig)) {
            for (String host : ImmutableList.of("localhost", "127.0.0.1")) {
                URI uri = URI.create("https://" + host + ":" + httpServerInfo.getHttpsUri().getPort());
                StringResponse response = httpClient.execute(
                        prepareGet().setUri(uri).build(),
                        createStringResponseHandler());

                assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
                assertEquals(response.getBody(), "CN=testing,OU=Client,O=Airlift,L=Palo Alto,ST=CA,C=US");
            }
        }
    }

    @Test
    public void testClientCertificatePem()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setAdminEnabled(false)
                .setHttpsEnabled(true);
        httpsConfig
                .setKeystorePath(getResource("clientcert-pem/server.pem").getPath())
                .setKeystorePassword("airlift")
                .setTrustStorePath(getResource("clientcert-pem/ca.crt").getPath());
        clientCertificate = ClientCertificate.REQUIRED;

        createAndStartServer(createCertTestServlet());

        HttpClientConfig clientConfig = new HttpClientConfig()
                .setKeyStorePath(getResource("clientcert-pem/client.pem").getPath())
                .setKeyStorePassword("airlift")
                .setTrustStorePath(getResource("clientcert-pem/ca.crt").getPath());

        assertClientCertificateRequest(clientConfig);
    }

    private void assertClientCertificateRequest(HttpClientConfig clientConfig)
    {
        try (JettyHttpClient httpClient = new JettyHttpClient(clientConfig)) {
            StringResponse response = httpClient.execute(
                    prepareGet().setUri(httpServerInfo.getHttpsUri()).build(),
                    createStringResponseHandler());

            assertEquals(response.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(response.getBody(), "CN=testing,OU=Client,O=Airlift,L=Palo Alto,ST=CA,C=US");
        }
    }

    private static HttpServlet createCertTestServlet()
    {
        return new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                    throws IOException
            {
                X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
                if ((certs == null) || (certs.length == 0)) {
                    throw new RuntimeException("No client certificate");
                }
                if (certs.length > 1) {
                    throw new RuntimeException("Received multiple client certificates");
                }
                X509Certificate cert = certs[0];
                response.getWriter().write(cert.getSubjectX500Principal().getName());
                response.setStatus(HttpServletResponse.SC_OK);
            }
        };
    }

    @Test
    public void testShowStackTraceEnabled()
            throws Exception
    {
        createServer(new ErrorServlet());
        server.start();

        try (HttpClient client = new JettyHttpClient()) {
            StringResponse response = client.execute(prepareGet().setUri(httpServerInfo.getHttpUri()).build(), createStringResponseHandler());
            assertEquals(response.getStatusCode(), 500);
            assertContains(response.getBody(), "ErrorServlet.java");
        }
    }

    @Test
    public void testShowStackTraceDisabled()
            throws Exception
    {
        config.setShowStackTrace(false);
        createServer(new ErrorServlet());
        server.start();

        try (HttpClient client = new JettyHttpClient()) {
            StringResponse response = client.execute(prepareGet().setUri(httpServerInfo.getHttpUri()).build(), createStringResponseHandler());
            assertEquals(response.getStatusCode(), 500);
            assertTrue(!response.getBody().contains("ErrorServlet.java"));
        }
    }

    @Test(timeOut = 30000)
    public void testStop()
            throws Exception
    {
        DummyServlet servlet = new DummyServlet();
        createAndStartServer(servlet);

        try (HttpClient client = new JettyHttpClient()) {
            URI uri = URI.create(httpServerInfo.getHttpUri().toASCIIString() + "/?sleep=50000");
            Request request = prepareGet().setUri(uri).build();
            HttpResponseFuture<?> future = client.executeAsync(request, createStatusResponseHandler());

            // wait until the servlet starts processing the request
            servlet.getLatch().await(1, SECONDS);

            // stop server while the request is still active
            server.stop();

            // wait until the server is stopped
            server.join();

            // request should fail rather than sleeping the full duration
            try {
                future.get(5, SECONDS);
                fail("expected exception");
            }
            catch (ExecutionException e) {
                assertInstanceOf(e.getCause(), UncheckedIOException.class);
            }
        }
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "Insufficient configured threads: .*")
    public void testInsufficientThreadsHttp()
            throws Exception
    {
        config.setMaxThreads(1);
        createAndStartServer();
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "Insufficient configured threads: .*")
    public void testInsufficientThreadsHttps()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setHttpsEnabled(true)
                .setMaxThreads(1);
        httpsConfig
                .setKeystorePath(getResource("test.keystore").getPath())
                .setKeystorePassword("airlift");
        createAndStartServer();
    }

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = ".+Cannot recover key.+")
    public void testInsufficientPasswordToAccessKeystore()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setHttpsEnabled(true);
        httpsConfig
                .setKeystorePath(getResource("test.keystore.with.two.passwords").getPath())
                .setKeystorePassword("airlift");
        createAndStartServer();
    }

    @Test
    public void testHttpsDaysUntilCertificateExpiration()
            throws Exception
    {
        config.setHttpEnabled(false)
                .setHttpsEnabled(true);
        httpsConfig
                .setKeystorePath(new File(getResource("test.keystore").toURI()).getAbsolutePath())
                .setKeystorePassword("airlift");
        createAndStartServer();
        Long daysUntilCertificateExpiration = server.getDaysUntilCertificateExpiration();
        assertNotNull(daysUntilCertificateExpiration);
        // todo this should be positive but the certificate is expired
        assertTrue(daysUntilCertificateExpiration < 0);
    }

    @Test
    public void testNoHttpsDaysUntilCertificateExpiration()
            throws Exception
    {
        config.setHttpEnabled(true);
        createAndStartServer();
        assertNull(server.getDaysUntilCertificateExpiration());
    }

    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = "Insufficient configured threads: .*")
    public void testInsufficientThreadsAdmin()
            throws Exception
    {
        config.setAdminMaxThreads(1);
        createAndStartServer();
    }

    @Test
    public void testKeystoreReloading()
            throws Exception
    {
        try (TempFile tempFile = new TempFile()) {
            appendCertificate(tempFile.file(), "certificate-1");
            config.setHttpsEnabled(true)
                    .setHttpEnabled(false);
            httpsConfig
                    .setSslContextRefreshTime(new Duration(5, SECONDS))
                    .setKeystorePath(tempFile.file().getAbsolutePath())
                    .setKeystorePassword("airlift");
            createAndStartServer();
            assertEventually(() -> assertEquals(server.getCertificates().size(), 1));
            appendCertificate(tempFile.file(), "certificate-2");
            assertEventually(() -> assertEquals(server.getCertificates().size(), 2));
        }
    }

    private void createAndStartServer()
            throws Exception
    {
        createAndStartServer(new DummyServlet());
    }

    private void createAndStartServer(HttpServlet servlet)
            throws Exception
    {
        closeChannels(httpServerInfo);
        httpServerInfo = createHttpServerInfo();
        createServer(servlet);
        server.start();
    }

    private void createServer()
    {
        createServer(new DummyServlet());
    }

    private void createServer(HttpServlet servlet)
    {
        HashLoginServiceProvider loginServiceProvider = new HashLoginServiceProvider(config);
        HttpServerProvider serverProvider = new HttpServerProvider(
                httpServerInfo,
                nodeInfo,
                config,
                optionalHttpsConfig(),
                servlet,
                ImmutableMap.of(),
                ImmutableSet.of(new DummyFilter()),
                ImmutableSet.of(),
                ImmutableSet.of(),
                clientCertificate,
                new RequestStats(),
                new NullEventClient(),
                Optional.empty());
        serverProvider.setTheAdminServlet(new DummyServlet());
        serverProvider.setLoginService(loginServiceProvider.get());
        serverProvider.setTokenManager(new TraceTokenManager());
        server = serverProvider.get();
    }

    private HttpServerInfo createHttpServerInfo()
    {
        return new HttpServerInfo(config, optionalHttpsConfig(), nodeInfo);
    }

    private Optional<HttpsConfig> optionalHttpsConfig()
    {
        return config.isHttpsEnabled() ? Optional.of(this.httpsConfig) : Optional.empty();
    }

    private static void appendCertificate(File keyStoreFile, String alias)
            throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        char[] password = "airlift".toCharArray();
        try (InputStream inStream = new FileInputStream(keyStoreFile)) {
            keyStore.load(inStream, password);
        }
        catch (EOFException ignored) { // reading an empty file produces EOFException
            keyStore.load(null, password);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();

        X500Principal issuer = new X500Principal("CN=Airlift Test, OU=Airlift, O=Airlift, L=Palo Alto, ST=CA, C=US");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(Instant.now()),
                Date.from(Instant.now().plus(365, ChronoUnit.DAYS)),
                issuer,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(privateKey);
        X509CertificateHolder certHolder = builder.build(signer);
        Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);

        keyStore.setKeyEntry(alias, privateKey, password, new Certificate[] {cert});
        try (OutputStream outStream = new FileOutputStream(keyStoreFile)) {
            keyStore.store(outStream, password);
        }
    }

    private static void assertEventually(Runnable assertion)
    {
        long start = System.nanoTime();
        Duration timeout = new Duration(30, SECONDS);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                assertion.run();
                return;
            }
            catch (Exception | AssertionError e) {
                if (Duration.nanosSince(start).compareTo(timeout) > 0) {
                    throw e;
                }
            }
            try {
                //noinspection BusyWait
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
