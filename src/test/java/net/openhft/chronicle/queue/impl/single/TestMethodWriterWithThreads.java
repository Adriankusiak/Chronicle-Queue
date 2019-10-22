package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.DirectoryUtils;
import net.openhft.chronicle.queue.DumpQueueMain;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.AbstractMarshallable;
import net.openhft.chronicle.wire.WireType;
import org.jetbrains.annotations.NotNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static junit.framework.TestCase.fail;
import static net.openhft.chronicle.queue.RollCycles.HOURLY;
import static net.openhft.chronicle.queue.RollCycles.TEST4_DAILY;

/**
 * check that method writes are thread safe when used with queue.methodWriter
 */
public class TestMethodWriterWithThreads {

    private static final int AMEND = 1;
    private static final int CREATE = 2;
    @Rule
    public final TestName testName = new TestName();
    private ThreadLocal<Amend> amendTL = ThreadLocal.withInitial(Amend::new);
    private ThreadLocal<Create> createTL = ThreadLocal.withInitial(Create::new);
    private I methodWriter;
    private AtomicBoolean fail = new AtomicBoolean();

    @Test
    public void test() throws FileNotFoundException {

        File tmpDir = getTmpDir();
        try (final ChronicleQueue q = builder(tmpDir, WireType.BINARY).rollCycle(HOURLY).build()) {

            methodWriter = q.methodWriter(I.class);

            ExcerptTailer tailer = q.createTailer();
            MethodReader methodReader = tailer.methodReader(newReader());

            IntStream.range(0, 1000)
                    .parallel()
                    .forEach(i -> {
                        creates();
                        amends();
                        synchronized (methodReader) {
                            for (int j = 0; j < 2 && !fail.get(); )
                                if (methodReader.readOne())
                                    j++;
                        }
                        if (fail.get())
                            fail();
                    });

        } finally {
            if (fail.get()) {
                DumpQueueMain.dump(tmpDir.getAbsolutePath());
            }
        }
    }

    @NotNull
    private I newReader() {
        return new I() {

            @Override
            public void amend(final Amend amend) {
                if (amend.type != AMEND) {
                    fail.set(true);
                    fail("amend type=" + amend.type);
                }
            }

            @Override
            public void create(final Create create) {
                if (create.type != CREATE) {
                    fail.set(true);
                    fail("create type=" + create.type);
                }
            }
        };
    }

    private void amends() {
        methodWriter.amend(amendTL.get().type(AMEND));
    }

    private void creates() {
        methodWriter.create(createTL.get().type(CREATE));
    }

    @NotNull
    protected File getTmpDir() {
        final String methodName = testName.getMethodName();
        return DirectoryUtils.tempDir(methodName != null ?
                methodName.replaceAll("[\\[\\]\\s]+", "_").replace(':', '_') : "NULL-" + UUID
                .randomUUID());
    }

    @NotNull
    protected SingleChronicleQueueBuilder builder(@NotNull File file, @NotNull WireType wireType) {
        return SingleChronicleQueueBuilder.builder(file, wireType).rollCycle(TEST4_DAILY).testBlockSize();
    }

    public interface I {
        void amend(Amend q);

        void create(Create q);
    }

    public static class Amend extends AbstractMarshallable {
        int type;

        public Amend type(final int type) {
            this.type = type;
            return this;
        }
    }

    public static class Create extends AbstractMarshallable {
        int type;

        public Create type(final int type) {
            this.type = type;
            return this;
        }
    }
}
