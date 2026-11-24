package io.github.otakukisaragishintaro.boundlesschatdiscord.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Config;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Http;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.MemoryPrefs;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationException;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationScope;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** API-test ownership uses the real engine and transport with controlled connection I/O. */
public class ApiTesterTest {
    @Test
    public void offlineCheckReturnsOnlyTheFieldsConsumedBySettings() {
        ApiTester.Result result = ApiTester.selfCheck(Config.read(new MemoryPrefs()));
        assertTrue(result.ok);
        assertEquals(ApiTester.TestKind.CONFIGURATION, result.testKind);
        assertNull(result.failure);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }

    @Test
    public void invalidConfigFailsOfflineAndConnectionTestWithoutOpeningIo() {
        Config.Snapshot config = Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "invalid"));
        for (ApiTester.Result result : new ApiTester.Result[]{ApiTester.selfCheck(config),
                ApiTester.newCall(config, ApiTester.TestKind.CONNECTION, "private input").call()}) {
            assertFalse(result.ok);
            assertEquals(TranslationException.Kind.CONFIG, result.failure.kind);
            assertEquals(TranslationException.Reason.CONFIG_INVALID, result.failure.reason);
            assertEquals(0, result.failure.httpStatus);
            assertNull(result.translated);
            assertTrue(result.elapsedMs >= 0);
        }
    }

    @Test
    public void blankTranslationUsesTheEnginesRejectionPath() {
        ApiTester.Result result = ApiTester.newCall(Config.read(new MemoryPrefs()),
                ApiTester.TestKind.TRANSLATION, " \n\t").call();
        assertFalse(result.ok);
        assertEquals(ApiTester.TestKind.TRANSLATION, result.testKind);
        assertEquals(TranslationException.Kind.EMPTY, result.failure.kind);
        assertEquals(TranslationException.Reason.EMPTY_TEXT, result.failure.reason);
        assertEquals(0, result.failure.httpStatus);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }

    @Test
    public void translationTestAppliesDiscordTokenProtectionAndRestoresMention() throws Exception {
        String mention = "<@285191197662904320>";
        FakeConnection connection = successfulConnection("你好 <keep1/>");
        ApiTester.Result result = call(url -> connection,
                "hello " + mention).call();
        assertTrue(result.ok);
        assertEquals("你好 " + mention, result.translated);
        assertTrue(connection.disconnected);
    }

    @Test
    public void translationTestHonorsConfiguredScopeBeforeOpeningNetwork() {
        TranslationScope scope = new TranslationScope(true,
                java.util.Collections.emptyList(),
                java.util.Collections.singletonList(
                        new TranslationScope.Rule("456", "blocked test channel", false)));
        Config.Snapshot config = Config.read(new MemoryPrefs()
                .put(Config.KEY_TRANSLATION_SCOPE, scope.toJson()));
        ApiTester.Result missingContext = ApiTester.newCall(config,
                ApiTester.TestKind.TRANSLATION, "hello").call();
        assertFalse(missingContext.ok);
        assertEquals(TranslationException.Reason.SCOPE_CONTEXT_REQUIRED,
                missingContext.failure.reason);

        ApiTester.Result denied = ApiTester.newCall(config,
                ApiTester.TestKind.TRANSLATION, "hello", "", "456").call();
        assertFalse(denied.ok);
        assertEquals(TranslationException.Reason.SCOPE_DENIED, denied.failure.reason);
        assertEquals("scope_denied", denied.failure.stage);
    }

    @Test
    public void cancellationBeforeSubmissionPreventsIoWithoutInterruptingTheCaller() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ApiTester.Call call = call(url -> {
            opens.incrementAndGet();
            throw new AssertionError("Cancelled queued call opened a connection");
        });
        call.cancel();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ApiTester.Result result = executor.submit(call).get(2, TimeUnit.SECONDS);
            assertCancelled(result, 0);
            assertNull(result.translated);
            assertEquals(0, opens.get());
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted())
                    .get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationDisconnectsReadThatIgnoresInterruption() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                awaitIgnoringInterrupt(disconnectSignal);
                throw new IOException("Disconnected");
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        AtomicInteger opens = new AtomicInteger();
        ApiTester.Call call = call(url -> {
            opens.incrementAndGet();
            return connection;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiTester.Result> result = executor.submit(call);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            // Deliberately do not cancel the Future: owner cancellation must disconnect.
            call.cancel();
            assertCancelled(result.get(2, TimeUnit.SECONDS), 200);
            assertTrue(connection.disconnected);
            assertEquals(1, opens.get());
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted())
                    .get(2, TimeUnit.SECONDS));
        } finally {
            disconnectSignal.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationBeforeConnectionAttachmentCannotStartTheRequest() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        FakeConnection connection = successfulConnection();
        ApiTester.Call call = call(url -> {
            opening.countDown();
            awaitIgnoringInterrupt(releaseOpen);
            return connection;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiTester.Result> result = executor.submit(call);
            assertTrue(opening.await(2, TimeUnit.SECONDS));
            call.cancel();
            releaseOpen.countDown();
            assertCancelled(result.get(2, TimeUnit.SECONDS), 0);
            assertTrue(connection.disconnected);
            assertEquals(0, connection.output.size());
        } finally {
            releaseOpen.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationAsResponseCodeArrivesKeepsKnownStatusBeforeReadingBody() throws Exception {
        for (int status : new int[]{200, 429}) {
            AtomicReference<ApiTester.Call> owner = new AtomicReference<>();
            FakeConnection connection = successfulConnection();
            FakeConnection cancelling = new FakeConnection(connection.input) {
                @Override public int getResponseCode() {
                    owner.get().cancel();
                    return status;
                }
                @Override public InputStream getInputStream() {
                    throw new AssertionError("Cancelled response must not start reading");
                }
                @Override public InputStream getErrorStream() {
                    throw new AssertionError("Cancelled error response must not start reading");
                }
            };
            ApiTester.Call call = call(url -> cancelling);
            owner.set(call);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                ApiTester.Result result = executor.submit(call).get(2, TimeUnit.SECONDS);
                assertCancelled(result, status);
                assertTrue(cancelling.disconnected);
                assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted())
                        .get(2, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void lateCancellationCannotInterruptTheCompletedWorkersNextJob() throws Exception {
        FakeConnection connection = successfulConnection();
        ApiTester.Call call = call(url -> connection);
        CountDownLatch nextJob = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ApiTester.Result result = executor.submit(call).get(2, TimeUnit.SECONDS);
            assertTrue(result.ok);
            assertEquals(ApiTester.TestKind.TRANSLATION, result.testKind);
            assertNull(result.failure);
            assertEquals("你好", result.translated);
            assertTrue(connection.disconnected);
            Future<Boolean> next = executor.submit(() -> {
                nextJob.countDown();
                try {
                    return !releaseNext.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    return true;
                }
            });
            assertTrue(nextJob.await(2, TimeUnit.SECONDS));
            call.cancel();
            call.cancel();
            assertTrue(result.ok);
            assertNull(result.failure);
            assertEquals("你好", result.translated);
            releaseNext.countDown();
            assertFalse(next.get(2, TimeUnit.SECONDS));
        } finally {
            releaseNext.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void errorEscapesToFutureAndReleasesCallAndEngineOwnership() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AssertionError error = new AssertionError("private fatal detail");
        ApiTester.Call call = call(url -> {
            opens.incrementAndGet();
            throw error;
        });
        CountDownLatch nextJob = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                executor.submit(call).get(2, TimeUnit.SECONDS);
                fail("Errors belong to the Future boundary, not a business failure Result");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() == error);
            }
            assertEquals(1, opens.get());
            Field workerField = ApiTester.Call.class.getDeclaredField("worker");
            workerField.setAccessible(true);
            assertNull(workerField.get(call));
            Field engineField = ApiTester.Call.class.getDeclaredField("engine");
            engineField.setAccessible(true);
            JsonApiEngine engine = (JsonApiEngine) engineField.get(call);
            Field activeField = JsonApiEngine.class.getDeclaredField("active");
            activeField.setAccessible(true);
            assertTrue(((Map<?, ?>) activeField.get(engine)).isEmpty());
            Future<Boolean> next = executor.submit(() -> {
                nextJob.countDown();
                try {
                    return !releaseNext.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    return true;
                }
            });
            assertTrue(nextJob.await(2, TimeUnit.SECONDS));
            call.cancel();
            call.cancel();
            releaseNext.countDown();
            assertFalse(next.get(2, TimeUnit.SECONDS));
        } finally {
            releaseNext.countDown();
            executor.shutdownNow();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void callCannotBeReused() {
        ApiTester.Call call = ApiTester.newCall(Config.read(new MemoryPrefs()),
                ApiTester.TestKind.TRANSLATION, "");
        call.call();
        call.call();
    }

    private interface Factory {
        HttpURLConnection open(URL url) throws Exception;
    }

    /** Same package-private transport injection used by engine tests, without widening APIs. */
    private static ApiTester.Call call(Factory factory) throws Exception {
        return call(factory, "hello");
    }

    private static ApiTester.Call call(Factory factory, String text) throws Exception {
        Class<?> type = Class.forName(Http.class.getName() + "$ConnectionFactory");
        Object connections = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> factory.open((URL) args[0]));
        Constructor<Http> constructor = Http.class.getDeclaredConstructor(int.class, type);
        constructor.setAccessible(true);
        Http http = constructor.newInstance(1000, connections);
        Config.Snapshot config = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                .put(Config.KEY_STREAMING, false));
        return new ApiTester.Call(new JsonApiEngine(config, http),
                ApiTester.TestKind.TRANSLATION, text, config);
    }

    private static void assertCancelled(ApiTester.Result result, int status) {
        assertFalse(result.ok);
        assertEquals(ApiTester.TestKind.TRANSLATION, result.testKind);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
        assertEquals(TranslationException.Kind.CANCELLED, result.failure.kind);
        assertEquals(TranslationException.Reason.CANCELLED, result.failure.reason);
        assertEquals(status, result.failure.httpStatus);
    }

    private static FakeConnection successfulConnection() throws Exception {
        return successfulConnection("你好");
    }

    private static FakeConnection successfulConnection(String translated) throws Exception {
        String body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + org.json.JSONObject.quote(translated) + "}}]}";
        return new FakeConnection(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Controlled I/O was never released");
                    }
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class FakeConnection extends HttpURLConnection {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean disconnected;

        FakeConnection(InputStream input) throws Exception {
            super(new URL("https://fake.invalid/v1"));
            this.input = input;
        }

        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
    }
}
