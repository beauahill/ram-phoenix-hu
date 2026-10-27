package com.ramphoenix.launcher;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;

/** Exercise the real wire code against an isolated fake adbd, never a vehicle. */
public class LocalAdbTest {
    private static final class Packet { String cmd; int a,b; byte[] data; }
    private static Packet read(Socket socket) throws Exception {
        DataInputStream in=new DataInputStream(socket.getInputStream()); byte[] raw=new byte[24]; in.readFully(raw);
        ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Packet p=new Packet(); byte[] cmd=new byte[4]; b.get(cmd); p.cmd=new String(cmd,StandardCharsets.US_ASCII);
        p.a=b.getInt();p.b=b.getInt();p.data=new byte[b.getInt()];in.readFully(p.data);return p;
    }
    private static void send(Socket socket,String command,int a,int b,byte[] data) throws Exception {
        int cmd=ByteBuffer.wrap(command.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int checksum=0;for(byte value:data)checksum+=value&255;
        socket.getOutputStream().write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(checksum).putInt(~cmd).array());
        socket.getOutputStream().write(data);socket.getOutputStream().flush();
    }
    private static KeyPair key() throws Exception { KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair(); }
    private static byte[] challenge(Socket socket,KeyPair pair) throws Exception {
        assertEquals("CNXN",read(socket).cmd);byte[] digest=new byte[20];new SecureRandom().nextBytes(digest);
        send(socket,"AUTH",1,0,digest);Packet signature=read(socket);assertEquals("AUTH",signature.cmd);assertEquals(2,signature.a);
        Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.DECRYPT_MODE,pair.getPublic());
        byte[] signed=rsa.doFinal(signature.data);assertArrayEquals(digest,Arrays.copyOfRange(signed,signed.length-20,signed.length));
        return digest;
    }
    @Test public void automaticReconnectNeverRequestsNewApproval() throws Exception {
        KeyPair pair=key();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> device=worker.submit(() -> {
                try(Socket socket=server.accept()) { socket.setSoTimeout(4000);byte[] digest=challenge(socket,pair);
                    send(socket,"AUTH",1,0,digest);assertEquals(-1,socket.getInputStream().read());
                }catch(Exception e){throw new RuntimeException(e);}
            });
            try { new LocalAdb(pair,server.getLocalPort(),false);fail("Must require approval"); }
            catch(IOException expected){assertTrue(expected.getMessage().contains("approval"));}
            device.get(5,TimeUnit.SECONDS);worker.shutdownNow();
        }
    }
    @Test public void explicitSetupOffersOnlyItsOwnPublicKey() throws Exception {
        KeyPair pair=key();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> device=worker.submit(() -> {
                try(Socket socket=server.accept()) { socket.setSoTimeout(4000);byte[] digest=challenge(socket,pair);
                    send(socket,"AUTH",1,0,digest);Packet publicKey=read(socket);assertEquals(3,publicKey.a);
                    String text=new String(publicKey.data,StandardCharsets.US_ASCII);assertTrue(text.endsWith(" PhoenixWindows@localhost\0"));
                    byte[] encoded=Base64.getDecoder().decode(text.split(" ")[0]);assertEquals(524,encoded.length);
                    ByteBuffer b=ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);assertEquals(64,b.getInt());b.getInt();
                    byte[] modulus=new byte[256];b.get(modulus);for(int i=0;i<128;i++){byte v=modulus[i];modulus[i]=modulus[255-i];modulus[255-i]=v;}
                    assertEquals(((java.security.interfaces.RSAPublicKey)pair.getPublic()).getModulus(),new java.math.BigInteger(1,modulus));
                    send(socket,"CNXN",0x01000000,4096,new byte[0]);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            try(LocalAdb adb=new LocalAdb(pair,server.getLocalPort(),true)) { /* authenticated */ }
            device.get(5,TimeUnit.SECONDS);worker.shutdownNow();
        }
    }
    @Test public void pushHandlesSyncReplyBeforeTransportAckAndClosesStreams() throws Exception {
        KeyPair pair=key();byte[] content=new byte[11000];new Random(7).nextBytes(content);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> device=worker.submit(() -> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(4000);challenge(socket,pair);send(socket,"CNXN",0x01000000,4096,new byte[0]);
                    Packet open=read(socket);assertEquals("OPEN",open.cmd);assertEquals("sync:\0",new String(open.data,StandardCharsets.UTF_8));
                    int id=open.a;send(socket,"OKAY",90,id,new byte[0]);
                    ByteArrayOutputStream transfer=new ByteArrayOutputStream();int expected=8+"/data/local/tmp/test,33152".length()+content.length+3*8+8;
                    while(transfer.size()<expected) {
                        Packet p=read(socket);assertEquals("WRTE",p.cmd);transfer.write(p.data);
                        if(transfer.size()==expected) send(socket,"WRTE",90,id,new byte[]{'O','K','A','Y',0,0,0,0});
                        send(socket,"OKAY",90,id,new byte[0]);
                        if(transfer.size()==expected) assertEquals("OKAY",read(socket).cmd);
                    }
                    ByteBuffer frames=ByteBuffer.wrap(transfer.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                    ByteArrayOutputStream received=new ByteArrayOutputStream();
                    while(frames.remaining()>0) {
                        byte[] label=new byte[4];frames.get(label);String name=new String(label,StandardCharsets.US_ASCII);int length=frames.getInt();
                        if(name.equals("DONE"))break;byte[] data=new byte[length];frames.get(data);if(name.equals("DATA"))received.write(data);
                    }
                    assertArrayEquals(content,received.toByteArray());
                    Packet quit=read(socket);assertEquals("WRTE",quit.cmd);send(socket,"OKAY",90,id,new byte[0]);
                    send(socket,"CLSE",90,id,new byte[0]);assertEquals("CLSE",read(socket).cmd);
                    open=read(socket);assertEquals("OPEN",open.cmd);assertEquals("shell:echo ready\0",new String(open.data,StandardCharsets.UTF_8));
                    send(socket,"OKAY",91,open.a,new byte[0]);send(socket,"WRTE",91,open.a,"ready\n".getBytes(StandardCharsets.UTF_8));
                    assertEquals("OKAY",read(socket).cmd);send(socket,"CLSE",91,open.a,new byte[0]);assertEquals("CLSE",read(socket).cmd);
                }catch(Exception e){throw new RuntimeException(e);}
            });
            try(LocalAdb adb=new LocalAdb(pair,server.getLocalPort(),false)) { adb.push("/data/local/tmp/test",content);adb.shell("echo ready"); }
            device.get(5,TimeUnit.SECONDS);worker.shutdownNow();
        }
    }
}
