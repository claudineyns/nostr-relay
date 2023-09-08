package io.github.social.nostr.relay.misc;

public class IncomingData {
    private static final int ALLOC_SIZE = 1024;

    private byte[] data = new byte[ALLOC_SIZE];
    private int written = 0;
    private int consumed = 0;

    public int write(final byte octet) {
        return this.write(new byte[]{octet}, 0, 1);
    }

    public int write(final byte[] source, final int offset, final int length) {
        if(offset >= source.length) return 0;

        final int srcLen = Math.min(source.length - offset, length);

        final int availableSpace = this.data.length - this.written;
        if( srcLen > availableSpace ) {
            final int allocFactor = (srcLen / ALLOC_SIZE) + 1;
            final byte[] alloc = new byte[this.data.length + (allocFactor * ALLOC_SIZE)];
            System.arraycopy(this.data, 0, alloc, 0, this.data.length);
            this.data = alloc;
        }

        System.arraycopy(source, offset, this.data, this.written, srcLen);
        this.written += srcLen;

        return srcLen;
    }

    public int consume(final byte[] destination) {
        this.checkAvailableData();

        final int read = Math.min(destination.length, this.remaining());

        System.arraycopy(this.data, this.consumed, destination, 0, read);

        return read;
    }

    public byte[] consume(final int length) {
        this.checkAvailableData();

        final int read = Math.min(length, this.remaining());

        final byte[] destination = new byte[read];

        System.arraycopy(this.data, this.consumed, destination, 0, read);

        return destination;
    }

    public byte[] consume() {
        return this.consume(this.remaining());
    }

    public byte next() {
        this.checkAvailableData();

        return this.data[this.consumed++];
    }

    public int remaining() {
        return this.written - this.consumed;
    }

    public void shrink() {
        final int remaining = this.remaining();
        System.arraycopy(this.data, this.consumed, this.data, 0, remaining);

        this.written = remaining;
        this.consumed = 0;
    }

    public void reset() {
        this.data = new byte[ALLOC_SIZE];
        this.written = 0;
        this.consumed = 0;
    }

    private void checkAvailableData() {
        if(this.consumed == this.written) {
            throw new IndexOutOfBoundsException("No available data to consume");
        }
    }

}
