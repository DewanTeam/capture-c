package com.anobank.manager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single public Minecraft TCP listener with PaperProxy -> Limbo failover.
 *
 * TCPShield/HAProxy PROXY v2 headers are only accepted from explicitly trusted
 * source CIDRs. Their client address is normalized into a PROXY v1 header for
 * PaperProxy. Direct clients get a synthetic PROXY v1 header from the actual
 * socket addresses. Limbo receives only the raw Minecraft stream; any trusted
 * inbound PROXY header is consumed before the relay begins.
 */
final class TcpFailoverGateway implements AutoCloseable {
    private static final byte[] PROXY_V2_SIGNATURE = new byte[] {
            '\r', '\n', '\r', '\n', 0, '\r', '\n', 'Q', 'U', 'I', 'T', '\n'
    };
    private static final int MAX_PROXY_V2_PAYLOAD = 4096;

    private final InetAddress bindAddress;
    private final int publicPort;
    private final InetSocketAddress paperProxyAddress;
    private final InetSocketAddress limboAddress;
    private final List<Cidr> trustedProxyCidrs;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paperProxyDraining = new AtomicBoolean(true);
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private volatile ServerSocket listener;
    private volatile Thread acceptThread;

    TcpFailoverGateway(String bindHost, int publicPort,
                      String paperProxyHost, int paperProxyPort,
                      String limboHost, int limboPort,
                      List<String> trustedProxyCidrs) throws IOException {
        this.bindAddress = InetAddress.getByName(bindHost);
        this.publicPort = publicPort;
        this.paperProxyAddress = new InetSocketAddress(InetAddress.getByName(paperProxyHost), paperProxyPort);
        this.limboAddress = new InetSocketAddress(InetAddress.getByName(limboHost), limboPort);
        if (publicPort < 1 || publicPort > 65535 || paperProxyPort < 1 || paperProxyPort > 65535
                || limboPort < 1 || limboPort > 65535) {
            throw new IllegalArgumentException("Gateway ports must be between 1 and 65535.");
        }
        if (!paperProxyAddress.getAddress().isLoopbackAddress() || !limboAddress.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("Gateway backend addresses must be loopback addresses.");
        }
        List<Cidr> parsed = new ArrayList<>();
        for (String raw : trustedProxyCidrs == null ? List.<String>of() : trustedProxyCidrs) {
            try { parsed.add(Cidr.parse(raw)); }
            catch (Exception exception) {
                throw new IllegalArgumentException("Invalid trusted TCPShield CIDR '" + raw + "'.", exception);
            }
        }
        this.trustedProxyCidrs = List.copyOf(parsed);
        if (this.trustedProxyCidrs.isEmpty()) {
            System.out.println("[gateway] WARNING: no TCPShield CIDRs configured; inbound PROXY protocol headers will be rejected. Direct connections remain supported.");
        }
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bindAddress, publicPort), 128);
        listener = server;
        acceptThread = new Thread(this::acceptLoop, "anobank-minecraft-gateway");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("[gateway] listening on " + bindAddress.getHostAddress() + ":" + publicPort
                + "; PaperProxy=" + paperProxyAddress + "; Limbo=" + limboAddress
                + "; trusted PROXYv2 CIDRs=" + trustedProxyCidrs.size());
    }

    void paperProxyStarting() {
        paperProxyDraining.set(false);
        System.out.println("[gateway] PaperProxy route enabled; new connections will use it when its local listener is ready.");
    }

    void paperProxyStopping() {
        if (paperProxyDraining.compareAndSet(false, true)) {
            System.out.println("[gateway] PaperProxy route paused; new connections are being sent to Limbo.");
        }
    }

    String status() {
        return "{\"enabled\":" + running.get()
                + ",\"publicPort\":" + publicPort
                + ",\"paperProxyDraining\":" + paperProxyDraining.get()
                + ",\"paperProxy\":" + quote(paperProxyAddress.toString())
                + ",\"limbo\":" + quote(limboAddress.toString())
                + ",\"trustedProxyCidrs\":" + trustedProxyCidrs.size() + "}";
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = listener.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(1500);
                openSockets.add(client);
                workers.execute(() -> handleClient(client));
            } catch (IOException exception) {
                if (running.get()) System.err.println("[gateway] accept error: " + safeMessage(exception));
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            ClientAddress clientAddress = normalizeIncomingProxyHeader(client);
            client.setSoTimeout(0);

            Socket backend = null;
            boolean usePaperProxy = !paperProxyDraining.get();
            if (usePaperProxy) {
                backend = connectBackend(paperProxyAddress);
                if (backend != null) {
                    try {
                        writeProxyHeader(clientAddress, backend);
                    } catch (IOException exception) {
                        System.err.println("[gateway] PaperProxy PROXY header write failed; trying Limbo: " + safeMessage(exception));
                        closeQuietly(backend);
                        backend = null;
                    }
                }
            }

            if (backend == null) {
                backend = connectBackend(limboAddress);
                if (backend == null) {
                    System.err.println("[gateway] neither PaperProxy nor Limbo accepted a connection.");
                    return;
                }
            }

            openSockets.add(backend);
            relay(client, backend, clientAddress.stream);
        } catch (SecurityException exception) {
            System.err.println("[gateway] rejected untrusted or malformed PROXY header from "
                    + client.getInetAddress().getHostAddress() + ": " + safeMessage(exception));
        } catch (IOException exception) {
            if (running.get()) System.err.println("[gateway] connection relay error: " + safeMessage(exception));
        } finally {
            closeQuietly(client);
            openSockets.remove(client);
            openSockets.removeIf(Socket::isClosed);
        }
    }

    /**
     * Peek the stream prefix without losing Minecraft bytes. Only verified
     * TCPShield peers may provide a v2 address. An untrusted peer attempting
     * PROXY protocol is rejected rather than treating forged metadata as game data.
     */
    private ClientAddress normalizeIncomingProxyHeader(Socket client) throws IOException {
        InputStream raw = client.getInputStream();
        java.io.PushbackInputStream input = new java.io.PushbackInputStream(raw, PROXY_V2_SIGNATURE.length);
        client.setSoTimeout(1500);
        byte[] prefix = input.readNBytes(1);
        if (prefix.length == 0) throw new EOFException("client closed before sending Minecraft handshake");
        input.unread(prefix);
        InetAddress peer = client.getInetAddress();
        boolean trustedProxyPeer = isTrustedProxy(peer);
        if (prefix[0] != PROXY_V2_SIGNATURE[0]) {
            if (trustedProxyPeer) {
                throw new SecurityException("trusted TCPShield source " + peer.getHostAddress()
                        + " did not send the required PROXYv2 header");
            }
            return ClientAddress.direct(client, input);
        }

        byte[] signature = input.readNBytes(PROXY_V2_SIGNATURE.length);
        if (!Arrays.equals(signature, PROXY_V2_SIGNATURE)) {
            input.unread(signature);
            if (trustedProxyPeer) {
                throw new SecurityException("trusted TCPShield source " + peer.getHostAddress()
                        + " sent an invalid PROXYv2 signature");
            }
            return ClientAddress.direct(client, input);
        }

        if (!trustedProxyPeer) {
            throw new SecurityException("source " + peer.getHostAddress() + " is not in configured TCPShield CIDRs");
        }

        DataInputStream data = new DataInputStream(input);
        int verCmd = data.readUnsignedByte();
        int familyProtocol = data.readUnsignedByte();
        int length = data.readUnsignedShort();
        if ((verCmd >>> 4) != 2 || (verCmd & 0x0F) != 1) {
            throw new SecurityException("unsupported PROXYv2 version/command");
        }
        if (length > MAX_PROXY_V2_PAYLOAD) throw new SecurityException("PROXYv2 payload exceeds " + MAX_PROXY_V2_PAYLOAD + " bytes");
        byte[] payload = data.readNBytes(length);
        if (payload.length != length) throw new EOFException("truncated PROXYv2 payload");

        int protocol = familyProtocol & 0x0F;
        int family = familyProtocol >>> 4;
        if (protocol != 1) throw new SecurityException("PROXYv2 transport is not STREAM/TCP");

        int addressLength;
        if (family == 1) addressLength = 12; // IPv4: src(4), dst(4), ports(4)
        else if (family == 2) addressLength = 36; // IPv6: src(16), dst(16), ports(4)
        else throw new SecurityException("unsupported PROXYv2 address family " + family);
        if (length < addressLength) throw new SecurityException("PROXYv2 address block is truncated");

        int ipLength = family == 1 ? 4 : 16;
        byte[] sourceBytes = Arrays.copyOfRange(payload, 0, ipLength);
        byte[] destinationBytes = Arrays.copyOfRange(payload, ipLength, ipLength * 2);
        InetAddress source = InetAddress.getByAddress(sourceBytes);
        InetAddress destination = InetAddress.getByAddress(destinationBytes);
        int sourcePort = unsignedShort(payload, ipLength * 2);
        int destinationPort = unsignedShort(payload, ipLength * 2 + 2);
        if (sourcePort < 1 || destinationPort < 1) throw new SecurityException("PROXYv2 contains an invalid port");

        System.out.println("[gateway] accepted verified TCPShield PROXYv2 header from " + peer.getHostAddress()
                + "; client=" + source.getHostAddress() + ":" + sourcePort);
        return new ClientAddress(input, source, destination, sourcePort, destinationPort, true);
    }

    private boolean isTrustedProxy(InetAddress address) {
        for (Cidr cidr : trustedProxyCidrs) if (cidr.matches(address)) return true;
        return false;
    }

    private static int unsignedShort(byte[] value, int offset) {
        return ((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF);
    }

    private static Socket connectBackend(InetSocketAddress address) {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(address, 350);
            return socket;
        } catch (IOException exception) {
            closeQuietly(socket);
            return null;
        }
    }

    private static void writeProxyHeader(ClientAddress client, Socket backend) throws IOException {
        InetAddress source = client.source;
        InetAddress destination = client.destination;
        String protocol;
        if (source instanceof Inet4Address && destination instanceof Inet4Address) protocol = "TCP4";
        else if (source instanceof Inet6Address && destination instanceof Inet6Address) protocol = "TCP6";
        else throw new IOException("source and destination address families do not match");

        String header = "PROXY " + protocol + " " + cleanAddress(source) + " " + cleanAddress(destination)
                + " " + client.sourcePort + " " + client.destinationPort + "\r\n";
        OutputStream output = backend.getOutputStream();
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String cleanAddress(InetAddress address) {
        return address.getHostAddress().replaceFirst("%.*$", "");
    }

    private static void relay(Socket client, Socket backend, InputStream normalizedClientInput) throws IOException {
        CountDownLatch completed = new CountDownLatch(2);
        workersStart(normalizedClientInput, client, backend, completed);
        workersStart(backend.getInputStream(), backend, client, completed);
        try { completed.await(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        finally { closeQuietly(client); closeQuietly(backend); }
    }

    private static void workersStart(InputStream input, Socket source, Socket destination, CountDownLatch completed) {
        Thread.startVirtualThread(() -> {
            try {
                OutputStream output = destination.getOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); output.flush(); }
            } catch (IOException ignored) {
                // Closing one direction is normal when a Minecraft client disconnects.
            } finally {
                try { destination.shutdownOutput(); } catch (IOException ignored) { }
                completed.countDown();
            }
        });
    }

    private record ClientAddress(InputStream stream, InetAddress source, InetAddress destination,
                                 int sourcePort, int destinationPort, boolean proxied) {
        static ClientAddress direct(Socket socket, InputStream stream) {
            return new ClientAddress(stream, socket.getInetAddress(), socket.getLocalAddress(),
                    socket.getPort(), socket.getLocalPort(), false);
        }
    }

    private record Cidr(InetAddress network, int prefixBits) {
        static Cidr parse(String value) throws IOException {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("CIDR is blank");
            String[] pieces = value.trim().split("/", -1);
            InetAddress network = InetAddress.getByName(pieces[0]);
            int bits = network.getAddress().length * 8;
            int prefix = pieces.length == 1 ? bits : Integer.parseInt(pieces[1]);
            if (pieces.length > 2 || prefix < 0 || prefix > bits) throw new IllegalArgumentException("Invalid CIDR prefix");
            return new Cidr(network, prefix);
        }
        boolean matches(InetAddress candidate) {
            byte[] a = network.getAddress(), b = candidate.getAddress();
            if (a.length != b.length) return false;
            int full = prefixBits / 8, rem = prefixBits % 8;
            for (int i = 0; i < full; i++) if (a[i] != b[i]) return false;
            if (rem == 0) return true;
            int mask = 0xFF << (8 - rem);
            return (a[full] & mask) == (b[full] & mask);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        openSockets.forEach(TcpFailoverGateway::closeQuietly);
        workers.shutdownNow();
    }
}