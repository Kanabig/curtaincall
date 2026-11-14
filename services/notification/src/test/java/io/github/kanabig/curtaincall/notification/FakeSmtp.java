package io.github.kanabig.curtaincall.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 메일 한 통씩 받아 저장하는 최소 SMTP 서버. {@code fail}이면 MAIL FROM에서 451로 거절한다. */
final class FakeSmtp implements AutoCloseable {

    record Mail(String to, String data) {

        /** 본문은 base64로 오므로 MIME으로 풀어 제목과 본문을 합쳐 돌려준다. */
        String decoded() {
            try {
                jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(
                        jakarta.mail.Session.getDefaultInstance(new java.util.Properties()),
                        new java.io.ByteArrayInputStream(
                                data.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
                return message.getSubject() + "\n" + message.getContent();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ServerSocket server;
    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    volatile boolean fail;

    FakeSmtp() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    List<Mail> mails() {
        return mails;
    }

    void reset() {
        fail = false;
        mails.clear();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            out.print("220 fake\r\n");
            out.flush();
            String to = null;
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.toUpperCase();
                if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
                    reply(out, "250 fake");
                } else if (cmd.startsWith("MAIL FROM")) {
                    reply(out, fail ? "451 temporarily unavailable" : "250 ok");
                } else if (cmd.startsWith("RCPT TO")) {
                    to = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                    reply(out, "250 ok");
                } else if (cmd.equals("DATA")) {
                    reply(out, "354 go");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        data.append(line).append('\n');
                    }
                    mails.add(new Mail(to, data.toString()));
                    reply(out, "250 queued");
                } else if (cmd.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "250 ok");
                }
            }
        } catch (IOException ignored) {
            // 클라이언트가 끊었다
        }
    }

    private static void reply(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
