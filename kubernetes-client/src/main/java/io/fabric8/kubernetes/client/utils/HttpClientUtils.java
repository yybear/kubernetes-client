/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.utils;

import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.Challenge;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.internal.SSLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.utils.Utils.isNotNullOrEmpty;

public class HttpClientUtils {

    public static OkHttpClient createHttpClient(final Config config) {
        try {
            OkHttpClient httpClient = new OkHttpClient();

            // Follow any redirects
            httpClient.setFollowRedirects(true);
            httpClient.setFollowSslRedirects(true);

            if (config.isTrustCerts()) {
                httpClient.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String s, SSLSession sslSession) {
                        return true;
                    }
                });
            }

            TrustManager[] trustManagers = SSLUtils.trustManagers(config);
            KeyManager[] keyManagers = SSLUtils.keyManagers(config);

            if (keyManagers != null || trustManagers != null || config.isTrustCerts()) {
                try {
                    SSLContext sslContext = SSLUtils.sslContext(keyManagers, trustManagers, config.isTrustCerts());
                    httpClient.setSslSocketFactory(sslContext.getSocketFactory());
                } catch (GeneralSecurityException e) {
                    throw new AssertionError(); // The system has no TLS. Just give up.
                }
            }

            if (isNotNullOrEmpty(config.getUsername()) && isNotNullOrEmpty(config.getPassword())) {
                httpClient.setAuthenticator(new Authenticator() {

                    @Override
                    public Request authenticate(Proxy proxy, Response response) throws IOException {
                        List<Challenge> challenges = response.challenges();
                        Request request = response.request();
                        HttpUrl url = request.httpUrl();
                        for (int i = 0, size = challenges.size(); i < size; i++) {
                            Challenge challenge = challenges.get(i);
                            if (!"Basic".equalsIgnoreCase(challenge.getScheme())) continue;

                            String credential = Credentials.basic(config.getUsername(), config.getPassword());
                            return request.newBuilder()
                                    .header("Authorization", credential)
                                    .build();
                        }
                        return null;
                    }

                    @Override
                    public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
                        return null;
                    }
                });
            } else if (config.getOauthToken() != null) {
                httpClient.interceptors().add(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        Request authReq = chain.request().newBuilder().addHeader("Authorization", "Bearer " + config.getOauthToken()).build();
                        return chain.proceed(authReq);
                    }
                });
            }

            Logger reqLogger = LoggerFactory.getLogger(LoggingInterceptor.class);
            if (reqLogger.isTraceEnabled()) {
                httpClient.networkInterceptors().add(new LoggingInterceptor(reqLogger));
            }

            if (config.getConnectionTimeout() > 0) {
                httpClient.setConnectTimeout(config.getConnectionTimeout(), TimeUnit.MILLISECONDS);
            }

            if (config.getRequestTimeout() > 0) {
                httpClient.setReadTimeout(config.getRequestTimeout(), TimeUnit.MILLISECONDS);
            }

            // Only check proxy if it's a full URL with protocol
            if (config.getMasterUrl().toLowerCase().startsWith(Config.HTTP_PROTOCOL_PREFIX) || config.getMasterUrl().startsWith(Config.HTTPS_PROTOCOL_PREFIX)) {
                try {
                    URL proxyUrl = getProxyUrl(config);
                    if (proxyUrl != null) {
                        httpClient.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort())));
                    }
                } catch (MalformedURLException e) {
                    throw new KubernetesClientException("Invalid proxy server configuration", e);
                }
            }

            return httpClient;
        } catch (Exception e) {
            throw KubernetesClientException.launderThrowable(e);
        }
    }

    private static URL getProxyUrl(Config config) throws MalformedURLException {
        URL master = new URL(config.getMasterUrl());
        String host = master.getHost();
        for (String noProxy : config.getNoProxy()) {
            if (host.endsWith(noProxy)) {
                return null;
            }
        }
        String proxy = config.getHttpsProxy();
        if (master.getProtocol().equals("http")) {
            proxy = config.getHttpProxy();
        }
        if (proxy != null) {
            return new URL(proxy);
        }
        return null;
    }

    private static class LoggingInterceptor implements Interceptor {
        private final Logger logger;

        LoggingInterceptor(Logger logger) {
            this.logger = logger;
        }

        @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();

            long t1 = System.nanoTime();
            logger.trace(String.format("Sending request %s %s on %s%n%s",
                         request.method(), request.url(), chain.connection(), request.headers()));

            Response response = chain.proceed(request);

            long t2 = System.nanoTime();
            logger.trace(String.format("Received %d response for %s %s in %.1fms%n%s",
                         response.code(), response.request().method(), response.request().url(), (t2 - t1) / 1e6d, response.headers()));

            return response;
        }
    }
}
