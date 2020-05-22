/*
 * Copyright 2016 higherfrequencytrading.com
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
package net.openhft.chronicle.queue.impl.table;

import net.openhft.chronicle.bytes.MappedBytes;
import net.openhft.chronicle.bytes.MappedFile;
import net.openhft.chronicle.core.*;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.StringUtils;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.queue.impl.TableStore;
import net.openhft.chronicle.queue.impl.single.MetaDataField;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.openhft.chronicle.core.util.Time.sleep;
import static net.openhft.chronicle.core.util.Time.tickTime;

public class SingleTableStore<T extends Metadata> implements TableStore<T> {
    public static final String SUFFIX = ".cq4t";

    private static final long timeoutMS = Long.getLong("chronicle.table.store.timeoutMS", 10_000);
    @NotNull
    private final WireType wireType;
    @NotNull
    private final T metadata;
    @NotNull
    private final MappedBytes mappedBytes;
    @NotNull
    private final MappedFile mappedFile;
    @NotNull
    private final Wire mappedWire;
    @NotNull
    private final ReferenceCounted refCount;
    private AtomicBoolean isClosed = new AtomicBoolean();

    /**
     * used by {@link Demarshallable}
     *
     * @param wire a wire
     */
    @SuppressWarnings("unused")
    @UsedViaReflection
    private SingleTableStore(@NotNull WireIn wire) {
        assert wire.startUse();
        try {
            this.wireType = Objects.requireNonNull(wire.read(MetaDataField.wireType).object(WireType.class));
            this.mappedBytes = (MappedBytes) (wire.bytes());
            this.mappedFile = mappedBytes.mappedFile();
            this.refCount = ReferenceCounter.onReleased(this::onCleanup);

            wire.consumePadding();
            if (wire.bytes().readRemaining() > 0) {
                this.metadata = Objects.requireNonNull(wire.read(MetaDataField.metadata).typedMarshallable());
            } else {
                //noinspection unchecked
                this.metadata = (T) Metadata.NoMeta.INSTANCE;
            }

            mappedWire = wireType.apply(mappedBytes);
        } finally {
            assert wire.endUse();
        }
    }

    /**
     * @param wireType    the wire type that is being used
     * @param mappedBytes used to mapped the data store file
     */
    SingleTableStore(@NotNull final WireType wireType,
                     @NotNull MappedBytes mappedBytes,
                     @NotNull T metadata) {
        this.wireType = wireType;
        this.metadata = metadata;
        this.mappedBytes = mappedBytes;
        this.mappedFile = mappedBytes.mappedFile();
        this.refCount = ReferenceCounter.onReleased(this::onCleanup);
        mappedWire = wireType.apply(mappedBytes);
    }

    public static <T, R> R doWithSharedLock(File file, Function<T, ? extends R> code, Supplier<T> target) {
        return doWithLock(file, code, target, true);
    }

    public static <T, R> R doWithExclusiveLock(File file, Function<T, ? extends R> code, Supplier<T> target) {
        return doWithLock(file, code, target, false);
    }

    // shared vs exclusive - see https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html
    private static <T, R> R doWithLock(File file, Function<T, ? extends R> code, Supplier<T> target, boolean shared) {
        final String type = shared ? "shared" : "exclusive";
        final StandardOpenOption readOrWrite = shared ? StandardOpenOption.READ : StandardOpenOption.WRITE;

        final long timeoutAt = tickTime() + timeoutMS;
        boolean warnedOnFailure = false;
        try (final FileChannel channel = FileChannel.open(file.toPath(), readOrWrite)) {

            while (tickTime() < timeoutAt) {
                try {
                    FileLock fileLock = channel.tryLock(0L, Long.MAX_VALUE, shared);
                    if (fileLock != null) {
                        return code.apply(target.get());
                    }
                } catch (IOException | OverlappingFileLockException e) {
                    // failed to acquire the lock, wait until other operation completes
                    if (!warnedOnFailure) {

                        if (Jvm.isDebugEnabled(SingleTableStore.class)) {
                            String message = "Failed to acquire " + type + " lock on the table store file. Retrying, file=" + file.getAbsolutePath();
                            Jvm.debug().on(SingleTableStore.class, message, new StackTrace());
                        }
                        warnedOnFailure = true;
                    }
                }
                sleep(50, MILLISECONDS);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't perform operation with " + type + " file lock", e);
        }
        throw new IllegalStateException("Unable to claim exclusive " + type + " lock on file " + file);
    }

    @Override
    public boolean isClosed() {
        return isClosed.get();
    }

    @NotNull
    @Override
    public File file() {
        return mappedFile.file();
    }

    @NotNull
    @Override
    public String dump() {
        return dump(false);
    }

    private String dump(boolean abbrev) {

        try (MappedBytes bytes = MappedBytes.mappedBytes(mappedFile)) {
            bytes.readLimit(bytes.realCapacity());
            return Wires.fromSizePrefixedBlobs(bytes, abbrev);
        }
    }

    @NotNull
    @Override
    public String shortDump() {
        return dump(true);
    }

    @Override
    public void reserve(ReferenceOwner id) throws IllegalStateException {
        this.refCount.reserve(id);
    }

    @Override
    public void release(ReferenceOwner id) throws IllegalStateException {
        this.refCount.release(id);
    }

    @Override
    public boolean tryReserve(ReferenceOwner id) {
        return refCount.tryReserve(id);
    }

    @Override
    public int refCount() {
        return this.refCount.refCount();
    }

    @Override
    public void releaseLast(ReferenceOwner id) {
        this.refCount.releaseLast();
    }

    @Override
    public void close() {
        if (isClosed.getAndSet(true))
            return;
        refCount.releaseLast();
    }

    /**
     * @return creates a new instance of mapped bytes, because, for example the tailer and appender
     * can be at different locations.
     */
    @NotNull
    @Override
    public MappedBytes bytes(ReferenceOwner owner) {
        return MappedBytes.mappedBytes(owner, mappedFile);
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "wireType=" + wireType +
                ", mappedFile=" + mappedFile +
                ", refCount=" + refCount +
                '}';
    }

    // *************************************************************************
    // Marshalling
    // *************************************************************************

    private void onCleanup() {
        mappedBytes.release(this);
    }

    @Override
    public void writeMarshallable(@NotNull WireOut wire) {

        wire.write(MetaDataField.wireType).object(wireType);

        if (metadata != Metadata.NoMeta.INSTANCE)
            wire.write(MetaDataField.metadata).typedMarshallable(this.metadata);

        // align to a word whether needed or not as a micro-optimisation.
        wire.writeAlignTo(Integer.BYTES, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized LongValue acquireValueFor(CharSequence key, long defaultValue) { // TODO Change to ThreadLocal values if performance is a problem.
        StringBuilder sb = Wires.acquireStringBuilder();
        ReferenceOwner acquire = ReferenceOwner.temporary("acquireValueFor");
        mappedBytes.reserve(acquire);
        try {
            mappedBytes.readPosition(0);
            mappedBytes.readLimit(mappedBytes.realCapacity());
            while (mappedWire.readDataHeader()) {
                int header = mappedBytes.readVolatileInt();
                if (Wires.isNotComplete(header))
                    break;
                long readPosition = mappedBytes.readPosition();
                int length = Wires.lengthOf(header);
                ValueIn valueIn = mappedWire.readEventName(sb);
                if (StringUtils.equalsCaseIgnore(key, sb)) {
                    return valueIn.int64ForBinding(null);
                }
                mappedBytes.readPosition(readPosition + length);
            }
            // not found
            int safeLength = Maths.toUInt31(mappedBytes.realCapacity() - mappedBytes.readPosition());
            mappedBytes.writeLimit(mappedBytes.realCapacity());
            mappedBytes.writePosition(mappedBytes.readPosition());
            long pos = mappedWire.enterHeader(safeLength);
            LongValue longValue = wireType.newLongReference().get();
            mappedWire.writeEventName(key).int64forBinding(defaultValue, longValue);
            mappedWire.writeAlignTo(Integer.BYTES, 0);
            mappedWire.updateHeader(pos, false, 0);
            return longValue;

        } catch (StreamCorruptedException | EOFException e) {
            throw new IORuntimeException(e);

        } finally {
            mappedBytes.release(acquire);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <R> R doWithExclusiveLock(Function<TableStore<T>, ? extends R> code) {
        return doWithExclusiveLock(file(), code, () -> this);
    }

    @Override
    public T metadata() {
        return metadata;
    }
}

