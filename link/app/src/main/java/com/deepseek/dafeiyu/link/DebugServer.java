package com.deepseek.dafeiyu.link;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本机调试口：127.0.0.1:19528，行协议（不是 HTTP）。
 * 只绑回环，外部连不到；用来干跑/自动化验证，免得每次截图点坐标。
 *
 * 用法（本机 shell）：
 *   echo STATE | nc 127.0.0.1 19528
 *   echo "SEND hello" | nc 127.0.0.1 19528
 *   echo "SET 192.168.31.118 9527 <base64key> 我" | nc 127.0.0.1 19528
 *   echo "LOG 30" | nc 127.0.0.1 19528
 */
public class DebugServer extends Thread {

    public static final int PORT = 19528;

    private final LinkService svc;
    private volatile boolean running = true;
    private ServerSocket ss;

    public DebugServer(LinkService svc) {
        this.svc = svc;
        setName("link-debug");
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            while (running) {
                final Socket s = ss.accept();
                // 每个连接单独线程 + 读超时，避免一个不说话的客户端堵住 accept
                new Thread(() -> {
                    try {
                        s.setSoTimeout(5000);
                        handle(s);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                }, "link-debug-conn").start();
            }
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (Exception ignored) {
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
        String line = in.readLine();
        if (line == null) return;
        line = line.trim();
        int sp = line.indexOf(' ');
        String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase();
        String arg = sp < 0 ? "" : line.substring(sp + 1).trim();

        switch (cmd) {
            case "PING":
                out.println("{\"ok\":true,\"pong\":true,\"ver\":" + android.os.Build.VERSION.SDK_INT + "}");
                break;
            case "STATE":
                out.println(svc.debugState());
                break;
            case "SEND":
                svc.debugSend(arg);
                out.println("{\"ok\":true,\"sent\":" + arg.length() + "}");
                break;
            case "SET": {
                String[] a = arg.split("\\s+");
                if (a.length < 3) {
                    out.println("{\"ok\":false,\"err\":\"SET <host> <port> <key> [nick]\"}");
                    break;
                }
                svc.debugSet(a[0], Integer.parseInt(a[1]), a[2], a.length > 3 ? a[3] : prefsNick());
                out.println("{\"ok\":true}");
                break;
            }
            case "RECONNECT":
                svc.debugReconnect();
                out.println("{\"ok\":true}");
                break;
            case "LOG": {
                int n = arg.isEmpty() ? 30 : Integer.parseInt(arg);
                out.println(svc.debugLogs(n));
                break;
            }
            case "HISTORY": {
                int n = arg.isEmpty() ? 10 : Integer.parseInt(arg);
                out.println(svc.debugHistory(n));
                break;
            }
            case "NODES":
                out.println(DafeiyuA11y.dumpNodes().toString());
                break;
            case "CLICK": {
                boolean ok = DafeiyuA11y.clickByText(arg, false);
                out.println("{\"ok\":" + ok + "}");
                break;
            }
            case "A11Y":
                out.println("{\"ok\":true,\"ready\":" + DafeiyuA11y.ready() + "}");
                break;
            case "CLEAR":
                svc.debugClear();
                out.println("{\"ok\":true}");
                break;
            default:
                out.println("{\"ok\":false,\"err\":\"unknown\",\"cmds\":\"PING STATE SEND SET RECONNECT LOG HISTORY CLEAR\"}");
        }
    }

    private String prefsNick() {
        return "我";
    }
}
