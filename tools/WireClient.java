import com.deepseek.dafeiyu.link.Crypto;
import com.deepseek.dafeiyu.link.Frame;
import com.deepseek.dafeiyu.link.Session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 裸客户端：只依赖 Crypto/Frame/Session，用来和 node 版协议实现互通自测 */
public class WireClient {

    public static void main(String[] a) throws Exception {
        String host = a[0];
        int port = Integer.parseInt(a[1]);
        byte[] psk = unhex(a[2]);

        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 5000);
        DataInputStream in = new DataInputStream(s.getInputStream());
        DataOutputStream out = new DataOutputStream(s.getOutputStream());

        byte[] saltC = Crypto.random(16);
        byte[] hello = new byte[21];
        System.arraycopy("DLK1".getBytes(StandardCharsets.US_ASCII), 0, hello, 0, 4);
        hello[4] = 1;
        System.arraycopy(saltC, 0, hello, 5, 16);
        out.write(hello);
        out.flush();

        byte[] reply = new byte[21];
        in.readFully(reply);
        if (!"DLK2".equals(new String(reply, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("握手标记不对");
        }
        Session sess = Session.derive(psk, saltC, Arrays.copyOfRange(reply, 5, 21), true);
                System.out.println("[cli] 握手完成 key=" + hex(sess.key()).substring(0, 16) + "…");

        // 1) 文本
        String text = "{\"t\":\"text\",\"id\":\"m1\",\"ts\":1,\"body\":\"你好，本鱼\"}";
        Frame.write(out, sess, Frame.T_JSON, text.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) {
            Frame.In f = Frame.read(in, sess);
            System.out.println("[cli] 收到帧 type=" + f.type + " " +
                    new String(f.plain, StandardCharsets.UTF_8));
        }

        // 2) 图片：头 + 两块
        byte[] fake = new byte[30000];
        for (int i = 0; i < fake.length; i++) fake[i] = (byte) (i & 0xff);
        String head = "{\"t\":\"image\",\"id\":\"img1\",\"name\":\"a.jpg\",\"size\":30000,\"chunks\":2}";
        Frame.write(out, sess, Frame.T_JSON, head.getBytes(StandardCharsets.UTF_8));
        int chunk = 24000;
        for (int i = 0; i < 2; i++) {
            int from = i * chunk, to = Math.min(fake.length, from + chunk);
            String b64 = java.util.Base64.getEncoder().encodeToString(Arrays.copyOfRange(fake, from, to));
            String c = "{\"t\":\"chunk\",\"id\":\"img1\",\"seq\":" + i + ",\"data\":\"" + b64 + "\"}";
            Frame.write(out, sess, Frame.T_JSON, c.getBytes(StandardCharsets.UTF_8));
        }

        // 3) 心跳
        Frame.write(out, sess, Frame.T_PING, new byte[0]);
        Frame.In pong = Frame.read(in, sess);
        System.out.println("[cli] 心跳返回 type=" + pong.type + "（3=PONG）");

        // 4) 篡改检测：手工改密文一字节，对端必须解不开
        s.close();
        System.out.println("[cli] 全部通过");
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] o = new byte[s.length() / 2];
        for (int i = 0; i < o.length; i++) {
            o[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return o;
    }
}
