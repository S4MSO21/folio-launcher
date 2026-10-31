package com.folio.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Folio Transfer: a tiny temporary HTTP server so a phone/PC on the same Wi-Fi can send books.
 * Off by default, protected by a random per-session code, asks the user before each file,
 * stops by itself after TIMEOUT_MS. Plain HTTP: not encrypted.
 */
final class TransferServer {
    interface Host {
        /** Blocks (called off the UI thread) until the user answers. */
        boolean confirm(String name, long size, String from);

        void progress(String name, long done, long total);

        void received(File tmp);

        void stopped();
    }

    static final long TIMEOUT_MS = 5 * 60 * 1000;
    private static final String PAGE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>Folio</title><body style='font-family:serif;background:#f4eee0;color:#2b2620;max-width:520px;margin:30px auto;padding:0 16px'>"
            + "<h2>Enviar livros para o Folio</h2><p>C&oacute;digo mostrado no tablet:</p>"
            + "<input id=c inputmode=numeric maxlength=6 style='font-size:24px;width:8em'><p>"
            + "<input id=f type=file multiple accept='.epub,.pdf,.txt,.fb2,.cbz,.html,.htm'></p>"
            + "<button onclick=go() style='font-size:18px;padding:8px 18px'>Enviar</button><pre id=o></pre>"
            + "<script>function go(){var fs=document.getElementById('f').files,o=document.getElementById('o'),c=document.getElementById('c').value;o.textContent='';"
            + "for(var i=0;i<fs.length;i++)(function(f){var x=new XMLHttpRequest();x.open('POST','/upload?code='+encodeURIComponent(c)+'&n='+encodeURIComponent(f.name));"
            + "x.onload=function(){o.textContent+=f.name+': '+x.responseText+'\\n'};x.onerror=function(){o.textContent+=f.name+': falha\\n'};x.send(f)})(fs[i])}</script>";

    final String code;
    final int port;
    private final ServerSocket socket;
    private final Host host;
    private final File incoming;
    private volatile boolean running = true;
    private volatile int bad;

    TransferServer(File incoming, Host host) throws Exception {
        this.incoming = incoming;
        this.host = host;
        incoming.mkdirs();
        code = String.format("%06d", new SecureRandom().nextInt(1000000));
        socket = new ServerSocket(0);
        socket.setSoTimeout(1000);
        port = socket.getLocalPort();
        final long end = System.currentTimeMillis() + TIMEOUT_MS;
        Thread t = new Thread(() -> {
            while (running && System.currentTimeMillis() < end) {
                try {
                    final Socket s = socket.accept();
                    s.setSoTimeout(30000);
                    new Thread(() -> handle(s)).start();
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    break;
                }
            }
            stop();
        }, "folio-transfer");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        if (!running) return;
        running = false;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        host.stopped();
    }

    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String req = line(in);
            if (req == null) return;
            String[] p = req.split(" ");
            if (p.length < 2) return;
            long len = -1;
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                if (h.toLowerCase().startsWith("content-length:")) len = Long.parseLong(h.substring(15).trim());
            }
            if (p[0].equals("GET") && (p[1].equals("/") || p[1].startsWith("/?"))) {
                reply(out, 200, "text/html; charset=utf-8", PAGE);
            } else if (p[0].equals("POST") && p[1].startsWith("/upload?")) {
                upload(in, out, p[1], len, sock.getInetAddress().getHostAddress());
            } else {
                reply(out, 404, "text/plain; charset=utf-8", "não encontrado");
            }
        } catch (Exception ignored) {
        }
    }

    private void upload(InputStream in, OutputStream out, String target, long len, String from) throws Exception {
        String given = "", name = "livro";
        for (String kv : target.substring(target.indexOf('?') + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            String v = URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            if (kv.startsWith("code=")) given = v;
            else if (kv.startsWith("n=")) name = v;
        }
        if (bad >= 5) {
            reply(out, 429, "text/plain; charset=utf-8", "bloqueado: muitas tentativas");
            stop();
            return;
        }
        if (!java.security.MessageDigest.isEqual(given.getBytes("UTF-8"), code.getBytes("UTF-8"))) {
            bad++;
            reply(out, 403, "text/plain; charset=utf-8", "código incorreto");
            return;
        }
        name = Importer.safeName(name);
        if (!Book.supported(name)) {
            reply(out, 415, "text/plain; charset=utf-8", "formato não suportado");
            return;
        }
        if (len <= 0 || len > Importer.MAX_BYTES) {
            reply(out, 413, "text/plain; charset=utf-8", "tamanho inválido (máx. 300 MB)");
            return;
        }
        if (!host.confirm(name, len, from)) {
            reply(out, 403, "text/plain; charset=utf-8", "recusado no tablet");
            return;
        }
        File tmp = new File(incoming, name);
        long left = len;
        byte[] buf = new byte[32 * 1024];
        try (OutputStream f = new FileOutputStream(tmp)) {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new java.io.IOException("cortado");
                f.write(buf, 0, n);
                left -= n;
                host.progress(name, len - left, len);
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        host.received(tmp);
        reply(out, 200, "text/plain; charset=utf-8", "recebido");
    }

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        return c < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static void reply(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
