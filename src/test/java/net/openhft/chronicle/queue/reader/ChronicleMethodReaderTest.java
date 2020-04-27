package net.openhft.chronicle.queue.reader;

import net.openhft.chronicle.bytes.MethodId;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.queue.impl.table.SingleTableStore;
import net.openhft.chronicle.wire.BytesInBinaryMarshallable;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.SelfDescribingMarshallable;
import net.openhft.chronicle.wire.VanillaMethodWriterBuilder;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ChronicleMethodReaderTest {

    private final Queue<String> capturedOutput = new ConcurrentLinkedQueue<>();
    private Path dataDir;

    @Before
    public void before() {
        dataDir = DirectoryUtils.tempDir(ChronicleMethodReaderTest.class.getSimpleName()).toPath();
        try (final ChronicleQueue queue = SingleChronicleQueueBuilder.binary(dataDir)
                .testBlockSize().build()) {
            final ExcerptAppender excerptAppender = queue.acquireAppender();
            final VanillaMethodWriterBuilder<All> methodWriterBuilder = excerptAppender.methodWriterBuilder(All.class);
            methodWriterBuilder.recordHistory(true);
            final All events = methodWriterBuilder.build();

            for (int i = 0; i < 24; ) {
                Method1Type m1 = new Method1Type();
                m1.text = "hello";
                m1.value = i;
                m1.number = i;
                events.method1(m1);
                i++;
                Method2Type m2 = new Method2Type();
                m2.text = "goodbye";
                m2.value = i;
                m2.number = i;
                events.method2(m2);
                i++;
            }
        }
    }

    @Test
    public void shouldNotFailOnEmptyQueue() {
        Path path = DirectoryUtils.tempDir("shouldNotFailOnEmptyQueue").toPath();
        path.toFile().mkdirs();
        basicReader(path).execute();
        assertTrue(capturedOutput.isEmpty());
    }

    @NotNull
    public ChronicleReader basicReader(Path path) {
        return new ChronicleReader().withBasePath(path).withMessageSink(capturedOutput::add).asMethodReader(All.class.getName());
    }

    @Test
    public void shouldNotFailWhenNoMetadata() throws IOException {
        Files.list(dataDir).filter(f -> f.getFileName().toString().endsWith(SingleTableStore.SUFFIX)).findFirst().ifPresent(path -> path.toFile().delete());
        basicReader().execute();
        assertThat(capturedOutput.stream().anyMatch(msg -> msg.contains("history:")), is(true));
    }

    @Test
    public void shouldIncludeMessageHistoryByDefault() {
        basicReader().execute();

        assertThat(capturedOutput.stream().anyMatch(msg -> msg.contains("history:")), is(true));
    }

    @Ignore("TODO FIX")
    @Test
    public void shouldApplyIncludeRegexToHistoryMessagesAndBusinessMessages() {
        basicReader().
                // matches goodbye, but not hello or history
                        withInclusionRegex("goodbye").
                asMethodReader(null).
                execute();
        assertThat(capturedOutput.stream().anyMatch(msg -> msg.contains("history:")), is(false));
    }

    @Test
    public void shouldBeAbleToReadFromReadOnlyFile() throws Exception {
        if (OS.isWindows()) {
            System.err.println("#460 read-only not supported on Windows");
            return;
        }
        final Path queueFile = Files.list(dataDir).
                filter(f -> f.getFileName().toString().endsWith(SingleChronicleQueue.SUFFIX)).findFirst().
                orElseThrow(() ->
                        new AssertionError("Could not find queue file in directory " + dataDir));

        assertThat(queueFile.toFile().setWritable(false), is(true));

        basicReader().execute();
    }

    @Test
    public void shouldConvertEntriesToText() {
        basicReader().execute();

        assertThat(capturedOutput.size(), is(48));
        assertThat(capturedOutput.stream().anyMatch(msg -> msg.contains("hello")), is(true));
    }

    @Test
    public void shouldFilterByInclusionRegex() {
        basicReader().withInclusionRegex(".*good.*").execute();

        assertThat(capturedOutput.size(), is(24));
        capturedOutput.stream().filter(msg -> !msg.startsWith("0x")).
                forEach(msg -> assertThat(msg, containsString("goodbye")));
    }

    @Ignore("TODO FIX")
    @Test
    public void shouldFilterByMultipleInclusionRegex() {
        basicReader().withInclusionRegex(".*bye$").withInclusionRegex(".*o.*").execute();

        assertThat(capturedOutput.size(), is(24));
        capturedOutput.stream().filter(msg -> !msg.startsWith("0x")).
                forEach(msg -> assertThat(msg, containsString("goodbye")));
        capturedOutput.stream().filter(msg -> !msg.startsWith("0x")).
                forEach(msg -> assertThat(msg, not(containsString("hello"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionIfInputDirectoryDoesNotExist() {
        basicReader().withBasePath(Paths.get("/does/not/exist")).execute();
    }

    @Test
    public void shouldFilterByExclusionRegex() {
        basicReader().withExclusionRegex(".*good.*").execute();

        assertThat(capturedOutput.size(), is(24));
        capturedOutput.forEach(msg -> assertThat(msg, not(containsString("goodbye"))));
    }

    @Ignore("TODO FIX")
    @Test
    public void shouldFilterByMultipleExclusionRegex() {
        basicReader().withExclusionRegex(".*bye$").withExclusionRegex(".*ell.*").execute();

        assertThat(capturedOutput.stream().filter(msg -> !msg.startsWith("0x")).count(), is(0L));
    }

    @Test
    public void shouldReturnNoMoreThanTheSpecifiedNumberOfMaxRecords() {
        basicReader().historyRecords(5).execute();

        assertThat(capturedOutput.stream().
                filter(msg -> !msg.startsWith("0x")).count(), is(5L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldFailIfSpecifiedIndexIsBeforeFirstIndex() {
        basicReader().withStartIndex(1L).execute();
    }

    @Test
    public void shouldNotRewindPastStartOfQueueWhenDisplayingHistory() {
        basicReader().historyRecords(Long.MAX_VALUE).execute();

        assertThat(capturedOutput.stream().
                filter(msg -> !msg.startsWith("0x")).count(), is(24L));
    }

    private ChronicleReader basicReader() {
        return basicReader(dataDir);
    }

    @After
    public void clearInterrupt() {
        Thread.interrupted();
    }

    interface Method1 {
        @MethodId(1)
        void method1(Method1Type method1Type);
    }

    interface Method2 {
        void method2(Method2Type method2Type);
    }

    interface All extends Method1, Method2 {

    }

    static class Method1Type extends BytesInBinaryMarshallable {
        String text;
        long value;
        double number;
    }

    static class Method2Type extends SelfDescribingMarshallable {
        String text;
        long value;
        double number;
    }

    private static final class RecordCounter implements Consumer<String> {
        private final AtomicLong recordCount = new AtomicLong();
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void accept(final String msg) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                // ignore
            }

            if (!msg.startsWith("0x")) {
                recordCount.incrementAndGet();
            }
        }
    }

    private static final class FiniteDocumentPollMethod implements Function<ExcerptTailer, DocumentContext> {

        private final int maxPollsReturningEmptyDocument;
        private int invocationCount;

        private FiniteDocumentPollMethod(final int maxPollsReturningEmptyDocument) {
            this.maxPollsReturningEmptyDocument = maxPollsReturningEmptyDocument;
        }

        @Override
        public DocumentContext apply(final ExcerptTailer excerptTailer) {
            final DocumentContext documentContext = excerptTailer.readingDocument();

            if (!documentContext.isPresent()) {
                invocationCount++;
                if (invocationCount >= maxPollsReturningEmptyDocument) {
                    throw new ArithmeticException("For testing purposes");
                }
            }

            return documentContext;
        }
    }
}