package com.ramphoenix.launcher;

import android.content.Context;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import javax.crypto.Cipher;

/** Small, single-stream ADB bootstrap. Keys belong to this installation, never a PC. */
final class LocalAdb implements Closeable {
    private final Socket socket=new Socket();
    private InputStream input;
    private OutputStream output;
    private int remote, local;
    private final ByteArrayOutputStream received=new ByteArrayOutputStream();
    private static int command(String s) { return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static final class Packet { int cmd,a,b; byte[] data; }
    LocalAdb(Context context, boolean requestApproval) throws Exception {
        this(key(context),5555,requestApproval);
    }
    LocalAdb(KeyPair key,int port,boolean requestApproval) throws Exception {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1",port),1000);
            socket.setSoTimeout(requestApproval?90000:2000);
            input=socket.getInputStream(); output=socket.getOutputStream();
            send("CNXN",0x01000000,4096,"host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed=false;
            while (true) {
                Packet p=read();
                if (p.cmd==command("CNXN")) return;
                if (p.cmd!=command("AUTH") || p.a!=1) throw new IOException("Unexpected ADB handshake");
                if (signed) {
                    if (!requestApproval) throw new IOException("Android debugging approval required");
                    send("AUTH",3,0,publicKey((RSAPublicKey)key.getPublic()));
                } else {
                    // The challenge is already a SHA-1 digest; do not hash it again.
                    byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0,0x04,0x14};
                    ByteArrayOutputStream digest=new ByteArrayOutputStream(); digest.write(prefix); digest.write(p.data);
                    Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsa.init(Cipher.ENCRYPT_MODE,key.getPrivate());
                    send("AUTH",2,0,rsa.doFinal(digest.toByteArray())); signed=true;
                }
            }
        } catch (Exception e) { socket.close(); throw e; }
    }
    private static KeyPair key(Context context) throws Exception {
        File privateFile=new File(context.getNoBackupFilesDir(),"window-adb.private");
        File publicFile=new File(context.getNoBackupFilesDir(),"window-adb.public");
        if (privateFile.exists() && publicFile.exists()) {
            KeyFactory f=KeyFactory.getInstance("RSA");
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile.toPath()))),
                    f.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile.toPath()))));
        }
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair=generator.generateKeyPair();
        Files.write(privateFile.toPath(),pair.getPrivate().getEncoded());
        Files.write(publicFile.toPath(),pair.getPublic().getEncoded()); return pair;
    }
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger n=key.getModulus(), base=BigInteger.ONE.shiftLeft(32);
        ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n.mod(base).modInverse(base).negate().intValue());
        for (BigInteger value:new BigInteger[]{n,BigInteger.ONE.shiftLeft(4096).mod(n)})
            for (int i=0;i<64;i++) b.putInt(value.shiftRight(i*32).intValue());
        b.putInt(key.getPublicExponent().intValue());
        return (java.util.Base64.getEncoder().encodeToString(b.array())+" PhoenixWindows@localhost\0").getBytes(StandardCharsets.US_ASCII);
    }
    private void send(String cmd,int a,int b,byte[] data) throws IOException {
        int id=command(cmd), sum=0; for (byte value:data) sum+=value&255;
        output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(id).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(~id).array());
        output.write(data); output.flush();
    }
    private byte[] bytes(int size) throws IOException {
        byte[] result=new byte[size]; int offset=0;
        while (offset<size) { int count=input.read(result,offset,size-offset); if (count<0) throw new EOFException(); offset+=count; }
        return result;
    }
    private Packet read() throws IOException {
        ByteBuffer h=ByteBuffer.wrap(bytes(24)).order(ByteOrder.LITTLE_ENDIAN); Packet p=new Packet();
        p.cmd=h.getInt(); p.a=h.getInt(); p.b=h.getInt(); int size=h.getInt(), checksum=h.getInt(), magic=h.getInt();
        if (size<0 || size>1048576 || magic!=~p.cmd) throw new IOException("Invalid ADB packet");
        p.data=bytes(size); int sum=0; for(byte value:p.data) sum+=value&255;
        if (sum!=checksum) throw new IOException("ADB checksum"); return p;
    }
    private void open(String service) throws IOException {
        local++; send("OPEN",local,0,(service+'\0').getBytes(StandardCharsets.UTF_8));
        Packet p=read(); if(p.cmd!=command("OKAY") || p.b!=local) throw new IOException("ADB stream rejected"); remote=p.a;
    }
    private void write(byte[] data) throws IOException {
        for(int offset=0;offset<data.length;offset+=4096) {
            byte[] chunk=java.util.Arrays.copyOfRange(data,offset,Math.min(data.length,offset+4096));
            send("WRTE",local,remote,chunk);
            while(true) {
                Packet p=read();
                if(p.cmd==command("OKAY")) break;
                if(p.cmd!=command("WRTE")) throw new IOException("ADB write rejected");
                received.write(p.data); send("OKAY",local,remote,new byte[0]);
            }
        }
    }
    void push(String path,byte[] data) throws IOException {
        open("sync:");
        ByteArrayOutputStream frames=new ByteArrayOutputStream();
        frame(frames,"SEND",(path+",33152").getBytes(StandardCharsets.UTF_8)); // regular file, 0600
        for(int offset=0;offset<data.length;offset+=4096) frame(frames,"DATA",java.util.Arrays.copyOfRange(data,offset,Math.min(data.length,offset+4096)));
        frames.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(command("DONE")).putInt((int)(System.currentTimeMillis()/1000)).array());
        // Sync replies can arrive while writes are being acknowledged. Send one small frame at a time.
        write(frames.toByteArray());
        while(received.size()<8) {
            Packet result=read();
            if(result.cmd!=command("WRTE")) throw new IOException("ADB sync failed");
            received.write(result.data); send("OKAY",local,remote,new byte[0]);
        }
        if(ByteBuffer.wrap(received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getInt()!=command("OKAY"))
            throw new IOException("ADB sync rejected file");
        received.reset();
        ByteArrayOutputStream quit=new ByteArrayOutputStream(); frame(quit,"QUIT",new byte[0]); write(quit.toByteArray());
        while(true) {
            Packet p=read();
            if(p.cmd==command("CLSE")) { send("CLSE",local,remote,new byte[0]); break; }
            if(p.cmd==command("WRTE")) send("OKAY",local,remote,new byte[0]);
        }
    }
    private static void frame(OutputStream out,String cmd,byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(command(cmd)).putInt(data.length).array()); out.write(data);
    }
    void shell(String fixedCommand) throws IOException {
        open("shell:"+fixedCommand);
        while(true) {
            Packet p=read();
            if(p.cmd==command("CLSE")) { send("CLSE",local,remote,new byte[0]); return; }
            if(p.cmd==command("WRTE")) send("OKAY",local,remote,new byte[0]);
        }
    }
    @Override public void close() throws IOException { socket.close(); }
}
